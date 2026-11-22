package com.foldmail.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Sender pictures: Gravatar profile photo first, then the favicon of the sender's company domain. */
object AvatarImages {
    private val memory = LruCache<String, Bitmap>(120)
    private val missing = HashSet<String>()
    private const val RETRY_MS = 7L * 24 * 3600 * 1000

    private val freeMail = setOf(
        "gmail.com", "googlemail.com", "seznam.cz", "email.cz", "post.cz", "centrum.sk", "centrum.cz", "azet.sk", "zoznam.sk", "pobox.sk",
        "outlook.com", "hotmail.com", "live.com", "msn.com", "yahoo.com", "icloud.com", "me.com", "proton.me", "protonmail.com", "gmx.com", "gmx.at", "gmx.de", "atlas.cz", "post.sk",
    )

    private fun md5(s: String) = MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun download(url: String): ByteArray? = try {
        val c = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 6000; readTimeout = 8000; instanceFollowRedirects = true }
        if (c.responseCode == 200) c.inputStream.use { it.readBytes() }.takeIf { it.size in 200..400_000 } else null
    } catch (_: Exception) { null }

    suspend fun load(context: Context, address: String): Bitmap? {
        val addr = address.trim().lowercase()
        if (!addr.contains('@')) return null
        memory.get(addr)?.let { return it }
        if (synchronized(missing) { addr in missing }) return null
        return withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "avatars").apply { mkdirs() }
            val file = File(dir, md5(addr))
            val none = File(dir, md5(addr) + ".none")
            if (file.exists()) BitmapFactory.decodeFile(file.path)?.let { memory.put(addr, it); return@withContext it }
            if (none.exists() && System.currentTimeMillis() - none.lastModified() < RETRY_MS) { synchronized(missing) { missing.add(addr) }; return@withContext null }
            val domain = addr.substringAfter('@')
            var bytes = download("https://www.gravatar.com/avatar/${md5(addr)}?s=96&d=404")
            if (bytes == null && domain !in freeMail) {
                bytes = download("https://t1.gstatic.com/faviconV2?client=SOCIAL&type=FAVICON&url=http://$domain&size=128")
            }
            val bmp = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            if (bmp != null) { file.writeBytes(bytes!!); memory.put(addr, bmp) }
            else { none.writeText("1"); synchronized(missing) { missing.add(addr) } }
            bmp
        }
    }
}
