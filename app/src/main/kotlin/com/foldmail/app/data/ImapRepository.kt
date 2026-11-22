package com.foldmail.app.data

import android.content.Context
import com.foldmail.app.Account
import com.foldmail.app.Attachment
import com.foldmail.app.Folder
import com.foldmail.app.ImapConfig
import com.foldmail.app.InlinePart
import com.foldmail.app.MailItem
import com.foldmail.app.SyncStatus
import com.foldmail.app.isRichHtml
import com.foldmail.app.slim
import com.foldmail.domain.model.EmailMessage
import com.foldmail.domain.model.normalizeAddress
import com.sun.mail.imap.IMAPFolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.Charset
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import javax.activation.DataHandler
import javax.mail.FetchProfile
import javax.mail.Flags
import javax.mail.Message
import javax.mail.Multipart
import javax.mail.Part
import javax.mail.Session
import javax.mail.Store
import javax.mail.UIDFolder
import javax.mail.internet.ContentType
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart
import javax.mail.internet.MimeUtility
import javax.mail.util.ByteArrayDataSource
import javax.mail.Folder as MailFolder

/** Generic mail accounts over IMAP or POP3 (receiving) and SMTP (sending). */
class ImapRepository private constructor(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = context.getSharedPreferences("imap_accounts", Context.MODE_PRIVATE)
    private val syncMutex = Mutex()
    private val store = MailStore(context)

    private val configs = ConcurrentHashMap<Long, ImapConfig>()
    val accounts = MutableStateFlow<List<Account>>(emptyList())
    val items = MutableStateFlow<List<MailItem>>(emptyList())
    val status = MutableStateFlow(SyncStatus())

    /** "accountId:gid" -> message (without HTML). */
    private val cache = ConcurrentHashMap<String, MailItem>()
    /** local message id -> (account id, gid) */
    private val index = ConcurrentHashMap<Long, Pair<Long, String>>()
    private var loadJob: Job? = null

    init {
        loadAccounts()
        if (accounts.value.isNotEmpty()) loadJob = scope.launch {
            loadFromDisk()
            sync()
        }
    }

    fun owns(localId: Long) = index.containsKey(localId)

    // ---------------------------------------------------------------- accounts

    private fun loadAccounts() {
        val raw = prefs.getString("accounts", null) ?: return
        try {
            val arr = JSONArray(raw)
            val list = ArrayList<Account>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.getLong("id")
                val cfg = ImapConfig(
                    protocol = o.getString("protocol"), email = o.getString("email"), username = o.getString("username"),
                    password = SecretBox.decrypt(o.getString("pw")),
                    host = o.getString("host"), port = o.getInt("port"), security = o.getString("security"),
                    smtpHost = o.getString("smtpHost"), smtpPort = o.getInt("smtpPort"), smtpSecurity = o.getString("smtpSecurity"),
                )
                configs[id] = cfg
                list += Account(id, o.getString("name"), cfg.email, o.getLong("color"))
            }
            accounts.value = list
        } catch (_: Exception) {
            // unreadable (e.g. keystore reset): the user adds the account again
        }
    }

    private fun saveAccounts() {
        val arr = JSONArray()
        accounts.value.forEach { a ->
            val c = configs[a.id] ?: return@forEach
            arr.put(
                JSONObject().put("id", a.id).put("name", a.name).put("color", a.colorArgb)
                    .put("protocol", c.protocol).put("email", c.email).put("username", c.username)
                    .put("pw", SecretBox.encrypt(c.password)).put("host", c.host).put("port", c.port).put("security", c.security)
                    .put("smtpHost", c.smtpHost).put("smtpPort", c.smtpPort).put("smtpSecurity", c.smtpSecurity),
            )
        }
        prefs.edit().putString("accounts", arr.toString()).apply()
    }

    /** Tests the login and, when it works, stores the account and starts the first download. */
    suspend fun addAccount(cfg: ImapConfig) {
        withContext(Dispatchers.IO) {
            val st = openStore(cfg)
            try { st.close() } catch (_: Exception) {}
        }
        if (accounts.value.any { it.email.equals(cfg.email, true) }) error("Tento účet už je pridaný")
        val id = maxOf(1000L, (accounts.value.maxOfOrNull { it.id } ?: 0L) + 1)
        configs[id] = cfg
        accounts.value = accounts.value + Account(id, cfg.email.substringBefore('@'), cfg.email, PALETTE[(id % PALETTE.size).toInt()])
        saveAccounts()
        refresh()
    }

    /** Forgets the account (and its saved password) and deletes its mail from this device. */
    fun removeAccount(accountId: Long) {
        configs.remove(accountId)
        accounts.value = accounts.value.filter { it.id != accountId }
        saveAccounts()
        cache.keys.removeAll { it.startsWith("$accountId:") }
        index.entries.removeAll { it.value.first == accountId }
        prefs.edit().remove("pop_read_$accountId").remove("pop_init_$accountId").apply()
        publish()
        scope.launch { store.deleteAccount(accountId) }
    }

    // ---------------------------------------------------------------- connection

    private fun storeProtocol(cfg: ImapConfig): String =
        if (cfg.protocol == "pop3") (if (cfg.security == "ssl") "pop3s" else "pop3")
        else (if (cfg.security == "ssl") "imaps" else "imap")

    private fun session(cfg: ImapConfig): Session {
        val p = Properties()
        val proto = storeProtocol(cfg)
        p["mail.$proto.connectiontimeout"] = "15000"
        p["mail.$proto.timeout"] = "40000"
        if (cfg.security == "starttls") p["mail.$proto.starttls.enable"] = "true"
        val smtp = if (cfg.smtpSecurity == "ssl") "smtps" else "smtp"
        p["mail.$smtp.connectiontimeout"] = "15000"
        p["mail.$smtp.timeout"] = "40000"
        p["mail.$smtp.auth"] = "true"
        if (cfg.smtpSecurity == "starttls") p["mail.$smtp.starttls.enable"] = "true"
        return Session.getInstance(p)
    }

    private fun openStore(cfg: ImapConfig): Store {
        val st = session(cfg).getStore(storeProtocol(cfg))
        st.connect(cfg.host, cfg.port, cfg.username, cfg.password)
        return st
    }

    // ---------------------------------------------------------------- sync

    fun refresh() {
        if (accounts.value.isEmpty()) return
        scope.launch { sync() }
    }

    suspend fun syncNow() {
        loadJob?.join()
        syncMutex.withLock { runSync(1500) }
    }

    private suspend fun sync() {
        if (!syncMutex.tryLock()) return
        try { runSync(FETCH_PER_SYNC) } finally { syncMutex.unlock() }
    }

    @Volatile private var retries = 0

    private suspend fun runSync(maxFetch: Int) {
        status.value = SyncStatus(syncing = true)
        val errors = ArrayList<String>()
        var offline = false
        for (account in accounts.value) {
            try {
                syncAccount(account, maxFetch)
            } catch (e: Exception) {
                // No network yet (e.g. right after opening the app): no red banner, try again shortly.
                if (isNetworkError(e)) offline = true else errors += "${account.email}: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        if (offline && retries < 4) {
            retries++
            scope.launch { kotlinx.coroutines.delay(4_000L * retries); sync() }
        } else if (!offline) retries = 0
        if (errors.isEmpty() || cache.isNotEmpty()) publish()
        status.value = SyncStatus(false, errors.firstOrNull())
    }

    private fun publish() { items.value = cache.values.toList() }

    private suspend fun loadFromDisk() {
        for (account in accounts.value) {
            val stored = withContext(Dispatchers.IO) { store.loadAll(account.id) }
            for (st in stored) {
                cache["${account.id}:${st.gmailId}"] = st.item
                index[st.item.email.localId] = account.id to st.gmailId
            }
        }
        if (cache.isNotEmpty() && items.value.isEmpty()) publish()
    }

    private suspend fun syncAccount(acc: Account, maxFetch: Int) = withContext(Dispatchers.IO) {
        val cfg = configs[acc.id] ?: return@withContext
        val hadCache = cache.keys.any { it.startsWith("${acc.id}:") }
        val fresh = ArrayList<MailItem>()
        val st = openStore(cfg)
        try {
            if (cfg.protocol == "pop3") {
                syncPop(st, cfg, acc, maxFetch, fresh)
            } else {
                syncImapFolder(st.getFolder("INBOX"), "i", cfg, acc, maxFetch, fresh)
                findFolder(st, "\\Sent", SENT_NAMES)?.let { syncImapFolder(it, "s", cfg, acc, maxFetch, fresh) }
            }
        } finally {
            try { st.close() } catch (_: Exception) {}
        }
        if (hadCache) Notifier.announce(context, fresh)
    }

    private fun findFolder(st: Store, attribute: String, names: List<String>): MailFolder? {
        val all = try { st.defaultFolder.list("*") } catch (_: Exception) { return null }
        all.firstOrNull { f -> (f as? IMAPFolder)?.attributes?.any { it.equals(attribute, true) } == true }?.let { return it }
        return all.firstOrNull { it.name.lowercase() in names || it.fullName.lowercase() in names }
    }

    private fun localIdOf(acc: Account, tag: String, uid: Long) =
        acc.id * ID_BLOCK + (if (tag == "s") SENT_OFFSET else 0L) + uid

    private fun syncImapFolder(folder: MailFolder, tag: String, cfg: ImapConfig, acc: Account, maxFetch: Int, fresh: MutableList<MailItem>) {
        val f = folder as IMAPFolder
        f.open(MailFolder.READ_WRITE)
        try {
            val count = f.messageCount
            if (count == 0) { removeMissing(acc, tag, emptySet(), 0L); return }
            val start = maxOf(1, count - WINDOW + 1)
            val msgs = f.getMessages(start, count)
            val fp = FetchProfile().apply { add(UIDFolder.FetchProfileItem.UID); add(FetchProfile.Item.FLAGS) }
            f.fetch(msgs, fp)
            val entries = msgs.map { Triple(it, f.getUID(it), it.isSet(Flags.Flag.SEEN)) }.sortedByDescending { it.second }
            val keep = HashSet<String>()
            var fetched = 0
            for ((msg, uid, seen) in entries) {
                val gid = "$tag$uid"
                val key = "${acc.id}:$gid"
                keep += key
                val cached = cache[key]
                if (cached != null) {
                    if (tag == "i" && cached.email.isRead != seen) setLocalRead(acc.id, gid, seen)
                    continue
                }
                if (fetched >= maxFetch) continue
                try {
                    val item = parse(msg as MimeMessage, acc, localIdOf(acc, tag, uid), "$tag\t$uid", isRead = seen || tag == "s")
                    register(acc, gid, item)
                    if (tag == "i") fresh += item
                    fetched++
                    if (fetched % 20 == 0) publish()
                } catch (_: Exception) {
                    // skip a broken message, the rest still arrives
                }
            }
            removeMissing(acc, tag, keep, if (start == 1) 0L else entries.last().second)
            publish()
        } finally {
            try { f.close(false) } catch (_: Exception) {}
        }
    }

    private fun syncPop(st: Store, cfg: ImapConfig, acc: Account, maxFetch: Int, fresh: MutableList<MailItem>) {
        val f = st.getFolder("INBOX") as com.sun.mail.pop3.POP3Folder
        f.open(MailFolder.READ_ONLY)
        try {
            val msgs = f.messages
            val readSet = prefs.getStringSet("pop_read_${acc.id}", emptySet())!!.toMutableSet()
            val firstRun = !prefs.getBoolean("pop_init_${acc.id}", false)
            val entries = msgs.map { m ->
                val uidl = f.getUID(m)
                Triple(m, uidl, "p" + (uidl.hashCode().toLong() and 0xFFFFFFFFL))
            }
            if (firstRun) {
                // what is already on the server counts as read, so the first download does not flood with alerts
                entries.forEach { readSet += it.third }
                prefs.edit().putStringSet("pop_read_${acc.id}", readSet).putBoolean("pop_init_${acc.id}", true).apply()
            }
            val keep = HashSet<String>()
            var fetched = 0
            for ((msg, uidl, gid) in entries.reversed()) {
                val key = "${acc.id}:$gid"
                keep += key
                if (cache.containsKey(key) || fetched >= maxFetch) continue
                try {
                    val hash = gid.drop(1).toLong()
                    val item = parse(msg as MimeMessage, acc, acc.id * ID_BLOCK + hash, "p\t$uidl", isRead = gid in readSet)
                    register(acc, gid, item)
                    fresh += item
                    fetched++
                    if (fetched % 20 == 0) publish()
                } catch (_: Exception) {
                }
            }
            cache.keys.filter { it.startsWith("${acc.id}:p") && it !in keep }.forEach { dropLocal(acc.id, it.substringAfter(':')) }
            publish()
        } finally {
            try { f.close(false) } catch (_: Exception) {}
        }
    }

    private fun removeMissing(acc: Account, tag: String, keep: Set<String>, minUid: Long) {
        cache.keys.filter { it.startsWith("${acc.id}:$tag") }.forEach { key ->
            val uid = key.substringAfter(':').drop(1).toLongOrNull() ?: return@forEach
            if (key !in keep && uid >= minUid) dropLocal(acc.id, key.substringAfter(':'))
        }
    }

    private fun dropLocal(accountId: Long, gid: String) {
        cache.remove("$accountId:$gid")?.let { index.remove(it.email.localId) }
        store.delete(accountId, gid)
    }

    private fun register(acc: Account, gid: String, item: MailItem) {
        store.writeHtml(acc.id, gid, item.html, item.inline)
        val slim = item.slim()
        store.write(acc.id, gid, "", slim)
        cache["${acc.id}:$gid"] = slim
        index[item.email.localId] = acc.id to gid
    }

    private fun setLocalRead(accountId: Long, gid: String, read: Boolean) {
        val key = "$accountId:$gid"
        val cur = cache[key] ?: return
        if (cur.email.isRead == read) return
        val upd = cur.copy(email = cur.email.copy(isRead = read))
        cache[key] = upd
        store.write(accountId, gid, "", upd)
    }

    // ---------------------------------------------------------------- parsing

    private class Collected {
        val plain = StringBuilder()
        val html = StringBuilder()
        val attachments = ArrayList<Attachment>()
        val inline = ArrayList<InlinePart>()
    }

    private fun addressText(a: javax.mail.Address): String =
        (a as? InternetAddress)?.let { if (it.personal.isNullOrBlank()) it.address else "${it.personal} <${it.address}>" } ?: a.toString()

    private fun parse(m: MimeMessage, acc: Account, localId: Long, ref: String, isRead: Boolean): MailItem {
        val c = Collected()
        walk(m, "", ref, c)
        val html = c.html.toString().ifBlank { null }
        val body = c.plain.toString().ifBlank { GmailParser.htmlToText(c.html.toString()) }.trim()
        val refs = m.getHeader("References", " ").orEmpty().split(Regex("\\s+")).filter { it.isNotBlank() }
        val email = EmailMessage(
            localId = localId, accountId = acc.id,
            messageId = m.getHeader("Message-ID")?.firstOrNull()?.trim(),
            inReplyTo = m.getHeader("In-Reply-To")?.firstOrNull()?.trim()?.ifEmpty { null },
            references = refs,
            subject = (try { m.subject } catch (_: Exception) { null }).orEmpty().ifBlank { "(bez predmetu)" },
            sentAtMillis = (m.sentDate ?: m.receivedDate)?.time ?: 0L,
            accountAddress = acc.email,
            from = try { m.from?.firstOrNull()?.let(::addressText).orEmpty() } catch (_: Exception) { "" },
            to = try { m.getRecipients(Message.RecipientType.TO)?.map(::addressText).orEmpty() } catch (_: Exception) { emptyList() },
            cc = try { m.getRecipients(Message.RecipientType.CC)?.map(::addressText).orEmpty() } catch (_: Exception) { emptyList() },
            isRead = isRead,
        )
        return MailItem(email, body, c.attachments, html, c.inline, rich = isRichHtml(html))
    }

    private fun textOf(p: Part): String {
        val cs = try { ContentType(p.contentType).getParameter("charset") } catch (_: Exception) { null }
        val charset = try { Charset.forName(MimeUtility.javaCharset(cs ?: "UTF-8")) } catch (_: Exception) { Charsets.UTF_8 }
        return try { String(p.inputStream.use { it.readBytes() }, charset) } catch (_: Exception) { "" }
    }

    private fun baseType(p: Part): String =
        try { ContentType(p.contentType).baseType.lowercase() } catch (_: Exception) { "application/octet-stream" }

    private fun walk(p: Part, path: String, ref: String, c: Collected) {
        if (p.isMimeType("multipart/*")) {
            val mp = p.content as Multipart
            for (i in 0 until mp.count) walk(mp.getBodyPart(i), if (path.isEmpty()) "$i" else "$path.$i", ref, c)
            return
        }
        val fileName = try { p.fileName?.let { MimeUtility.decodeText(it) } } catch (_: Exception) { p.fileName }
        val isAttachment = Part.ATTACHMENT.equals(p.disposition, true) || fileName != null
        if (!isAttachment && p.isMimeType("text/plain")) { if (c.plain.isEmpty()) c.plain.append(textOf(p)); return }
        if (!isAttachment && p.isMimeType("text/html")) { if (c.html.isEmpty()) c.html.append(textOf(p)); return }
        val mime = baseType(p)
        val cid = p.getHeader("Content-ID")?.firstOrNull()?.trim()?.trim('<', '>')
        if (!cid.isNullOrEmpty() && mime.startsWith("image/")) {
            val bytes = try { p.inputStream.use { it.readBytes() } } catch (_: Exception) { null }
            if (bytes != null && bytes.size < 3_000_000) {
                c.inline += InlinePart(cid, mime, java.util.Base64.getEncoder().encodeToString(bytes), null)
                if (fileName == null) return
            }
        }
        val name = fileName ?: (if (mime == "message/rfc822") "správa.eml" else "priloha")
        c.attachments += Attachment(name, null, mime, p.size.takeIf { it >= 0 }?.toLong(), remoteId = "$ref\t$path")
    }

    // ---------------------------------------------------------------- actions

    fun markRead(localIds: Collection<Long>) {
        val mine = localIds.mapNotNull { id -> index[id]?.let { id to it } }
        if (mine.isEmpty()) return
        items.update { list -> list.map { if (it.email.localId in localIds && owns(it.email.localId)) it.copy(email = it.email.copy(isRead = true)) else it } }
        scope.launch {
            for ((_, p) in mine) setLocalRead(p.first, p.second, true)
            publish()
            for ((accountId, group) in mine.map { it.second }.groupBy { it.first }) {
                val cfg = configs[accountId] ?: continue
                try {
                    if (cfg.protocol == "pop3") {
                        val set = prefs.getStringSet("pop_read_$accountId", emptySet())!!.toMutableSet()
                        group.forEach { set += it.second }
                        prefs.edit().putStringSet("pop_read_$accountId", set).apply()
                    } else {
                        val st = openStore(cfg)
                        try {
                            for ((_, gid) in group.filter { it.second.startsWith("i") }) {
                                val f = st.getFolder("INBOX") as IMAPFolder
                                f.open(MailFolder.READ_WRITE)
                                try { f.getMessageByUID(gid.drop(1).toLong())?.setFlag(Flags.Flag.SEEN, true) } finally { f.close(false) }
                            }
                        } finally { try { st.close() } catch (_: Exception) {} }
                    }
                } catch (_: Exception) {
                    // best effort; the next sync shows the real state
                }
            }
        }
    }

    /** Trash removes the mail here and on the server. */
    fun moveMessages(localIds: Collection<Long>, folder: Folder) {
        if (folder != Folder.Trash) return
        val mine = localIds.mapNotNull { id -> index[id]?.let { id to it } }
        if (mine.isEmpty()) return
        val gone = mine.map { it.first }.toSet()
        items.update { list -> list.filter { it.email.localId !in gone } }
        scope.launch {
            val removed = mine.map { it.second }
            removed.forEach { (acc, gid) -> dropLocal(acc, gid) }
            var failed: String? = null
            for ((accountId, group) in removed.groupBy { it.first }) {
                val cfg = configs[accountId] ?: continue
                try {
                    val st = openStore(cfg)
                    try {
                        if (cfg.protocol == "pop3") {
                            val f = st.getFolder("INBOX") as com.sun.mail.pop3.POP3Folder
                            f.open(MailFolder.READ_WRITE)
                            try {
                                val wanted = group.map { it.second }.toSet()
                                f.messages.filter { "p" + (f.getUID(it).hashCode().toLong() and 0xFFFFFFFFL) in wanted }
                                    .forEach { it.setFlag(Flags.Flag.DELETED, true) }
                            } finally { f.close(true) }
                        } else {
                            val trash = findFolder(st, "\\Trash", TRASH_NAMES)
                            for ((tag, gids) in group.map { it.second }.groupBy { it.take(1) }) {
                                val f = (if (tag == "i") st.getFolder("INBOX") else findFolder(st, "\\Sent", SENT_NAMES)) as? IMAPFolder ?: continue
                                f.open(MailFolder.READ_WRITE)
                                try {
                                    for (g in gids) {
                                        val msg = f.getMessageByUID(g.drop(1).toLong()) ?: continue
                                        if (trash != null && trash.fullName != f.fullName) f.copyMessages(arrayOf(msg), trash)
                                        msg.setFlag(Flags.Flag.DELETED, true)
                                    }
                                } finally { f.close(true) }
                            }
                        }
                    } finally { try { st.close() } catch (_: Exception) {} }
                } catch (e: Exception) {
                    failed = e.message ?: e.javaClass.simpleName
                }
            }
            publish()
            if (failed != null) {
                status.value = SyncStatus(false, "Mazanie na serveri zlyhalo: $failed")
                sync()
            }
        }
    }

    suspend fun resolveHtml(localId: Long): String? = withContext(Dispatchers.IO) {
        val (acc, gid) = index[localId] ?: return@withContext null
        val (html, inline) = store.readHtml(acc, gid) ?: return@withContext null
        var out = html
        for (p in inline) {
            val data = p.data ?: continue
            val (mime, small) = com.foldmail.app.ui.ImageShrinker.shrinkBase64(p.mime, data)
            out = out.replace(Regex("cid:" + Regex.escape(p.cid), RegexOption.IGNORE_CASE), "data:$mime;base64,$small")
        }
        out
    }

    /** Downloads an attachment into the cache; returns it with a shareable uri. */
    suspend fun fetchAttachment(localId: Long, a: Attachment): Attachment? = withContext(Dispatchers.IO) {
        val (accountId, gid) = index[localId] ?: return@withContext null
        val cfg = configs[accountId] ?: return@withContext null
        val ref = a.remoteId?.split('\t') ?: return@withContext null   // kind, uid, path
        if (ref.size < 3) return@withContext null
        val dir = File(context.cacheDir, "mail/$accountId-$gid").apply { mkdirs() }
        val file = File(dir, a.name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "priloha" })
        if (!file.exists() || file.length() == 0L) {
            val st = openStore(cfg)
            try {
                val msg: Message?
                val closeable: MailFolder
                if (cfg.protocol == "pop3") {
                    val f = st.getFolder("INBOX") as com.sun.mail.pop3.POP3Folder
                    f.open(MailFolder.READ_ONLY)
                    closeable = f
                    msg = f.messages.firstOrNull { f.getUID(it) == ref[1] }
                } else {
                    val f = (if (ref[0] == "i") st.getFolder("INBOX") else findFolder(st, "\\Sent", SENT_NAMES)) as IMAPFolder
                    f.open(MailFolder.READ_ONLY)
                    closeable = f
                    msg = f.getMessageByUID(ref[1].toLong())
                }
                try {
                    var part: Part = msg ?: return@withContext null
                    for (idx in ref[2].split('.').filter { it.isNotEmpty() }) part = (part.content as Multipart).getBodyPart(idx.toInt())
                    file.writeBytes(part.inputStream.use { it.readBytes() })
                } finally { try { closeable.close(false) } catch (_: Exception) {} }
            } finally { try { st.close() } catch (_: Exception) {} }
        }
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        a.copy(uri = uri.toString(), sizeBytes = file.length())
    }

    // ---------------------------------------------------------------- sending

    fun send(to: List<String>, subject: String, body: String, inReplyTo: String?, attachments: List<Attachment>, accountId: Long, cc: List<String>, bcc: List<String>) {
        val account = accounts.value.firstOrNull { it.id == accountId } ?: return
        val cfg = configs[account.id] ?: return
        val optimistic = -System.nanoTime()
        items.update { list ->
            list + MailItem(
                EmailMessage(
                    localId = optimistic, accountId = account.id, messageId = null, inReplyTo = inReplyTo,
                    references = emptyList(), subject = subject.ifBlank { "(bez predmetu)" },
                    sentAtMillis = System.currentTimeMillis(), accountAddress = account.email,
                    from = account.email, to = to, cc = cc, isRead = true,
                ),
                body, attachments,
            )
        }
        scope.launch {
            try {
                val session = session(cfg)
                val msg = MimeMessage(session)
                msg.setFrom(InternetAddress(cfg.email))
                msg.setRecipients(Message.RecipientType.TO, to.joinToString(",").let { InternetAddress.parse(it, false) })
                if (cc.isNotEmpty()) msg.setRecipients(Message.RecipientType.CC, InternetAddress.parse(cc.joinToString(","), false))
                if (bcc.isNotEmpty()) msg.setRecipients(Message.RecipientType.BCC, InternetAddress.parse(bcc.joinToString(","), false))
                msg.setSubject(subject, "UTF-8")
                if (!inReplyTo.isNullOrBlank()) { msg.setHeader("In-Reply-To", inReplyTo); msg.setHeader("References", inReplyTo) }
                if (attachments.none { it.uri != null }) {
                    msg.setText(body, "UTF-8")
                } else {
                    val mp = MimeMultipart("mixed")
                    mp.addBodyPart(MimeBodyPart().apply { setText(body, "UTF-8") })
                    for (a in attachments) {
                        val uri = a.uri ?: continue
                        val bytes = context.contentResolver.openInputStream(android.net.Uri.parse(uri))?.use { it.readBytes() } ?: continue
                        mp.addBodyPart(MimeBodyPart().apply {
                            dataHandler = DataHandler(ByteArrayDataSource(bytes, a.mimeType))
                            fileName = MimeUtility.encodeText(a.name)
                        })
                    }
                    msg.setContent(mp)
                }
                msg.saveChanges()
                val proto = if (cfg.smtpSecurity == "ssl") "smtps" else "smtp"
                val tr = session.getTransport(proto)
                tr.connect(cfg.smtpHost, cfg.smtpPort, cfg.username, cfg.password)
                try { tr.sendMessage(msg, msg.allRecipients) } finally { try { tr.close() } catch (_: Exception) {} }

                if (cfg.protocol == "pop3") {
                    // POP3 has no Sent folder: keep our own copy on the device
                    val millis = System.currentTimeMillis()
                    val gid = "l$millis"
                    val item = MailItem(
                        EmailMessage(
                            localId = account.id * ID_BLOCK + LOCAL_OFFSET + millis % 1_000_000_000L, accountId = account.id,
                            messageId = msg.getHeader("Message-ID")?.firstOrNull(), inReplyTo = inReplyTo, references = emptyList(),
                            subject = subject.ifBlank { "(bez predmetu)" }, sentAtMillis = millis, accountAddress = account.email,
                            from = account.email, to = to, isRead = true,
                        ),
                        body, attachments.map { it.copy(uri = null) },
                    )
                    register(account, gid, item)
                    publish()
                } else if (!cfg.host.contains("gmail", true)) {
                    // most IMAP servers do not file SMTP mail in Sent by themselves
                    try {
                        val st = openStore(cfg)
                        try {
                            findFolder(st, "\\Sent", SENT_NAMES)?.let { sent ->
                                sent.open(MailFolder.READ_WRITE)
                                try { sent.appendMessages(arrayOf(msg)) } finally { sent.close(false) }
                            }
                        } finally { try { st.close() } catch (_: Exception) {} }
                    } catch (_: Exception) { }
                    sync()
                } else {
                    sync()
                }
            } catch (e: Exception) {
                status.value = SyncStatus(false, "Odoslanie zlyhalo: ${e.message ?: e.javaClass.simpleName}")
                items.update { list -> list.filter { it.email.localId != optimistic } }
            }
        }
    }

    companion object {
        @Volatile private var instance: ImapRepository? = null
        fun get(context: Context): ImapRepository =
            instance ?: synchronized(this) { instance ?: ImapRepository(context.applicationContext).also { instance = it } }

        private const val FETCH_PER_SYNC = 150
        private const val WINDOW = 5000
        private const val ID_BLOCK = 10_000_000_000L
        private const val SENT_OFFSET = 5_000_000_000L
        private const val LOCAL_OFFSET = 6_000_000_000L
        private val PALETTE = listOf(0xFF5AC8FA, 0xFFFF9500, 0xFF34C759, 0xFFAF52DE, 0xFFFF2D55, 0xFFFFCC00, 0xFF0A28F0)
        private val SENT_NAMES = listOf(
            "sent", "sent items", "sent mail", "sent messages", "odoslané", "odoslaná pošta", "odeslaná pošta", "odeslané", "[gmail]/sent mail",
        )
        private val TRASH_NAMES = listOf(
            "trash", "deleted items", "deleted messages", "kôš", "koš", "odstránená pošta", "odstranená pošta", "[gmail]/trash",
        )
    }
}
