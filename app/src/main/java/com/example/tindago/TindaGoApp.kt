package com.example.tindago

import android.app.Application
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.tindago.data.backup.BackupScheduler
import com.example.tindago.data.local.AppDatabase
import com.example.tindago.data.notifications.DailyCheckWorker
import com.example.tindago.data.notifications.NotificationChannels
import com.example.tindago.data.notifications.SmsWorker
import com.example.tindago.ui.localization.AppSettings
import java.util.concurrent.TimeUnit

class TindaGoApp : Application() {

    /** Lazy-initialized Room database singleton */
    val database: AppDatabase by lazy {
        AppDatabase.getInstance(this)
    }

    override fun onCreate() {
        super.onCreate()
        // V2.70: notification channels (idempotent) + daily check scheduling.
        NotificationChannels.createAll(this)
        scheduleNotificationChecks()
        // SMS automated reminders scheduling
        scheduleSmsReminders()
        // V3.0: automatic backup — periodic WorkManager + OEM catch-up.
        scheduleAutomaticBackups()
    }

    /** Schedule the two periodic workers (inexact timing only — battery-friendly,
     *  Doze-safe; no exact alarms per the V2.70 plan NFR-6). */
    private fun scheduleNotificationChecks() {
        val workManager = WorkManager.getInstance(this)

        val morning = PeriodicWorkRequestBuilder<DailyCheckWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(6, TimeUnit.HOURS)   // first run ~06:30
            .setInputData(workDataOf(DailyCheckWorker.KEY_CHECK to DailyCheckWorker.CHECK_MORNING))
            .build()
        workManager.enqueueUniquePeriodicWork(
            DailyCheckWorker.WORK_MORNING, ExistingPeriodicWorkPolicy.KEEP, morning
        )

        val closing = PeriodicWorkRequestBuilder<DailyCheckWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(18, TimeUnit.HOURS)  // first run ~18:00
            .setInputData(workDataOf(DailyCheckWorker.KEY_CHECK to DailyCheckWorker.CHECK_CLOSING))
            .build()
        workManager.enqueueUniquePeriodicWork(
            DailyCheckWorker.WORK_CLOSING, ExistingPeriodicWorkPolicy.KEEP, closing
        )
    }

    /** Schedule automated SMS debt reminders based on settings. */
    private fun scheduleSmsReminders() {
        val prefs = getSharedPreferences("tindago_prefs", MODE_PRIVATE)
        val smsEnabled = prefs.getBoolean("sms_enabled", false)
        val reminderDays = prefs.getInt("sms_reminder_days", 7)
        SmsWorker.schedule(this, smsEnabled, reminderDays)
    }

    /** V3.0: Schedule periodic automatic backups and opportunistic catch-up for OEM delays. */
    private fun scheduleAutomaticBackups() {
        val settings = AppSettings(this)
        BackupScheduler.schedule(this, settings)
        BackupScheduler.maybeRunIfDue(this, settings)
    }

    /** Called when backup settings change (e.g. from SettingsScreen toggle) to re-apply schedule. */
    fun onBackupSettingsChanged() {
        scheduleAutomaticBackups()
    }
}
