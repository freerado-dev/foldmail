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
    /** "accountId:gmailId" -> parsed message, so refreshes only download new mail. */
    private val cache = ConcurrentHashMap<String, MailItem>()
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
        val ids = (
            GmailApi.listIds(token, "INBOX", INBOX_COUNT) + GmailApi.listIds(token, "SENT", SENT_COUNT) +
                GmailApi.listIdsQuery(token, "-in:inbox -in:sent -in:trash -in:spam -in:drafts", ARCHIVE_COUNT)
            ).distinct()
        // Download only what is new (plus the newest few, to pick up read/unread changes).
        val toFetch = (ids.filter { !cache.containsKey("${account.id}:$it") } + ids.take(REFRESH_NEWEST)).distinct()
        val gate = Semaphore(4)
        var failure: Exception? = null
        coroutineScope {
            toFetch.map { id ->
                async {
                    gate.withPermit {
                        try {
                            val parsed = GmailParser.parse(GmailApi.getMessage(token, id), account.id, account.email)
                            gmailIds[parsed.item.email.localId] = Triple(account.id, parsed.gmailId, parsed.threadId)
                            parsed.item.email.messageId?.let { threadByMessageId[it] = parsed.threadId }
                            cache["${account.id}:$id"] = parsed.item
                        } catch (e: Exception) {
                            failure = e   // keep what we have; the next sync continues where this one stopped
                        }
                    }
                }
            }.awaitAll()
        }
        // Forget messages that disappeared on the server (deleted elsewhere).
        val keep = ids.map { "${account.id}:$it" }.toSet()
        cache.keys.filter { it.startsWith("${account.id}:") && it !in keep }.forEach { cache.remove(it) }
        val result = ids.mapNotNull { cache["${account.id}:$it"] }
        if (failure != null && result.isEmpty()) throw failure!!
        return result
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

    /** Trash = delete for real: removes from the list now and from Gmail in the background. */
    fun moveMessages(localIds: Collection<Long>, folder: com.foldmail.app.Folder) {
        if (localIds.isEmpty()) return
        val ids = localIds.toSet()
        if (folder != com.foldmail.app.Folder.Trash) {
            // Archive = leave the Inbox label, Inbox = get it back (received mail only).
            val incoming = items.value.filter { it.email.localId in ids && !it.email.isOutgoing }.map { it.email.localId }
            scope.launch {
                try {
                    for (id in incoming) {
                        val (accountId, gmailId, _) = gmailIds[id] ?: continue
                        val account = accounts.value.firstOrNull { it.id == accountId } ?: continue
                        val token = GoogleAuth.accessToken(context, account.email)
                        if (folder == com.foldmail.app.Folder.Archive) GmailApi.modifyLabels(token, gmailId, emptyList(), listOf("INBOX"))
                        else GmailApi.modifyLabels(token, gmailId, listOf("INBOX"), emptyList())
                    }
                } catch (e: Exception) {
                    status.value = SyncStatus(false, "Zmena v Gmaile zlyhala: ${e.message ?: e.javaClass.simpleName}")
                }
            }
            return
        }
        items.update { list -> list.filter { it.email.localId !in ids } }
        cache.entries.removeAll { it.value.email.localId in ids }
        scope.launch {
            var failed: String? = null
            for (id in ids) {
                val (accountId, gmailId, _) = gmailIds[id] ?: continue
                val account = accounts.value.firstOrNull { it.id == accountId } ?: continue
                try {
                    GmailApi.trash(GoogleAuth.accessToken(context, account.email), gmailId)
                } catch (e: Exception) {
                    failed = e.message ?: e.javaClass.simpleName
                }
            }
            if (failed != null) {
                status.value = SyncStatus(false, "Mazanie v Gmaile zlyhalo: $failed")
                sync()
            }
        }
    }

    fun setStarred(localIds: Collection<Long>, starred: Boolean) {
        scope.launch {
            try {
                for (id in localIds) {
                    val (accountId, gmailId, _) = gmailIds[id] ?: continue
                    val account = accounts.value.firstOrNull { it.id == accountId } ?: continue
                    val token = GoogleAuth.accessToken(context, account.email)
                    if (starred) GmailApi.modifyLabels(token, gmailId, listOf("STARRED"), emptyList())
                    else GmailApi.modifyLabels(token, gmailId, emptyList(), listOf("STARRED"))
                }
            } catch (e: Exception) {
                status.value = SyncStatus(false, "Hviezdička v Gmaile zlyhala: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun tokenFor(localId: Long): Triple<String, String, Long>? {
        val (accountId, gmailId, _) = gmailIds[localId] ?: return null
        val account = accounts.value.firstOrNull { it.id == accountId } ?: return null
        return Triple(account.email, gmailId, accountId)
    }

    /** Downloads an attachment into the cache and returns it with a shareable content uri. */
    suspend fun fetchAttachment(localId: Long, a: Attachment): Attachment? = withContext(Dispatchers.IO) {
        val attId = a.remoteId ?: return@withContext null
        val (email, gmailId, _) = tokenFor(localId) ?: return@withContext null
        val bytes = GmailApi.attachment(GoogleAuth.accessToken(context, email), gmailId, attId)
        val dir = java.io.File(context.cacheDir, "mail/$gmailId").apply { mkdirs() }
        val file = java.io.File(dir, a.name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "priloha" })
        file.writeBytes(bytes)
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        a.copy(uri = uri.toString(), sizeBytes = bytes.size.toLong())
    }

    /** Replaces cid: references in the HTML with the embedded images. */
    suspend fun resolveHtml(localId: Long): String? = withContext(Dispatchers.IO) {
        val item = items.value.firstOrNull { it.email.localId == localId } ?: return@withContext null
        var html = item.html ?: return@withContext null
        if (item.inline.isEmpty()) return@withContext html
        val (email, gmailId, _) = tokenFor(localId) ?: return@withContext html
        val token = GoogleAuth.accessToken(context, email)
        for (p in item.inline) {
            val data = p.data ?: try {
                java.util.Base64.getEncoder().encodeToString(GmailApi.attachment(token, gmailId, p.attachmentId ?: continue))
            } catch (_: Exception) { continue }
            html = html.replace(Regex("cid:" + Regex.escape(p.cid), RegexOption.IGNORE_CASE), "data:${p.mime};base64,$data")
        }
        html
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
        private const val INBOX_COUNT = 200
        private const val SENT_COUNT = 100
        private const val ARCHIVE_COUNT = 50
        private const val REFRESH_NEWEST = 25
        private val PALETTE = listOf(0xFFFF3B30, 0xFF0A28F0, 0xFF34C759, 0xFFFFCC00, 0xFFAF52DE, 0xFFFF2D55, 0xFF5AC8FA)
    }
}
