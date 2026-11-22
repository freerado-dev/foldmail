package com.foldmail.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** The user's signature: text plus an optional small photo that goes out as an inline image in an HTML alternative. */
object Signature {
    class Html(val html: String, val photo: ByteArray)

    private val pending = ConcurrentHashMap<String, Html>()

    private fun prefs(context: Context) = context.getSharedPreferences("signatures", Context.MODE_PRIVATE)

    fun enabled(context: Context, accountId: Long) = prefs(context).getBoolean("on_$accountId", false)
    fun text(context: Context, accountId: Long) = prefs(context).getString("text_$accountId", "") ?: ""
    fun onReplies(context: Context, accountId: Long) = prefs(context).getBoolean("replies_$accountId", true)
    fun setEnabled(context: Context, accountId: Long, v: Boolean) = prefs(context).edit().putBoolean("on_$accountId", v).apply()
    fun setText(context: Context, accountId: Long, v: String) = prefs(context).edit().putString("text_$accountId", v).apply()
    fun setOnReplies(context: Context, accountId: Long, v: Boolean) = prefs(context).edit().putBoolean("replies_$accountId", v).apply()

    fun photoFile(context: Context, accountId: Long) = File(context.filesDir, "signature_$accountId.jpg")

    fun hasPhoto(context: Context, accountId: Long) = photoFile(context, accountId).exists()

    /** Saves the picked picture scaled down to at most 240 px (keeps the aspect ratio). */
    fun savePhoto(context: Context, accountId: Long, uri: Uri): Boolean = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 480 && bounds.outHeight / (sample * 2) >= 480) sample *= 2
        val bmp = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }
        if (bmp == null) false else {
            val k = 240f / maxOf(bmp.width, bmp.height)
            val scaled = if (k < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt().coerceAtLeast(1), (bmp.height * k).toInt().coerceAtLeast(1), true) else bmp
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 88, out)
            photoFile(context, accountId).writeBytes(out.toByteArray())
            true
        }
    } catch (_: Exception) { false }

    fun removePhoto(context: Context, accountId: Long) { photoFile(context, accountId).delete() }

    /** Body with the plain-text signature added; remembers the HTML variant (with the photo) for the sender. */
    fun apply(context: Context, accountId: Long, body: String, isReply: Boolean): String {
        if (!enabled(context, accountId) || (isReply && !onReplies(context, accountId))) return body
        val text = text(context, accountId).trim()
        val photo = if (hasPhoto(context, accountId)) photoFile(context, accountId).readBytes() else null
        if (text.isEmpty() && photo == null) return body
        val full = if (text.isEmpty()) body else body.trimEnd() + "\n\n-- \n" + text
        if (photo != null) {
            fun esc(t: String) = t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            val html = "<div style=\"white-space:pre-wrap\">" + esc(body.trimEnd()) + "</div><br>-- <br>" +
                "<img src=\"cid:sigphoto\" style=\"max-width:120px;height:auto\" alt=\"\"><br>" +
                "<div style=\"white-space:pre-wrap\">" + esc(text) + "</div>"
            pending[full] = Html(html, photo)
        }
        return full
    }

    /** HTML variant prepared by [apply] for exactly this body, if any. */
    fun htmlFor(body: String): Html? = pending.remove(body)
}
