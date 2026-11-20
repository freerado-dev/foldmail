package com.foldmail.app.data

import com.foldmail.app.Attachment
import com.foldmail.app.InlinePart
import com.foldmail.app.MailItem
import com.foldmail.domain.model.EmailMessage
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
import java.util.Base64

/** Thin Gmail REST client (blocking; call from Dispatchers.IO). */
object GmailApi {
    private const val BASE = "https://gmail.googleapis.com/gmail/v1/users/me"

    class ApiException(val code: Int, message: String) : IOException(message)

    /** Retries rate-limit (403/429) and server (5xx) errors with growing pauses. */
    private fun request(method: String, url: String, token: String, body: String? = null): String {
        var attempt = 0
        while (true) {
            try {
                return requestOnce(method, url, token, body)
            } catch (e: ApiException) {
                val limited = e.code == 429 || e.code >= 500 ||
                    (e.code == 403 && Regex("rate|quota", RegexOption.IGNORE_CASE).containsMatchIn(e.message.orEmpty()))
                if (!limited || ++attempt >= 6) throw e
                Thread.sleep(1000L * attempt * attempt)
            }
        }
    }

    private fun requestOnce(method: String, url: String, token: String, body: String?): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 15_000
            c.readTimeout = 45_000
            c.setRequestProperty("Authorization", "Bearer $token")
            c.setRequestProperty("Accept", "application/json")
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw ApiException(code, "Gmail API $code: ${text.take(300)}")
            return text
        } finally {
            c.disconnect()
        }
    }

    fun profileEmail(token: String): String = JSONObject(request("GET", "$BASE/profile", token)).getString("emailAddress")

    fun listIds(token: String, label: String, max: Int): List<String> {
        val json = JSONObject(request("GET", "$BASE/messages?labelIds=$label&maxResults=$max", token))
        val arr = json.optJSONArray("messages") ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it).getString("id") }
    }

    fun listIdsQuery(token: String, query: String, max: Int): List<String> {
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        val json = JSONObject(request("GET", "$BASE/messages?q=$q&maxResults=$max", token))
        val arr = json.optJSONArray("messages") ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it).getString("id") }
    }

    /** Raw bytes of an attachment. */
    fun attachment(token: String, messageId: String, attachmentId: String): ByteArray {
        val data = JSONObject(request("GET", "$BASE/messages/$messageId/attachments/$attachmentId", token)).getString("data")
        return Base64.getUrlDecoder().decode(data.trim())
    }

    fun modifyLabels(token: String, id: String, add: List<String>, remove: List<String>) {
        val body = JSONObject().put("addLabelIds", JSONArray(add)).put("removeLabelIds", JSONArray(remove))
        request("POST", "$BASE/messages/$id/modify", token, body.toString())
    }

    fun getMessage(token: String, id: String): JSONObject = JSONObject(request("GET", "$BASE/messages/$id?format=full", token))

    fun send(token: String, rawBase64Url: String, threadId: String?) {
        val body = JSONObject().put("raw", rawBase64Url)
        if (threadId != null) body.put("threadId", threadId)
        request("POST", "$BASE/messages/send", token, body.toString())
    }

    fun trash(token: String, id: String) {
        request("POST", "$BASE/messages/$id/trash", token, "")
    }

    fun markRead(token: String, id: String) {
        request("POST", "$BASE/messages/$id/modify", token, JSONObject().put("removeLabelIds", JSONArray().put("UNREAD")).toString())
    }
}

/** Result of parsing one Gmail message resource. */
data class ParsedMessage(val item: MailItem, val gmailId: String, val threadId: String)

object GmailParser {

