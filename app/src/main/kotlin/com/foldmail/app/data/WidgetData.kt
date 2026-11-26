package com.foldmail.app.data

import android.content.Context
import com.foldmail.app.nameDirectory
import com.foldmail.app.previewOf
import com.foldmail.app.title
import com.foldmail.domain.threading.PersonConversation
import com.foldmail.domain.threading.PersonConversations
import org.json.JSONArray
import org.json.JSONObject

/** What the home-screen widgets show: the newest chats and the unread count, kept in a small preference file. */
object WidgetData {
    data class Row(val key: String, val name: String, val subject: String, val preview: String, val time: Long, val unread: Boolean, val address: String?)
    data class Snapshot(val unread: Int, val rows: List<Row>)

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("widget", Context.MODE_PRIVATE)

    fun read(context: Context): Snapshot {
        val raw = prefs(context).getString("snapshot", null) ?: return Snapshot(0, emptyList())
        return try {
            val o = JSONObject(raw)
            val arr = o.getJSONArray("rows")
            Snapshot(
                o.optInt("unread"),
                (0 until arr.length()).map {
                    val r = arr.getJSONObject(it)
                    Row(
                        r.getString("key"), r.optString("name"), r.optString("subject"), r.optString("preview"),
                        r.optLong("time"), r.optBoolean("unread"), r.optString("address").takeIf { a -> a.isNotEmpty() },
                    )
                },
            )
        } catch (_: Exception) { Snapshot(0, emptyList()) }
    }

    /** Stores the snapshot; returns true when it differs from the previous one (so widgets need a redraw). */
    fun save(context: Context, s: Snapshot): Boolean {
        val arr = JSONArray()
        s.rows.forEach {
            arr.put(JSONObject().put("key", it.key).put("name", it.name).put("subject", it.subject).put("preview", it.preview)
                .put("time", it.time).put("unread", it.unread).put("address", it.address ?: ""))
        }
        val raw = JSONObject().put("unread", s.unread).put("rows", arr).toString()
        val p = prefs(context)
        if (p.getString("snapshot", null) == raw) return false
        p.edit().putString("snapshot", raw).apply()
        return true
    }

    fun build(
        conversations: List<PersonConversation>, bodies: Map<Long, String>, names: Map<String, String>, self: String, unread: Int, limit: Int = 8,
    ): Snapshot = Snapshot(
        unread,
        conversations.take(limit).map { c ->
            Row(
                c.key, c.title(names, self), c.last.subject, previewOf(bodies[c.last.localId].orEmpty()).take(120), c.last.sentAtMillis,
                c.unreadCount > 0, c.participants.singleOrNull(),
            )
        },
    )

    /** Used after a background sync, when the app (and its view model) is not running. */
    fun refreshFromRepos(context: Context): Boolean {
        val items = GmailRepository.get(context).items.value + ImapRepository.get(context).items.value
        if (items.isEmpty()) return false
        val p = context.getSharedPreferences("foldmail_flags", Context.MODE_PRIVATE)
        val trashed = p.getStringSet("trashed", emptySet()).orEmpty().mapNotNull { it.toLongOrNull() }.toSet()
        val archived = p.getStringSet("archived", emptySet()).orEmpty().mapNotNull {
            val i = it.lastIndexOf('|'); if (i < 0) null else it.substring(0, i) to (it.substring(i + 1).toLongOrNull() ?: 0L)
        }.toMap()
        val emails = items.map { it.email }.filter { it.localId !in trashed }
        val all = PersonConversations.build(emails, mergeAccounts = true).filter { c ->
            val at = archived[c.key]; at == null || c.last.sentAtMillis > at
        }
        val bodies = items.associate { it.email.localId to it.body }
        val unread = emails.count { !it.isRead && !it.isOutgoing }
        return save(context, build(all, bodies, nameDirectory(emails), "", unread))
    }
}
