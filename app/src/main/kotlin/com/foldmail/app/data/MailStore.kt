package com.foldmail.app.data

import android.content.Context
import com.foldmail.app.Attachment
import com.foldmail.app.InlinePart
import com.foldmail.app.MailItem
import com.foldmail.domain.model.EmailMessage
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Keeps downloaded mail on the device: one small JSON file per message, so a sync only writes what changed. */
class MailStore(context: Context) {
    private val root = File(context.filesDir, "mail")

    class Stored(val gmailId: String, val threadId: String, val item: MailItem)

    private fun dir(accountId: Long) = File(root, accountId.toString()).apply { mkdirs() }

    fun write(accountId: Long, gmailId: String, threadId: String, item: MailItem) {
        try {
            val target = File(dir(accountId), "$gmailId.json")
            val tmp = File(dir(accountId), "$gmailId.tmp")
            tmp.writeText(toJson(gmailId, threadId, item).toString())
            tmp.renameTo(target)
        } catch (_: Exception) {
            // storage is a cache; ignore failures
        }
    }

    fun delete(accountId: Long, gmailId: String) {
        try { File(dir(accountId), "$gmailId.json").delete() } catch (_: Exception) {}
    }

    fun loadAll(accountId: Long): List<Stored> =
        dir(accountId).listFiles { f -> f.name.endsWith(".json") }.orEmpty().mapNotNull {
            try { fromJson(JSONObject(it.readText())) } catch (_: Exception) { null }
        }

    private fun strList(a: JSONArray?): List<String> = a?.let { (0 until it.length()).map { i -> it.getString(i) } } ?: emptyList()
    private fun optStr(o: JSONObject, k: String): String? = if (o.has(k) && !o.isNull(k)) o.getString(k) else null

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
            .put("body", item.body).put("html", item.html)
            .put("attachments", JSONArray().also { arr ->
                item.attachments.forEach { a ->
                    arr.put(JSONObject().put("name", a.name).put("uri", a.uri).put("mime", a.mimeType)
                        .put("size", a.sizeBytes).put("remoteId", a.remoteId))
                }
            })
            .put("inline", JSONArray().also { arr ->
                item.inline.forEach { p ->
                    arr.put(JSONObject().put("cid", p.cid).put("mime", p.mime).put("data", p.data).put("attId", p.attachmentId))
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
        val inline = o.optJSONArray("inline")?.let { arr ->
            (0 until arr.length()).map { i ->
                val p = arr.getJSONObject(i)
                InlinePart(p.getString("cid"), p.getString("mime"), optStr(p, "data"), optStr(p, "attId"))
            }
        } ?: emptyList()
        return Stored(o.getString("gid"), o.optString("tid"), MailItem(email, o.optString("body"), attachments, optStr(o, "html"), inline))
    }
}
