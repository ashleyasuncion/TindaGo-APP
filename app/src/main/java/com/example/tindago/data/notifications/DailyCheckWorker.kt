package com.example.tindago.data.notifications

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.tindago.TindaGoApp
import com.example.tindago.data.AppRepository
import com.example.tindago.ui.localization.AppSettings
import com.example.tindago.ui.localization.t
import kotlinx.coroutines.flow.first
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Daily notification worker (V2.70 — analysis §5.1.3 / FR-1, NFR-2/5).
 * Runs the pure rules (NotificationRules), applies cooldowns (NotificationThrottle),
 * suppresses while the app is foreground (AppForegroundTracker), and posts via
 * NotificationCenter. Scheduled TWICE a day:
 *   - morning (~06:30): overdue store, out-of-stock + restock (grouped), weekly digest
 *   - evening (~18:00): closing reminder (only after the configured hour)
 * Always returns success — a failed check must never wedge the scheduler.
 *
 * The actual check logic lives in [runNotificationCheck] (top-level) so the
 * Developer Panel can invoke it on demand for testing (V2.73).
 */
class DailyCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        const val WORK_MORNING = "daily_notification_check"
        const val WORK_CLOSING = "closing_reminder_check"
        const val KEY_CHECK = "check"
        const val CHECK_MORNING = "morning"
        const val CHECK_CLOSING = "closing"
    }

    override suspend fun doWork(): Result {
        val app = applicationContext as TindaGoApp
        val check = inputData.getString(KEY_CHECK) ?: CHECK_MORNING
        // force = false: keep the scheduled worker's foreground suppression +
        // master-toggle gate (a scheduled run must never interrupt a sale).
        runNotificationCheck(app, check, force = false)
        return Result.success()
    }
}

/**
 * Result of a notification check run (V2.73). `posted` counts notifications
 * actually handed to the system; `masterToggleOff` / `noPermission` explain
 * why nothing posted so the Dev Panel can give an honest, actionable message.
 */
data class NotificationCheckResult(
    val posted: Int = 0,
    val masterToggleOff: Boolean = false,
    val noPermission: Boolean = false
)

/**
 * Core notification check (V2.73 — extracted so the Dev Panel can run it on
 * demand with force = true, bypassing foreground suppression). Returns what
 * happened this run. Always returns normally; a failed check must never crash
 * the caller (worker or dev panel).
 */
