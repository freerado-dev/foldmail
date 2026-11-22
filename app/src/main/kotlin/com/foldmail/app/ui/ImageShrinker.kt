package com.foldmail.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Newsletters often embed photos several thousand pixels wide. Scrolling back over them makes the WebView
 * decode them again and again. Remote images are therefore downloaded once, scaled to screen size and cached.
 */
object ImageShrinker {
    private const val MAX_WIDTH = 1000
    private const val MAX_DOWNLOAD = 12 * 1024 * 1024

    private class Entry(val bytes: ByteArray, val mime: String)

    private val cache = object : LruCache<String, Entry>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Entry) = value.bytes.size
    }

    fun intercept(req: WebResourceRequest): WebResourceResponse? {
        val url = req.url.toString()
        if (req.method != "GET" || !(url.startsWith("https://") || url.startsWith("http://"))) return null
        val path = req.url.path?.lowercase().orEmpty()
        if (path.endsWith(".gif") || path.endsWith(".svg") || path.endsWith(".css") || path.endsWith(".js")) return null
        val accept = req.requestHeaders["Accept"].orEmpty()
        val looksLikeImage = accept.contains("image/") || path.endsWith(".jpg") || path.endsWith(".jpeg") ||
            path.endsWith(".png") || path.endsWith(".webp")
        if (!looksLikeImage) return null
        cache.get(url)?.let { return WebResourceResponse(it.mime, null, ByteArrayInputStream(it.bytes)) }
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000; readTimeout = 12_000; instanceFollowRedirects = true
                setRequestProperty("User-Agent", req.requestHeaders["User-Agent"] ?: "Mozilla/5.0")
            }
            val mime = conn.contentType?.substringBefore(';')?.trim().orEmpty()
            if (conn.responseCode != 200 || !mime.startsWith("image/") || mime.contains("gif") || mime.contains("svg")) {
                conn.disconnect(); return null
            }
            val bytes = conn.inputStream.use { it.readBytes() }
            conn.disconnect()
            if (bytes.size > MAX_DOWNLOAD) return null
            val entry = shrink(bytes, mime)
            cache.put(url, entry)
            WebResourceResponse(entry.mime, null, ByteArrayInputStream(entry.bytes))
        } catch (_: Throwable) {
            null
        }
    }

    /** Same scaling for images embedded in the mail itself (base64 data); returns (mime, base64). */
    fun shrinkBase64(mime: String, base64: String): Pair<String, String> {
        if (mime.contains("gif", true) || mime.contains("svg", true) || !mime.startsWith("image/", true)) return mime to base64
        return try {
            val e = shrink(java.util.Base64.getMimeDecoder().decode(base64), mime)
            e.mime to java.util.Base64.getEncoder().encodeToString(e.bytes)
        } catch (_: Throwable) {
            mime to base64
        }
    }

    private fun shrink(bytes: ByteArray, mime: String): Entry {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || (bounds.outWidth <= MAX_WIDTH + 100 && bytes.size < 400_000)) return Entry(bytes, mime)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= MAX_WIDTH) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return Entry(bytes, mime)
        if (bmp.width > MAX_WIDTH + 100) {
            val h = (bmp.height.toLong() * MAX_WIDTH / bmp.width).toInt().coerceAtLeast(1)
            bmp = Bitmap.createScaledBitmap(bmp, MAX_WIDTH, h, true)
        }
        val out = ByteArrayOutputStream()
        val alpha = bmp.hasAlpha()
        bmp.compress(if (alpha) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 82, out)
        return Entry(out.toByteArray(), if (alpha) "image/png" else "image/jpeg")
    }
}
