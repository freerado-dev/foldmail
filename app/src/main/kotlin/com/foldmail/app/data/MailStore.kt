package com.foldmail.app.data

import android.content.Context
import com.foldmail.app.Attachment
import com.foldmail.app.InlinePart
import com.foldmail.app.MailItem
import com.foldmail.app.isRichHtml
import com.foldmail.domain.model.EmailMessage
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Keeps downloaded mail on the device. Per message there are two small files: `<id>.json` (headers, text,
 * attachments – always loaded) and `<id>.html` (HTML + inline images – read only when a mail is displayed),
 * so thousands of mails stay light in memory.
 */
class MailStore(context: Context) {
    private val root = File(context.filesDir, "mail")

    class Stored(val gmailId: String, val threadId: String, val item: MailItem)

    private fun dir(accountId: Long) = File(root, accountId.toString()).apply { mkdirs() }

    private fun atomic(target: File, text: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text)
        tmp.renameTo(target)
    }

    /** Headers, text and attachments (HTML is not part of it). */
    fun write(accountId: Long, gmailId: String, threadId: String, item: MailItem) {
        try { atomic(File(dir(accountId), "$gmailId.json"), toJson(gmailId, threadId, item).toString()) } catch (_: Exception) {}
    }

    fun writeHtml(accountId: Long, gmailId: String, html: String?, inline: List<InlinePart>) {
        if (html == null) return
        try {
            val o = JSONObject().put("html", html).put("inline", inlineToJson(inline))
            atomic(File(dir(accountId), "$gmailId.html"), o.toString())
        } catch (_: Exception) {}
    }

    /** HTML and inline images of a message, or null. Also understands the older single-file format. */
    fun readHtml(accountId: Long, gmailId: String): Pair<String, List<InlinePart>>? = try {
        val f = File(dir(accountId), "$gmailId.html")
        val o = if (f.exists()) JSONObject(f.readText()) else JSONObject(File(dir(accountId), "$gmailId.json").readText())
        if (o.has("html") && !o.isNull("html")) o.getString("html") to inlineFromJson(o.optJSONArray("inline")) else null
    } catch (_: Exception) {
        null
    }

    /** Removes everything stored for an account. */
    fun deleteAccount(accountId: Long) {
        try { File(root, accountId.toString()).deleteRecursively() } catch (_: Exception) {}
    }

    fun delete(accountId: Long, gmailId: String) {
        try {
            File(dir(accountId), "$gmailId.json").delete()
            File(dir(accountId), "$gmailId.html").delete()
        } catch (_: Exception) {}
    }

    fun loadAll(accountId: Long): List<Stored> =
        dir(accountId).listFiles { f -> f.name.endsWith(".json") }.orEmpty().mapNotNull { f ->
            try {
                val o = JSONObject(f.readText())
                val s = fromJson(o)
                if (o.has("html") && !o.isNull("html")) {
                    // older format: move the HTML out of the always-loaded file
                    writeHtml(accountId, s.gmailId, o.getString("html"), inlineFromJson(o.optJSONArray("inline")))
                    write(accountId, s.gmailId, s.threadId, s.item)
                }
                s
            } catch (_: Exception) {
                null
            }
        }

    private fun strList(a: JSONArray?): List<String> = a?.let { (0 until it.length()).map { i -> it.getString(i) } } ?: emptyList()
    private fun optStr(o: JSONObject, k: String): String? = if (o.has(k) && !o.isNull(k)) o.getString(k) else null

    private fun inlineToJson(inline: List<InlinePart>) = JSONArray().also { arr ->
        inline.forEach { p -> arr.put(JSONObject().put("cid", p.cid).put("mime", p.mime).put("data", p.data).put("attId", p.attachmentId)) }
    }

    private fun inlineFromJson(arr: JSONArray?): List<InlinePart> = arr?.let {
        (0 until it.length()).map { i ->
            val p = it.getJSONObject(i)
            InlinePart(p.getString("cid"), p.getString("mime"), optStr(p, "data"), optStr(p, "attId"))
        }
    } ?: emptyList()

    private fun toJson(gmailId: String, threadId: String, item: MailItem): JSONObject {
        val e = item.email
        return JSONObject()
            .put("gid", gmailId).put("tid", threadId)
            .put("localId", e.localId).put("accountId", e.accountId)
            .put("messageId", e.messageId).put("inReplyTo", e.inReplyTo)
            .put("references", JSONArray(e.references))
            .put("subject", e.subject).put("sentAt", e.sentAtMillis)
            .put("accountAddress", e.accountAddress).put("from", e.from)
            .put("to", JSONArray(e.to)).put("cc", JSONArray(e.cc))
            .put("isRead", e.isRead)
            .put("body", item.body.take(com.foldmail.app.MAX_BODY_IN_MEMORY)).put("rich", item.rich)
            .put("attachments", JSONArray().also { arr ->
                item.attachments.forEach { a ->
                    arr.put(JSONObject().put("name", a.name).put("uri", a.uri).put("mime", a.mimeType)
                        .put("size", a.sizeBytes).put("remoteId", a.remoteId))
                }
            })
    }

    private fun fromJson(o: JSONObject): Stored {
        val email = EmailMessage(
            localId = o.getLong("localId"), accountId = o.getLong("accountId"),
            messageId = optStr(o, "messageId"), inReplyTo = optStr(o, "inReplyTo"),
            references = strList(o.optJSONArray("references")),
            subject = o.getString("subject"), sentAtMillis = o.getLong("sentAt"),
            accountAddress = o.optString("accountAddress"), from = o.optString("from"),
            to = strList(o.optJSONArray("to")), cc = strList(o.optJSONArray("cc")),
            isRead = o.optBoolean("isRead", true),
        )
        val attachments = o.optJSONArray("attachments")?.let { arr ->
            (0 until arr.length()).map { i ->
                val a = arr.getJSONObject(i)
                Attachment(
                    a.getString("name"), optStr(a, "uri"), a.optString("mime", "application/octet-stream"),
                    if (a.has("size") && !a.isNull("size")) a.getLong("size") else null, optStr(a, "remoteId"),
                )
            }
        } ?: emptyList()
        val rich = if (o.has("rich")) o.getBoolean("rich") else isRichHtml(optStr(o, "html"))
        val body = o.optString("body").take(com.foldmail.app.MAX_BODY_IN_MEMORY)
        return Stored(o.getString("gid"), o.optString("tid"), MailItem(email, body, attachments, null, emptyList(), rich))
    }
}
