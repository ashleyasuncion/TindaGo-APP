package com.example.tindago.data.backup

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.tindago.ui.localization.AppSettings
import java.util.concurrent.TimeUnit

/**
 * Schedules automatic backups (V3.0).
 *
 * - [schedule] registers (or cancels) the weekly/daily periodic worker.
 * - [maybeRunIfDue] enqueues a one-shot catch-up when the app is opened after
 *   the interval elapsed — important on OEMs (Xiaomi/Huawei/Oppo/Vivo) whose
 *   battery managers can delay periodic work for days.
 *
 * Both use unique work names so repeated calls never stack up duplicates.
 */
object BackupScheduler {

    const val WORK_PERIODIC = "tindago_auto_backup"
    const val WORK_CATCH_UP = "tindago_backup_catch_up"

    /** (Re)schedule or cancel the periodic backup based on current settings. */
    fun schedule(context: Context, settings: AppSettings) {
        val workManager = WorkManager.getInstance(context)
        val intervalHours = settings.backupIntervalHours
        if (!settings.backupEnabled || intervalHours <= 0) {
            workManager.cancelUniqueWork(WORK_PERIODIC)
            return
        }
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .setRequiresStorageNotLow(true)
            .build()
        val request = PeriodicWorkRequestBuilder<BackupWorker>(
            intervalHours.toLong(), TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
            .addTag(WORK_PERIODIC)
            .build()
        workManager.enqueueUniquePeriodicWork(
            WORK_PERIODIC,
            // UPDATE keeps the existing schedule but applies new interval/constraints.
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_PERIODIC)
    }

    /**
     * If an automatic backup is due (interval elapsed since the last one),
     * enqueue a single catch-up run. KEEP ensures at most one is pending, so
     * repeatedly opening the app does not queue multiple backups.
     */
    fun maybeRunIfDue(context: Context, settings: AppSettings) {
        if (!settings.backupEnabled || settings.backupIntervalHours <= 0) return
        val intervalMs = settings.backupIntervalHours * 60L * 60L * 1000L
        val dueAt = settings.lastBackupAt + intervalMs
        if (System.currentTimeMillis() < dueAt) return
        val request = OneTimeWorkRequestBuilder<BackupWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .setRequiresStorageNotLow(true)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
            .addTag(WORK_CATCH_UP)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(WORK_CATCH_UP, ExistingWorkPolicy.KEEP, request)
    }
}
