package com.foldmail.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** An unfinished email kept on the device (attachments are not kept). */
data class Draft(
    val id: String, val to: String, val cc: String, val bcc: String, val subject: String, val body: String,
    val accountId: Long, val replyTo: String?, val updated: Long,
)

object DraftStore {
    val drafts = MutableStateFlow<List<Draft>>(emptyList())
    private var loaded = false

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("drafts", Context.MODE_PRIVATE)

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val raw = prefs(context).getString("list", null) ?: return
        drafts.value = try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Draft(
                    o.getString("id"), o.optString("to"), o.optString("cc"), o.optString("bcc"), o.optString("subject"), o.optString("body"),
                    o.optLong("account", -1L), o.optString("replyTo").takeIf { it.isNotEmpty() }, o.optLong("updated"),
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun persist(context: Context) {
        val arr = JSONArray()
        drafts.value.forEach {
            arr.put(JSONObject().put("id", it.id).put("to", it.to).put("cc", it.cc).put("bcc", it.bcc).put("subject", it.subject)
                .put("body", it.body).put("account", it.accountId).put("replyTo", it.replyTo ?: "").put("updated", it.updated))
        }
        prefs(context).edit().putString("list", arr.toString()).apply()
    }

    fun save(context: Context, d: Draft) {
        load(context)
        drafts.value = (listOf(d) + drafts.value.filter { it.id != d.id })
        persist(context)
    }

    fun delete(context: Context, id: String?) {
        if (id == null) return
        load(context)
        drafts.value = drafts.value.filter { it.id != id }
        persist(context)
    }
}
