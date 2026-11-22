package com.foldmail.app.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.foldmail.app.R

/** A new incoming message to announce. */
class NewMail(val localId: Long, val sender: String, val subject: String, val preview: String)

object Notifier {
    private const val PREFIX = "mail_"
    private const val GROUP = "foldmail_new_mail"

    private fun soundUri(context: Context, s: NotificationSound): Uri =
        Uri.parse("android.resource://${context.packageName}/${s.rawRes}")

    /** Android fixes a channel's sound when it is created, so every sound gets its own channel. */
    private fun ensureChannel(context: Context, s: NotificationSound): String {
        val nm = context.getSystemService(NotificationManager::class.java)
        val id = PREFIX + s.key
        nm.notificationChannels.filter { it.id.startsWith(PREFIX) && it.id != id }.forEach { nm.deleteNotificationChannel(it.id) }
        if (nm.getNotificationChannel(id) == null) {
            val ch = NotificationChannel(id, "Nové emaily", NotificationManager.IMPORTANCE_HIGH)
            ch.setSound(
                soundUri(context, s),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
            )
            ch.enableVibration(true)
            nm.createNotificationChannel(ch)
        }
        return id
    }

    /** Announces unread incoming mail from the last three days. */
    fun announce(context: Context, fresh: List<com.foldmail.app.MailItem>) {
        val recent = System.currentTimeMillis() - 3L * 24 * 3600 * 1000
        val news = fresh.filter { !it.email.isOutgoing && !it.email.isRead && it.email.sentAtMillis > recent }
            .sortedBy { it.email.sentAtMillis }
            .map {
                val from = it.email.from
                val name = from.substringBefore('<').trim().trim('"').ifEmpty { com.foldmail.domain.model.normalizeAddress(from) }
                NewMail(it.email.localId, name, it.email.subject, com.foldmail.app.previewOf(it.body))
            }
        notifyNew(context, news)
    }

    fun hasPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun notifyNew(context: Context, mails: List<NewMail>) {
        if (mails.isEmpty()) return
        val settings = AppSettings.get(context)
        if (!settings.notifications.value || !hasPermission(context)) return
        val channel = ensureChannel(context, soundByKey(settings.sound.value))
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val tap = PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val nm = NotificationManagerCompat.from(context)
        try {
            mails.take(5).forEachIndexed { i, m ->
                val n = NotificationCompat.Builder(context, channel)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(m.sender)
                    .setContentText(if (settings.notificationPreview.value) m.subject else "Nový email")
                    .apply { if (settings.notificationPreview.value) setStyle(NotificationCompat.BigTextStyle().bigText(m.subject + "\n" + m.preview)) }
                    .setContentIntent(tap).setAutoCancel(true).setGroup(GROUP)
                    .setCategory(NotificationCompat.CATEGORY_EMAIL)
                    // only the first one rings, the rest are silent
                    .setOnlyAlertOnce(false).setSilent(i > 0)
                    .build()
                nm.notify(m.localId.hashCode(), n)
            }
            if (mails.size > 1) {
                val summary = NotificationCompat.Builder(context, channel)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle("FoldMail").setContentText("${mails.size} nových emailov")
                    .setContentIntent(tap).setAutoCancel(true).setGroup(GROUP).setGroupSummary(true).setSilent(true)
                    .build()
                nm.notify(GROUP.hashCode(), summary)
            }
        } catch (_: SecurityException) {
            // permission revoked in between
        }
    }

    /** Removes the notifications of these messages (deleted, archived or read in the app) and the summary when none are left. */
    fun cancel(context: Context, localIds: Collection<Long>) {
        if (localIds.isEmpty()) return
        val nm = NotificationManagerCompat.from(context)
        localIds.forEach { nm.cancel(it.hashCode()) }
        try {
            val sys = context.getSystemService(NotificationManager::class.java)
            val left = sys.activeNotifications.count { it.packageName == context.packageName && it.id != GROUP.hashCode() }
            if (left == 0) nm.cancel(GROUP.hashCode())
        } catch (_: Exception) {
        }
    }

    /** Plays a sound once (preview in the settings). */
    fun preview(context: Context, s: NotificationSound) {
        try {
            MediaPlayer.create(context, s.rawRes)?.apply {
                setOnCompletionListener { it.release() }
                start()
            }
        } catch (_: Exception) {
        }
    }
}