suspend fun runNotificationCheck(app: TindaGoApp, check: String, force: Boolean): NotificationCheckResult {
    val settings = AppSettings(app)
    if (!settings.notificationsEnabled) {
        return NotificationCheckResult(masterToggleOff = true)
    }
    if (!NotificationCenter.canPost(app)) {
        return NotificationCheckResult(noPermission = true)
    }
    // Foreground suppression: the app is visible, so in-app badges/banners
    // already cover these states (never interrupt a sale). force = true is used
    // ONLY by the Developer Panel test trigger.
    if (!force && AppForegroundTracker.isForeground) return NotificationCheckResult()

    val lang = settings.language
    val today = todayStr()
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    var posted = 0

    val repo = AppRepository(
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
    val products = repo.getAllProducts().first()
    val debts = repo.getAllDebts().first()
    val eod = repo.getLatestEndOfDayData().first()
    val restockLog = repo.getLatestRestockLog().first()

    val dayOpen = settings.dayOpen
    val dayDate = settings.dayDate
    val isEodComplete = eod?.finished == true
    val daysSinceRestock = restockLog?.date?.let { daysBetween(it, today) } ?: -1

    val lastNotified = NotificationThrottle.parseNotifiedKeys(settings.notifiedKeys)
    val todayDay = NotificationThrottle.todayEpochDay()
    var updated = lastNotified

    if (check == DailyCheckWorker.CHECK_CLOSING) {
        // ── Evening closing reminder ──
        if (settings.notifyClosing &&
            hour >= settings.closingReminderHour &&
            NotificationRules.dueClosing(dayOpen, isEodComplete)
        ) {
            val key = "closing:$today"
            if (NotificationThrottle.shouldNotify(lastNotified, key, 1, todayDay)) {
                if (NotificationCenter.post(
                        app, NotificationChannels.CLOSING, NotificationCenter.ID_CLOSING,
                        "notifClosingTitle".t(lang),
                        "notifClosingText".t(lang),
                        NotificationDeepLinks.CLOSING
                    )
                ) {
                    posted++
                    updated = NotificationThrottle.markNotified(updated, key, todayDay)
                }
            }
        }
    } else {
        // ── Morning checks ──
        val quiet = !NotificationRules.quietHoursAllowed(hour)

        // 1) Overdue store (HIGH channel) — once per calendar day until resolved
        if (!quiet && settings.notifyOverdue && NotificationRules.dueOverdue(dayOpen, dayDate, today)) {
            val key = "overdue:$today"
            if (NotificationThrottle.shouldNotify(lastNotified, key, 1, todayDay)) {
                if (NotificationCenter.post(
                        app, NotificationChannels.OVERDUE, NotificationCenter.ID_OVERDUE,
                        "notifOverdueTitle".t(lang),
                        "notifOverdueText".t(lang),
                        NotificationDeepLinks.MORNING
                    )
                ) {
                    posted++
                    updated = NotificationThrottle.markNotified(updated, key, todayDay)
                }
            }
        }

        // 2) Stock group — out-of-stock items (once per item per day) + restock
        if (!quiet && settings.notifyStock) {
            val outItems = NotificationRules.outOfStockItems(products)
            val dueItems = outItems.filter {
                NotificationThrottle.shouldNotify(lastNotified, "stock:${it.id}:$today", 1, todayDay)
            }
            val restockDueNow = NotificationRules.dueRestock(daysSinceRestock) &&
                NotificationThrottle.shouldNotify(lastNotified, "restock:$today", 1, todayDay)

            if (dueItems.isNotEmpty() || restockDueNow) {
                val lines = mutableListOf<String>()
                val names = dueItems.map { it.name }
                names.take(5).forEach { lines.add(it) }
                if (names.size > 5) lines.add("+${names.size - 5} more")
                if (restockDueNow) lines.add(0, "notifRestockLine".t(lang).replace("{n}", daysSinceRestock.toString()))

                val onlyRestock = dueItems.isEmpty()
                val title = if (onlyRestock) {
                    "notifRestockTitle".t(lang)
                } else if (dueItems.size == 1) {
                    "notifStockOneTitle".t(lang)
                } else {
                    "notifStockTitle".t(lang).replace("{n}", dueItems.size.toString())
                }
                val text = if (onlyRestock) {
                    "notifRestockText".t(lang)
                } else {
                    "notifStockText".t(lang)
                }
                if (NotificationCenter.post(
                        app, NotificationChannels.STOCK, NotificationCenter.ID_STOCK,
                        title, text, NotificationDeepLinks.INVENTORY,
                        lines = lines, groupKey = "stock_group", groupSummary = true, onlyAlertOnce = true
                    )
                ) {
                    posted++
                    dueItems.forEach { updated = NotificationThrottle.markNotified(updated, "stock:${it.id}:$today", todayDay) }
                }
                if (restockDueNow) updated = NotificationThrottle.markNotified(updated, "restock:$today", todayDay)
            }
        }

        // 3) Weekly digest (opt-in) — once per Monday-anchored week
        if (settings.notifyDigest) {
            val key = "digest:${NotificationThrottle.digestWeekKey(today)}"
            if (NotificationThrottle.shouldNotify(lastNotified, key, 7, todayDay)) {
                val summary = NotificationRules.digestSummary(debts, settings.defaultCreditLimit)
                if (summary.outstandingTotal > 0) {
                    val peso = { v: Double -> "₱" + String.format(Locale.US, "%,.2f", v) }
                    if (NotificationCenter.post(
                            app, NotificationChannels.DIGEST, NotificationCenter.ID_DIGEST,
                            "notifDigestTitle".t(lang),
                            "notifDigestText".t(lang),
                            NotificationDeepLinks.DEBTS,
                            lines = listOf(
                                "notifDigestOutstanding".t(lang).replace("{amount}", peso(summary.outstandingTotal)),
                                "notifDigestOverLimit".t(lang).replace("{n}", summary.overLimitCount.toString()),
                                "notifDigestAging".t(lang).replace("{n}", summary.aging60PlusCount.toString())
                            ),
                            groupKey = "digest_group", groupSummary = true, onlyAlertOnce = true
                        )
                    ) {
                        posted++
                        updated = NotificationThrottle.markNotified(updated, key, todayDay)
                    }
                }
            }
        }
    }

    if (updated != lastNotified) {
        settings.notifiedKeys = NotificationThrottle.serializeNotifiedKeys(updated)
    }
    return NotificationCheckResult(posted = posted)
}

private fun todayStr(): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(java.util.Date())

private fun daysBetween(from: String, to: String): Int = try {
    val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    val a = fmt.parse(from) ?: return -1
    val b = fmt.parse(to) ?: return -1
    ((b.time - a.time) / (1000L * 60 * 60 * 24)).toInt()
} catch (_: Exception) { -1 }


