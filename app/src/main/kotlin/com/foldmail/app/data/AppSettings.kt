package com.foldmail.app.data

import android.content.Context
import com.foldmail.app.R
import kotlinx.coroutines.flow.MutableStateFlow

/** One of the bundled notification sounds. */
class NotificationSound(val key: String, val label: String, val rawRes: Int)

val NotificationSounds = listOf(
    NotificationSound("zvonec", "Jemný zvonček", R.raw.snd_zvonec),
    NotificationSound("kristal", "Krištáľ", R.raw.snd_kristal),
    NotificationSound("kvapka", "Kvapka", R.raw.snd_kvapka),
    NotificationSound("marimba", "Marimba", R.raw.snd_marimba),
    NotificationSound("harfa", "Harfa", R.raw.snd_harfa),
    NotificationSound("klavir", "Klavír", R.raw.snd_klavir),
    NotificationSound("zvonkohra", "Zvonkohra", R.raw.snd_zvonkohra),
    NotificationSound("perla", "Perla", R.raw.snd_perla),
    NotificationSound("bublina", "Bublina", R.raw.snd_bublina),
    NotificationSound("pipnutie", "Pípnutie", R.raw.snd_pipnutie),
)

fun soundByKey(key: String) = NotificationSounds.firstOrNull { it.key == key } ?: NotificationSounds.first()

/** User settings (theme, notifications), kept in SharedPreferences and observable. */
class AppSettings private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Light is the default. */
    val dark = MutableStateFlow(prefs.getBoolean("dark", false))
    val notifications = MutableStateFlow(prefs.getBoolean("notifications", true))
    val sound = MutableStateFlow(prefs.getString("sound", null) ?: NotificationSounds.first().key)

    fun setDark(v: Boolean) { prefs.edit().putBoolean("dark", v).apply(); dark.value = v }
    fun setNotifications(v: Boolean) { prefs.edit().putBoolean("notifications", v).apply(); notifications.value = v }
    fun setSound(key: String) { prefs.edit().putString("sound", key).apply(); sound.value = key }

    companion object {
        @Volatile private var instance: AppSettings? = null
        fun get(context: Context): AppSettings =
            instance ?: synchronized(this) { instance ?: AppSettings(context.applicationContext).also { instance = it } }
    }
}
