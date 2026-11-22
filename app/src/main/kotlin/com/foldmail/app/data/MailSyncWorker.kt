package com.foldmail.app.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Checks Gmail in the background (about every 15 minutes) so new mail can be announced. */
class MailSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val gmail = GmailRepository.get(applicationContext)
        val imap = ImapRepository.get(applicationContext)
        if (gmail.accounts.value.isNotEmpty()) try { gmail.syncNow() } catch (_: Exception) { /* next run tries again */ }
        if (imap.accounts.value.isNotEmpty()) try { imap.syncNow() } catch (_: Exception) { }
        return Result.success()
    }

    companion object {
        private const val NAME = "mail-sync"

        fun schedule(context: Context, enabled: Boolean, minutes: Int = AppSettings.get(context).syncMinutes.value) {
            val wm = WorkManager.getInstance(context)
            if (!enabled) { wm.cancelUniqueWork(NAME); return }
            val req = PeriodicWorkRequestBuilder<MailSyncWorker>(minutes.coerceAtLeast(15).toLong(), TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
        }
    }
}
