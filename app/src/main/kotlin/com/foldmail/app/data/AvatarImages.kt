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

    /** null = the server has nothing (404 etc.); throws on network trouble so that it is not remembered as "missing". */
    private fun download(url: String): ByteArray? {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 7000; readTimeout = 9000; instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
            setRequestProperty("Accept", "image/*,*/*;q=0.8")
        }
        return try {
            if (c.responseCode == 200) c.inputStream.use { it.readBytes() }.takeIf { it.size in 100..600_000 } else null
        } finally { c.disconnect() }
    }

    /** "mail.shop.example.co.uk" -> "example.co.uk" (rough, enough for logos). */
    private fun rootDomain(d: String): String {
        val p = d.split('.')
        if (p.size <= 2) return d
        val twoPart = setOf("co", "com", "org", "net", "gov", "ac")
        return if (p[p.size - 2] in twoPart && p.last().length == 2) p.takeLast(3).joinToString(".") else p.takeLast(2).joinToString(".")
    }

    /** Bumped whenever an own photo changes so that avatars reload. */
    val version = kotlinx.coroutines.flow.MutableStateFlow(0)

    private fun customFile(context: Context, addr: String) = File(context.filesDir, "photo_" + md5(addr.trim().lowercase()) + ".jpg")

    fun hasCustom(context: Context, addr: String) = customFile(context, addr).exists()

    /** Own picture for an account address: square crop, 192 px. */
    fun saveCustom(context: Context, addr: String, uri: android.net.Uri): Boolean = try {
        val bmp = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        if (bmp == null) false else {
            val side = minOf(bmp.width, bmp.height)
            val sq = Bitmap.createBitmap(bmp, (bmp.width - side) / 2, (bmp.height - side) / 2, side, side)
            val out = java.io.ByteArrayOutputStream()
            Bitmap.createScaledBitmap(sq, 192, 192, true).compress(Bitmap.CompressFormat.JPEG, 90, out)
            customFile(context, addr).writeBytes(out.toByteArray())
            memory.remove(addr.trim().lowercase()); version.value++
            true
        }
    } catch (_: Exception) { false }

    fun removeCustom(context: Context, addr: String) {
        customFile(context, addr).delete(); memory.remove(addr.trim().lowercase()); version.value++
    }

    suspend fun load(context: Context, address: String, allowNetwork: Boolean = true): Bitmap? {
        val addr = address.trim().lowercase()
        if (!addr.contains('@')) return null
        customFile(context, addr).takeIf { it.exists() }?.let { f -> BitmapFactory.decodeFile(f.path)?.let { return it } }
        if (!allowNetwork) return null
        memory.get(addr)?.let { return it }
        if (synchronized(missing) { addr in missing }) return null
        return withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "avatars2").apply { mkdirs() }
            val file = File(dir, md5(addr))
            val none = File(dir, md5(addr) + ".none")
            if (file.exists()) BitmapFactory.decodeFile(file.path)?.let { memory.put(addr, it); return@withContext it }
            if (none.exists() && System.currentTimeMillis() - none.lastModified() < RETRY_MS) { synchronized(missing) { missing.add(addr) }; return@withContext null }
            val domain = addr.substringAfter('@')
            var networkTrouble = false
            fun attempt(url: String): ByteArray? = try { download(url) } catch (_: Exception) { networkTrouble = true; null }
            var bytes = attempt("https://www.gravatar.com/avatar/${md5(addr)}?s=96&d=404")
            if (bytes == null && domain !in freeMail) {
                val root = rootDomain(domain)
                val urls = buildList {
                    add("https://t1.gstatic.com/faviconV2?client=SOCIAL&type=FAVICON&fallback_opts=TYPE,SIZE,URL&url=https://$domain&size=128")
                    if (root != domain) add("https://t1.gstatic.com/faviconV2?client=SOCIAL&type=FAVICON&fallback_opts=TYPE,SIZE,URL&url=https://$root&size=128")
                    add("https://$root/apple-touch-icon.png")
                    add("https://www.$root/favicon.ico")
                    add("https://$root/favicon.ico")
                }
                for (u in urls) {
                    val got = attempt(u) ?: continue
                    // keep only something that really decodes into a usable picture
                    val o = BitmapFactory.decodeByteArray(got, 0, got.size)
                    if (o != null && o.width >= 16) { bytes = got; break }
                }
            }
            val bmp = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            if (bmp != null) { file.writeBytes(bytes!!); memory.put(addr, bmp) }
            else if (!networkTrouble) { none.writeText("1"); synchronized(missing) { missing.add(addr) } }
            bmp
        }
    }
}