    fun parse(json: JSONObject, accountId: Long, accountEmail: String): ParsedMessage {
        val id = json.getString("id")
        val payload = json.optJSONObject("payload") ?: JSONObject()
        val headers = HashMap<String, String>()
        payload.optJSONArray("headers")?.let { arr ->
            for (i in 0 until arr.length()) {
                val h = arr.getJSONObject(i)
                headers.putIfAbsent(h.getString("name").lowercase(), h.optString("value"))
            }
        }
        val labels = json.optJSONArray("labelIds")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
        val attachments = ArrayList<Attachment>()
        val plain = StringBuilder()
        val html = StringBuilder()
        val inline = ArrayList<InlinePart>()
        walk(payload, plain, html, attachments, inline)
        val body = plain.toString().ifBlank { htmlToText(html.toString()) }.trim()

        val localId = try { java.lang.Long.parseUnsignedLong(id, 16) } catch (_: NumberFormatException) { id.hashCode().toLong() }
        val email = EmailMessage(
            localId = localId,
            accountId = accountId,
            messageId = headers["message-id"]?.trim(),
            inReplyTo = headers["in-reply-to"]?.trim()?.ifEmpty { null },
            references = headers["references"].orEmpty().split(Regex("\\s+")).filter { it.isNotBlank() },
            subject = decodeHeader(headers["subject"].orEmpty()).ifBlank { "(bez predmetu)" },
            sentAtMillis = json.optString("internalDate").toLongOrNull() ?: 0L,
            accountAddress = accountEmail,
            from = decodeHeader(headers["from"].orEmpty()),
            to = splitAddresses(headers["to"].orEmpty()).map(::decodeHeader),
            cc = splitAddresses(headers["cc"].orEmpty()).map(::decodeHeader),
            isRead = "UNREAD" !in labels,
        )
        return ParsedMessage(MailItem(email, body, attachments, html.toString().ifBlank { null }, inline), id, json.optString("threadId"))
    }

    private fun walk(part: JSONObject, plain: StringBuilder, html: StringBuilder, attachments: MutableList<Attachment>, inline: MutableList<InlinePart>) {
        val mime = part.optString("mimeType")
        val filename = part.optString("filename")
        val body = part.optJSONObject("body")
        val cid = headerValue(part, "Content-ID")?.trim()?.trim('<', '>')
        if (!cid.isNullOrEmpty() && mime.startsWith("image/") && body != null) {
            val data = if (body.has("data")) body.getString("data").trim().replace('-', '+').replace('_', '/') else null
            val attId = body.optString("attachmentId").ifBlank { null }
            if (data != null || attId != null) inline += InlinePart(cid, mime, data, attId)
        }
        if (filename.isNotBlank()) {
            attachments += Attachment(
                decodeHeader(filename), null, mime.ifBlank { "application/octet-stream" }, body?.optLong("size"),
                remoteId = body?.optString("attachmentId")?.ifBlank { null },
            )
        } else if (body != null && body.has("data")) {
            val text = decodeBody(body.getString("data"), charsetOf(part))
            if (mime == "text/plain" && plain.isEmpty()) plain.append(text)
            else if (mime == "text/html" && html.isEmpty()) html.append(text)
        }
        part.optJSONArray("parts")?.let { arr -> for (i in 0 until arr.length()) walk(arr.getJSONObject(i), plain, html, attachments, inline) }
    }

    private fun headerValue(part: JSONObject, name: String): String? {
        val arr = part.optJSONArray("headers") ?: return null
        for (i in 0 until arr.length()) {
            val h = arr.getJSONObject(i)
            if (h.optString("name").equals(name, true)) return h.optString("value")
        }
        return null
    }

    private fun charsetOf(part: JSONObject): Charset {
        val arr = part.optJSONArray("headers") ?: return Charsets.UTF_8
        for (i in 0 until arr.length()) {
            val h = arr.getJSONObject(i)
            if (h.optString("name").equals("Content-Type", true)) {
                val m = Regex("charset=\"?([^\";\\s]+)", RegexOption.IGNORE_CASE).find(h.optString("value"))
                if (m != null) return try { Charset.forName(m.groupValues[1]) } catch (_: Exception) { Charsets.UTF_8 }
            }
        }
        return Charsets.UTF_8
    }

    private fun decodeBody(data: String, charset: Charset): String =
        String(Base64.getUrlDecoder().decode(data.trim()), charset)

    /** Splits "A <a@x>, \"B, C\" <b@x>" on top-level commas. */
    fun splitAddresses(raw: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var quoted = false
        var angle = false
        for (ch in raw) {
            when {
                ch == '"' -> { quoted = !quoted; cur.append(ch) }
                ch == '<' && !quoted -> { angle = true; cur.append(ch) }
                ch == '>' && !quoted -> { angle = false; cur.append(ch) }
                ch == ',' && !quoted && !angle -> { out += cur.toString().trim(); cur.clear() }
                else -> cur.append(ch)
            }
        }
        if (cur.isNotBlank()) out += cur.toString().trim()
        return out.filter { it.isNotEmpty() }
    }

