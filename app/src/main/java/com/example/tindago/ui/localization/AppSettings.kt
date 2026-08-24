package com.example.tindago.ui.localization

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf

val LocalLanguage = compositionLocalOf { mutableStateOf("en") }
val LocalTextScale = compositionLocalOf { mutableStateOf(1f) }

class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("tindago_prefs", Context.MODE_PRIVATE)

    var language: String
        get() = prefs.getString("language", "en") ?: "en"
        set(value) = prefs.edit().putString("language", value).apply()

    var textSize: String
        get() = prefs.getString("text_size", "standard") ?: "standard"
        set(value) = prefs.edit().putString("text_size", value).apply()

    var storeName: String
        get() = prefs.getString("store_name", "My Store") ?: "My Store"
        set(value) = prefs.edit().putString("store_name", value).apply()

    var ownerName: String
        get() = prefs.getString("owner_name", "Owner") ?: "Owner"
        set(value) = prefs.edit().putString("owner_name", value).apply()

    var hasCompletedSetup: Boolean
        get() = prefs.getBoolean("has_completed_setup", false)
        set(value) = prefs.edit().putBoolean("has_completed_setup", value).apply()

    var hasCompletedTutorial: Boolean
        get() = prefs.getBoolean("has_completed_tutorial", false)
        set(value) = prefs.edit().putBoolean("has_completed_tutorial", value).apply()

    /** Launch counter for tutorial — auto-starts on every launch, no skip on first-ever launch */
    var launchCount: Int
        get() = prefs.getInt("launch_count", 0)
        set(value) = prefs.edit().putInt("launch_count", value).apply()

    var defaultMarkup: Int
        get() = prefs.getInt("default_markup", 20)
        set(value) = prefs.edit().putInt("default_markup", value).apply()

    /** Global low-stock alert threshold — default for new products; per-product value overrides. */
    var lowStockThreshold: Int
        get() = prefs.getInt("low_stock_threshold", 5)
        set(value) = prefs.edit().putInt("low_stock_threshold", value).apply()

    /** Global default credit limit (₱) for customers without their own limit
     *  (web v2.56 parity). 0 = no limit. Per-customer override wins. */
    var defaultCreditLimit: Int
        get() = prefs.getInt("default_credit_limit", 500)
        set(value) = prefs.edit().putInt("default_credit_limit", value).apply()

    /** Last selected Reports period ("day"/"week"/"month") — survives app restart (web v2.55 parity). */
    var reportPeriod: String
        get() = prefs.getString("report_period", "day") ?: "day"
        set(value) = prefs.edit().putString("report_period", value).apply()

    fun getTextScaleFactor(): Float = when (textSize) {
        "standard" -> 1.0f
        "large" -> 1.125f
        else -> 1.25f
    }

    var dayOpen: Boolean
        get() = prefs.getBoolean("day_open", false)
        set(value) = prefs.edit().putBoolean("day_open", value).apply()

    var dayDate: String
        get() = prefs.getString("day_date", "") ?: ""
        set(value) = prefs.edit().putString("day_date", value).apply()

    var dayArchived: Boolean
        get() = prefs.getBoolean("day_archived", false)
        set(value) = prefs.edit().putBoolean("day_archived", value).apply()

    // ── Notification settings (V2.70 — NotificationManagement analysis §5.3) ──
    /** Master notification on/off switch. */
    var notificationsEnabled: Boolean
        get() = prefs.getBoolean("notifications_enabled", true)
        set(value) = prefs.edit().putBoolean("notifications_enabled", value).apply()

    /** Per-category toggles. Digest is opt-in (off by default). */
    var notifyOverdue: Boolean
        get() = prefs.getBoolean("notify_overdue", true)
        set(value) = prefs.edit().putBoolean("notify_overdue", value).apply()

    var notifyStock: Boolean
        get() = prefs.getBoolean("notify_stock", true)
        set(value) = prefs.edit().putBoolean("notify_stock", value).apply()

    var notifyClosing: Boolean
        get() = prefs.getBoolean("notify_closing", true)
        set(value) = prefs.edit().putBoolean("notify_closing", value).apply()

    var notifyDigest: Boolean
        get() = prefs.getBoolean("notify_digest", false) // opt-in
        set(value) = prefs.edit().putBoolean("notify_digest", value).apply()

    /** Hour (6-21) at which the evening closing reminder fires. */
    var closingReminderHour: Int
        get() = prefs.getInt("closing_reminder_hour", 18)
        set(value) = prefs.edit().putInt("closing_reminder_hour", value.coerceIn(6, 21)).apply()

    /** Whether the contextual permission primer has been shown (once). */
    var notifPrimerShown: Boolean
        get() = prefs.getBoolean("notif_primer_shown", false)
        set(value) = prefs.edit().putBoolean("notif_primer_shown", value).apply()

    /** Last-notified throttle map persisted as a StringSet of "key=epochDay".
     *  Always assign a fresh set — SharedPreferences StringSets must not be
     *  mutated in place. */
    var notifiedKeys: Set<String>
        get() = prefs.getStringSet("notified_keys", emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet("notified_keys", value).apply()

    // ── SMS settings (automated debt reminders) ──
    /** Master SMS on/off switch. */
    var smsEnabled: Boolean
        get() = prefs.getBoolean("sms_enabled", false)  // off by default
        set(value) = prefs.edit().putBoolean("sms_enabled", value).apply()

    /** SMS reminder interval in days (minimum 3). */
    var smsReminderDays: Int
        get() = prefs.getInt("sms_reminder_days", 7)
        set(value) = prefs.edit().putInt("sms_reminder_days", value.coerceAtLeast(3)).apply()

    /** Quiet hours start (0-23). No SMS before this hour. */
    var smsQuietHoursStart: Int
        get() = prefs.getInt("sms_quiet_start", 8)
        set(value) = prefs.edit().putInt("sms_quiet_start", value.coerceIn(0, 23)).apply()

    /** Quiet hours end (0-23). No SMS after this hour. */
    var smsQuietHoursEnd: Int
        get() = prefs.getInt("sms_quiet_end", 20)
        set(value) = prefs.edit().putInt("sms_quiet_end", value.coerceIn(0, 23)).apply()

    /** Whether to send a final "fully paid" confirmation SMS. */
    var smsSendPaidConfirmation: Boolean
        get() = prefs.getBoolean("sms_paid_confirm", true)
        set(value) = prefs.edit().putBoolean("sms_paid_confirm", value).apply()

    fun clearAll() {
        prefs.edit().clear().apply()
    }
}

fun String.t(lang: String): String = Strings.get(this, lang)
