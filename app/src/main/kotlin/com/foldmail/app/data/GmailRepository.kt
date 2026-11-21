package com.foldmail.app.data

import android.content.Context
import com.foldmail.app.Account
import com.foldmail.app.Attachment
import com.foldmail.app.MailItem
import com.foldmail.app.slim
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
class GmailRepository private constructor(private val context: Context) {
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

    private val store = MailStore(context)
    private var loadJob: kotlinx.coroutines.Job? = null

    init {
        // Show the mail saved on the device immediately, then fetch only what is new.
        if (accounts.value.isNotEmpty()) loadJob = scope.launch {
            loadFromDisk()
            sync()
        }
    }

    /** Waits for the start-up load and runs a full sync (used by the background worker). */
    suspend fun syncNow() {
        loadJob?.join()
        syncMutex.withLock { runSync(maxFetch = 1500) }
    }

    private suspend fun loadFromDisk() {
        for (account in accounts.value) {
            val stored = withContext(Dispatchers.IO) { store.loadAll(account.id) }
            for (st in stored) {
                cache["${account.id}:${st.gmailId}"] = st.item
                gmailIds[st.item.email.localId] = Triple(account.id, st.gmailId, st.threadId)
                st.item.email.messageId?.let { threadByMessageId[it] = st.threadId }
            }
        }
        if (cache.isNotEmpty() && items.value.isEmpty()) items.value = cache.values.toList()
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
            runSync()
        } finally {
            syncMutex.unlock()
        }
    }

    private suspend fun runSync(maxFetch: Int = FETCH_PER_SYNC) {
        status.value = SyncStatus(syncing = true)
        val errors = ArrayList<String>()
        for (account in accounts.value) {
            try {
                syncAccount(account, maxFetch)
            } catch (e: Exception) {
                errors += "${account.email}: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        if (errors.isEmpty() || cache.isNotEmpty()) items.value = cache.values.toList()
        status.value = SyncStatus(false, errors.firstOrNull())
    }

    /**
     * Brings the device up to date with Gmail: all received and sent mail (no spam, trash or drafts).
     * New mail is fetched first; the rest of the history is downloaded in portions of [maxFetch] per sync.
     */
    private suspend fun syncAccount(account: Account, maxFetch: Int) {
        val token = GoogleAuth.accessToken(context, account.email)
        val ids = GmailApi.listAllIds(token)
        val prefix = "${account.id}:"
        val hadCache = cache.keys.any { it.startsWith(prefix) }
        val fresh = ids.filter { !cache.containsKey(prefix + it) }.take(maxFetch)
        // The newest few are downloaded again to pick up read/unread changes.
        val toFetch = (fresh + ids.take(REFRESH_NEWEST)).distinct()
        val missingTotal = ids.count { !cache.containsKey(prefix + it) }
        val gate = Semaphore(4)
        var failure: Exception? = null
        var done = 0
        for (chunk in toFetch.chunked(60)) {
            coroutineScope {
                chunk.map { id ->
                    async {
                        gate.withPermit {
                            try {
                                val parsed = GmailParser.parse(GmailApi.getMessage(token, id), account.id, account.email)
                                gmailIds[parsed.item.email.localId] = Triple(account.id, parsed.gmailId, parsed.threadId)
                                parsed.item.email.messageId?.let { threadByMessageId[it] = parsed.threadId }
                                store.writeHtml(account.id, parsed.gmailId, parsed.item.html, parsed.item.inline)
                                val slim = parsed.item.slim()
                                store.write(account.id, parsed.gmailId, parsed.threadId, slim)
                                cache[prefix + id] = slim
                            } catch (e: Exception) {
                                failure = e   // keep what we have; the next sync continues where this one stopped
                            }
                        }
                    }
                }.awaitAll()
            }
            done += chunk.size
            items.value = cache.values.toList()
            val have = cache.keys.count { it.startsWith(prefix) }
            status.value = SyncStatus(true, null, if (have < ids.size) "Sťahujem správy: $have / ${ids.size}" else null)
        }
        // Announce unread incoming mail that appeared since the last sync (not on the very first download).
        if (hadCache) {
            val recent = System.currentTimeMillis() - 3L * 24 * 3600 * 1000
            val news = fresh.mapNotNull { cache[prefix + it] }
                .filter { !it.email.isOutgoing && !it.email.isRead && it.email.sentAtMillis > recent }
                .sortedBy { it.email.sentAtMillis }
                .map {
                    val from = it.email.from
                    val name = from.substringBefore('<').trim().trim('"').ifEmpty { normalizeAddress(from) }
                    NewMail(it.email.localId, name, it.email.subject, com.foldmail.app.previewOf(it.body))
                }
            Notifier.notifyNew(context, news)
        }
        // Forget messages that disappeared on the server (deleted elsewhere, moved to spam or trash).
        val keep = ids.mapTo(HashSet()) { prefix + it }
        cache.keys.filter { it.startsWith(prefix) && it !in keep }.forEach {
            cache.remove(it)
            store.delete(account.id, it.substringAfter(':'))
        }
        if (failure != null && missingTotal > 0 && done == 0) throw failure!!
    }

    fun send(to: List<String>, subject: String, body: String, inReplyTo: String?, attachments: List<Attachment>, accountId: Long, cc: List<String>, bcc: List<String>) {
        val account = accounts.value.firstOrNull { it.id == accountId } ?: accounts.value.firstOrNull() ?: return
        // Show it immediately; the next sync replaces it with the real sent message.
        val localId = -System.nanoTime()
        items.update { list ->
            list + MailItem(
                EmailMessage(
                    localId = localId, accountId = account.id, messageId = null, inReplyTo = inReplyTo,
                    references = emptyList(), subject = subject.ifBlank { "(bez predmetu)" },
                    sentAtMillis = System.currentTimeMillis(), accountAddress = account.email,
                    from = account.email, to = to, cc = cc, isRead = true,
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
                    from = account.email, to = to, cc = cc, bcc = bcc, subject = subject, body = body,
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
        ids.forEach { id -> gmailIds[id]?.let { (acc, gid, _) -> store.delete(acc, gid) } }
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
        val dir = java.io.File(context.cacheDir, "mail/$gmailId").apply { mkdirs() }
        val file = java.io.File(dir, a.name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "priloha" })
        if (!file.exists() || file.length() == 0L) {
            file.writeBytes(GmailApi.attachment(GoogleAuth.accessToken(context, email), gmailId, attId))
        }
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        a.copy(uri = uri.toString(), sizeBytes = file.length())
    }

    /** Replaces cid: references in the HTML with the embedded images. */
    suspend fun resolveHtml(localId: Long): String? = withContext(Dispatchers.IO) {
        val (email, gmailId, _) = tokenFor(localId) ?: return@withContext null
        val accountId = gmailIds[localId]?.first ?: return@withContext null
        val (html0, inline) = store.readHtml(accountId, gmailId) ?: return@withContext null
        var html = html0
        if (inline.isEmpty()) return@withContext html
        val token = GoogleAuth.accessToken(context, email)
        for (p in inline) {
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
            // keep the saved copies in step with what is shown
            for (id in localIds) {
                val (acc, gid, tid) = gmailIds[id] ?: continue
                val key = "$acc:$gid"
                val cur = cache[key] ?: continue
                if (!cur.email.isRead) {
                    val upd = cur.copy(email = cur.email.copy(isRead = true))
                    cache[key] = upd
                    store.write(acc, gid, tid, upd)
                }
            }
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
        @Volatile private var instance: GmailRepository? = null
        fun get(context: Context): GmailRepository =
            instance ?: synchronized(this) { instance ?: GmailRepository(context.applicationContext).also { instance = it } }

        private const val FETCH_PER_SYNC = 400
        private const val REFRESH_NEWEST = 25
        private val PALETTE = listOf(0xFFFF3B30, 0xFF0A28F0, 0xFF34C759, 0xFFFFCC00, 0xFFAF52DE, 0xFFFF2D55, 0xFF5AC8FA)
    }
}
