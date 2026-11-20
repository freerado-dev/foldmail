package com.foldmail.app.data

import android.content.Context
import com.foldmail.app.Account
import com.foldmail.app.Attachment
import com.foldmail.app.MailItem
import com.foldmail.app.SyncStatus
import com.foldmail.domain.model.EmailMessage
import com.foldmail.domain.model.normalizeAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** Real Gmail accounts: persists the account list, downloads inbox + sent mail, sends and marks read. */
class GmailRepository(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = context.getSharedPreferences("gmail_accounts", Context.MODE_PRIVATE)
    private val syncMutex = Mutex()

    val accounts = MutableStateFlow(load())
    val items = MutableStateFlow<List<MailItem>>(emptyList())
    val status = MutableStateFlow(SyncStatus())

    /** local message id -> (account id, gmail id, thread id) */
    private val gmailIds = ConcurrentHashMap<Long, Triple<Long, String, String>>()
    /** Message-ID header -> gmail thread id, so replies stay in the same Gmail thread. */
    private val threadByMessageId = ConcurrentHashMap<String, String>()

    init {
        if (accounts.value.isNotEmpty()) refresh()
    }

    private fun load(): List<Account> {
        val raw = prefs.getString("accounts", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Account(o.getLong("id"), o.getString("name"), o.getString("email"), o.getLong("color"))
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun save(list: List<Account>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name).put("email", it.email).put("color", it.colorArgb)) }
        prefs.edit().putString("accounts", arr.toString()).apply()
    }

    suspend fun addAccount(accessToken: String) {
        val email = withContext(Dispatchers.IO) { GmailApi.profileEmail(accessToken) }.lowercase()
        if (accounts.value.none { it.email.equals(email, true) }) {
            val id = (accounts.value.maxOfOrNull { it.id } ?: 0L) + 1
            val color = PALETTE[((id - 1).toInt()) % PALETTE.size]
            val list = accounts.value + Account(id, email.substringBefore('@'), email, color)
            save(list)
            accounts.value = list
        }
        refresh()
    }

    fun refresh() {
        if (accounts.value.isEmpty()) return
        scope.launch { sync() }
    }

    private suspend fun sync() {
        if (!syncMutex.tryLock()) return
        try {
            status.value = SyncStatus(syncing = true)
            val all = ArrayList<MailItem>()
            val errors = ArrayList<String>()
            for (account in accounts.value) {
                try {
                    all += syncAccount(account)
                } catch (e: Exception) {
                    errors += "${account.email}: ${e.message ?: e.javaClass.simpleName}"
                }
            }
            if (errors.isEmpty() || all.isNotEmpty()) items.value = all
            status.value = SyncStatus(false, errors.firstOrNull())
        } finally {
            syncMutex.unlock()
        }
    }

    private suspend fun syncAccount(account: Account): List<MailItem> {
        val token = GoogleAuth.accessToken(context, account.email)
        val ids = (GmailApi.listIds(token, "INBOX", MAX_PER_LABEL) + GmailApi.listIds(token, "SENT", MAX_PER_LABEL)).distinct()
        val gate = Semaphore(6)
        return coroutineScope {
            ids.map { id ->
                async {
                    gate.withPermit {
                        val parsed = GmailParser.parse(GmailApi.getMessage(token, id), account.id, account.email)
                        gmailIds[parsed.item.email.localId] = Triple(account.id, parsed.gmailId, parsed.threadId)
                        parsed.item.email.messageId?.let { threadByMessageId[it] = parsed.threadId }
                        parsed.item
                    }
                }
            }.awaitAll()
        }
    }

    fun send(to: List<String>, subject: String, body: String, inReplyTo: String?, attachments: List<Attachment>, accountId: Long) {
        val account = accounts.value.firstOrNull { it.id == accountId } ?: accounts.value.firstOrNull() ?: return
        // Show it immediately; the next sync replaces it with the real sent message.
        val localId = -System.nanoTime()
        items.update { list ->
            list + MailItem(
                EmailMessage(
                    localId = localId, accountId = account.id, messageId = null, inReplyTo = inReplyTo,
                    references = emptyList(), subject = subject.ifBlank { "(bez predmetu)" },
                    sentAtMillis = System.currentTimeMillis(), accountAddress = account.email,
                    from = account.email, to = to, isRead = true,
                ),
                body, attachments,
            )
        }
        scope.launch {
            try {
                val token = GoogleAuth.accessToken(context, account.email)
                val parts = attachments.mapNotNull { a ->
                    val uri = a.uri ?: return@mapNotNull null
                    val bytes = context.contentResolver.openInputStream(android.net.Uri.parse(uri))?.use { it.readBytes() } ?: return@mapNotNull null
                    MimeBuilder.Part(a.name, a.mimeType, bytes)
                }
                val raw = MimeBuilder.build(
                    from = account.email, to = to, subject = subject, body = body,
                    inReplyTo = inReplyTo, references = listOfNotNull(inReplyTo), parts = parts,
                )
                GmailApi.send(token, raw, inReplyTo?.let { threadByMessageId[it] })
                sync()
            } catch (e: Exception) {
                status.value = SyncStatus(false, "Odoslanie zlyhalo: ${e.message ?: e.javaClass.simpleName}")
                // drop the optimistic copy
                items.update { list -> list.filter { it.email.localId != localId } }
            }
        }
    }

    fun markRead(localIds: Collection<Long>) {
        if (localIds.isEmpty()) return
        items.update { list -> list.map { if (it.email.localId in localIds) it.copy(email = it.email.copy(isRead = true)) else it } }
        scope.launch {
            for (id in localIds) {
                val (accountId, gmailId, _) = gmailIds[id] ?: continue
                val account = accounts.value.firstOrNull { it.id == accountId } ?: continue
                try {
                    GmailApi.markRead(GoogleAuth.accessToken(context, account.email), gmailId)
                } catch (_: Exception) {
                    // best effort; the next sync shows the real state
                }
            }
        }
    }

    companion object {
        private const val MAX_PER_LABEL = 40
        private val PALETTE = listOf(0xFFFF3B30, 0xFF0A28F0, 0xFF34C759, 0xFFFFCC00, 0xFFAF52DE, 0xFFFF2D55, 0xFF5AC8FA)
    }
}
