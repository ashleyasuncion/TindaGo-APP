package com.example.tindago.data.notifications

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import androidx.work.*
import com.example.tindago.data.local.AppDatabase
import com.example.tindago.data.local.entity.SmsLogEntity
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker that sends automated SMS debt reminders.
 *
 * Runs once daily (configurable). For each active debt with a phone number
 * and SMS opt-in, it checks the schedule and sends a reminder if due.
 *
 * Uses SmsManager (device-based SMS) — requires SEND_SMS permission and
 * the owner's SIM with load/promo. Only works on sideloaded APKs (Google
 * Play blocks SEND_SMS for non-default-SMS-handler apps).
 */
class SmsWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val WORK_NAME = "tindago_sms_reminder"
        const val WORK_NAME_PERIODIC = "tindago_sms_reminder_periodic"

        /**
         * Schedule or cancel the SMS worker based on the master toggle.
         */
        fun schedule(context: Context, enabled: Boolean, intervalDays: Int = 7) {
            val workManager = WorkManager.getInstance(context)
            if (enabled) {
                val request = PeriodicWorkRequestBuilder<SmsWorker>(
                    intervalDays.toLong(), TimeUnit.DAYS
                )
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiresBatteryNotLow(true)
                            .build()
                    )
                    .setBackoffCriteria(
                        BackoffPolicy.EXPONENTIAL,
                        10, TimeUnit.MINUTES
                    )
                    .build()
                workManager.enqueueUniquePeriodicWork(
                    WORK_NAME_PERIODIC,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    request
                )
            } else {
                workManager.cancelUniqueWork(WORK_NAME_PERIODIC)
            }
        }
    }

    override suspend fun doWork(): Result {
        // Check SEND_SMS permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ContextCompat.checkSelfPermission(
                    applicationContext,
                    Manifest.permission.SEND_SMS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return Result.failure()
            }
        }

        val db = AppDatabase.getInstance(applicationContext)
        val debts = db.customerDebtDao().getAllDebts().first()
        val activeDebts = debts.filter { it.remainingBalance > 0 && it.phoneNumber.isNotBlank() }

        if (activeDebts.isEmpty()) return Result.success()

        // Load settings
        val prefs = applicationContext.getSharedPreferences("tindago_prefs", Context.MODE_PRIVATE)
        val storeName = prefs.getString("store_name", "My Store") ?: "My Store"
        val reminderDays = prefs.getInt("sms_reminder_days", 7)
        val quietStart = prefs.getInt("sms_quiet_start", 8)
        val quietEnd = prefs.getInt("sms_quiet_end", 20)
        val smsLogDao = db.smsLogDao()

        val now = System.currentTimeMillis()
        val currentHour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)

        // Check quiet hours
        if (currentHour < quietStart || currentHour >= quietEnd) {
            return Result.success() // Skip during quiet hours
        }

        var sentCount = 0

        for (debt in activeDebts) {
            // Check if we already sent a reminder recently
            val lastReminder = smsLogDao.getLatestLogByDebtAndType(debt.id, "reminder")
            if (lastReminder != null) {
                val daysSinceLastReminder = (now - lastReminder.timestamp) / (1000L * 60 * 60 * 24)
                if (daysSinceLastReminder < reminderDays) continue
            }

            // Group all debts for this customer
            val customerDebts = activeDebts.filter {
                it.customerName.equals(debt.customerName, ignoreCase = true)
            }
            val totalBalance = customerDebts.sumOf { it.remainingBalance }
            val oldestDebt = customerDebts.minByOrNull { it.createdAt } ?: debt
            val daysSinceCreation = ((now - oldestDebt.createdAt) / (1000L * 60 * 60 * 24)).toInt()
            val totalPaid = customerDebts.sumOf { it.amount - it.remainingBalance }

            // Build message
            val msg = com.example.tindago.data.SmsHelper.buildReminderMessage(
                customerName = debt.customerName,
                storeName = storeName,
                totalBalance = totalBalance,
                daysOpen = daysSinceCreation,
                totalPaid = totalPaid,
                effectiveCreditLimit = 0, // Will be computed from settings
                remainingCredit = 0.0,
                lang = prefs.getString("language", "fil") ?: "fil"
            )

            // Send SMS
            val success = sendSms(debt.phoneNumber, msg)

            // Log the attempt
            smsLogDao.insertLog(
                SmsLogEntity(
                    debtId = debt.id,
                    customerName = debt.customerName,
                    phoneNumber = debt.phoneNumber,
                    type = "reminder",
                    messageBody = msg,
                    status = if (success) "sent" else "failed",
                    timestamp = now
                )
            )

            if (success) sentCount++

            // Small delay between sends to avoid overwhelming the SIM
            if (sentCount > 0) {
                kotlinx.coroutines.delay(1000)
            }
        }

        return Result.success()
    }

    /**
     * Send an SMS using the device's SmsManager.
     */
    private fun sendSms(phoneNumber: String, message: String): Boolean {
        return try {
            val normalized = com.example.tindago.data.SmsHelper.normalizePhoneNumber(phoneNumber)
                ?: return false

            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                applicationContext.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }

            // Split message if longer than 160 chars
            val parts = smsManager.divideMessage(message)
            if (parts.size > 1) {
                smsManager.sendMultipartTextMessage(normalized, null, parts, null, null)
            } else {
                smsManager.sendTextMessage(normalized, null, message, null, null)
            }
            true
        } catch (e: Exception) {
            android.util.Log.e("SmsWorker", "SMS send failed: ${e.message}")
            false
        }
    }
}