    private val encodedWord = Regex("=\\?([^?]+)\\?([bBqQ])\\?([^?]*)\\?=")

    /** Decodes RFC 2047 encoded words ("=?UTF-8?B?...?="). */
    fun decodeHeader(value: String): String {
        if ("=?" !in value) return value
        val joined = value.replace(Regex("\\?=\\s+=\\?"), "?==?")
        return encodedWord.replace(joined) { m ->
            val cs = try { Charset.forName(m.groupValues[1]) } catch (_: Exception) { Charsets.UTF_8 }
            try {
                if (m.groupValues[2].equals("B", true)) String(Base64.getMimeDecoder().decode(m.groupValues[3]), cs)
                else decodeQ(m.groupValues[3], cs)
            } catch (_: Exception) {
                m.value
            }
        }
    }

    private fun decodeQ(s: String, cs: Charset): String {
        val bytes = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '_' -> bytes.write(' '.code)
                c == '=' && i + 2 <= s.length - 1 -> {
                    bytes.write(s.substring(i + 1, i + 3).toInt(16)); i += 2
                }
                else -> bytes.write(c.code)
            }
            i++
        }
        return String(bytes.toByteArray(), cs)
    }

    /** Very small HTML -> text conversion for mails that have no text/plain part. */
    fun htmlToText(html: String): String {
        if (html.isBlank()) return ""
        var t = html.replace(Regex("(?is)<(script|style|head)[^>]*>.*?</\\1>"), "")
        t = t.replace(Regex("(?i)<br\\s*/?>|</p>|</div>|</tr>|</h[1-6]>|</li>"), "\n")
        t = t.replace(Regex("(?s)<[^>]+>"), "")
        t = t.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")
        t = Regex("&#(\\d+);").replace(t) { it.groupValues[1].toIntOrNull()?.let { c -> String(Character.toChars(c)) } ?: it.value }
        return t.lines().map { it.trim() }.joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()
    }
}

/** Builds an RFC 5322 message and returns it base64url encoded, ready for messages.send. */
object MimeBuilder {
    class Part(val name: String, val mimeType: String, val bytes: ByteArray)

    fun build(
        from: String,
        to: List<String>,
        subject: String,
        body: String,
        inReplyTo: String?,
        references: List<String>,
        parts: List<Part>,
        boundary: String = "foldmail-" + java.util.UUID.randomUUID().toString().replace("-", ""),
    ): String {
        val mime = Base64.getMimeEncoder(76, "\r\n".toByteArray())
        val sb = StringBuilder()
        sb.append("From: ").append(from).append("\r\n")
        sb.append("To: ").append(to.joinToString(", ")).append("\r\n")
        sb.append("Subject: ").append(encodeWord(subject)).append("\r\n")
        if (!inReplyTo.isNullOrBlank()) sb.append("In-Reply-To: ").append(inReplyTo).append("\r\n")
        if (references.isNotEmpty()) sb.append("References: ").append(references.joinToString(" ")).append("\r\n")
        sb.append("MIME-Version: 1.0\r\n")
        val textPart = StringBuilder()
            .append("Content-Type: text/plain; charset=UTF-8\r\n")
            .append("Content-Transfer-Encoding: base64\r\n\r\n")
            .append(mime.encodeToString(body.toByteArray(Charsets.UTF_8))).append("\r\n")
        if (parts.isEmpty()) {
            sb.append(textPart)
        } else {
            sb.append("Content-Type: multipart/mixed; boundary=\"").append(boundary).append("\"\r\n\r\n")
            sb.append("--").append(boundary).append("\r\n").append(textPart)
            for (p in parts) {
                val n = encodeWord(p.name)
                sb.append("--").append(boundary).append("\r\n")
                sb.append("Content-Type: ").append(p.mimeType).append("; name=\"").append(n).append("\"\r\n")
                sb.append("Content-Disposition: attachment; filename=\"").append(n).append("\"\r\n")
                sb.append("Content-Transfer-Encoding: base64\r\n\r\n")
                sb.append(mime.encodeToString(p.bytes)).append("\r\n")
            }
            sb.append("--").append(boundary).append("--\r\n")
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(sb.toString().toByteArray(Charsets.UTF_8))
    }

    fun encodeWord(s: String): String =
        if (s.all { it.code in 32..126 }) s
        else "=?UTF-8?B?" + Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8)) + "?="
}
