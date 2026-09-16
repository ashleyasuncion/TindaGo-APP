package com.example.tindago.data.backup

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.tindago.TindaGoApp
import com.example.tindago.data.AppRepository
import com.example.tindago.data.notifications.NotificationCenter
import com.example.tindago.data.notifications.NotificationChannels
import com.example.tindago.data.notifications.NotificationDeepLinks
import com.example.tindago.ui.localization.AppSettings
import com.example.tindago.ui.localization.t

/**
 * Automatic backup worker (V3.0).
 *
 * Scheduled by [BackupScheduler] at the user's chosen frequency and also used
 * for the opportunistic on-app-open catch-up. Writes exactly one backup file
 * to the configured location, prunes old automatic backups, and records the
 * result in [AppSettings] so the Settings screen can show the last backup.
 *
 * On repeated failure it posts a single, actionable notification that deep-links
 * to Settings; a successful retry clears it. Transient failures retry with
 * exponential backoff (configured on the request).
 */
class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as TindaGoApp
        val settings = AppSettings(applicationContext)

        // Respect the master toggle even if a stale request survived a cancel.
        if (!settings.backupEnabled) return Result.success()

        val repository = AppRepository(
            productDao = app.database.productDao(),
            dailyEntryDao = app.database.dailyEntryDao(),
            specificSaleDao = app.database.specificSaleDao(),
            customerDebtDao = app.database.customerDebtDao(),
            endOfDayDao = app.database.endOfDayDao(),
            restockLogDao = app.database.restockLogDao(),
            debtPaymentDao = app.database.debtPaymentDao(),
            debtTransactionDao = app.database.debtTransactionDao(),
            expenseDao = app.database.expenseDao(),
            smsLogDao = app.database.smsLogDao()
        )

        val result = BackupManager.createBackup(
            context = applicationContext,
            repository = repository,
            settings = settings,
            manual = false
        )

        return when {
            result.success -> {
                // A recovered backup clears any earlier failure notification.
                NotificationCenter.cancel(applicationContext, NotificationCenter.ID_BACKUP)
                Result.success()
            }
            runAttemptCount < MAX_RETRIES -> Result.retry()
            else -> {
                notifyFailure(settings.language)
                Result.failure()
            }
        }
    }

    private fun notifyFailure(lang: String) {
        // In-app status is always recorded; the notification is best-effort
        // (silently skipped without POST_NOTIFICATIONS permission).
        NotificationCenter.post(
            context = applicationContext,
            channelId = NotificationChannels.BACKUP,
            notificationId = NotificationCenter.ID_BACKUP,
            title = "notifBackupFailedTitle".t(lang),
            text = "notifBackupFailedText".t(lang),
            deepLink = NotificationDeepLinks.SETTINGS
        )
    }

    companion object {
        /** Retries before a failure is treated as permanent and surfaced. */
        const val MAX_RETRIES = 2
    }
}
