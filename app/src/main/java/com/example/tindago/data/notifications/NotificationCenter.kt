package com.example.tindago.data.notifications

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.tindago.MainActivity
import com.example.tindago.R
import com.example.tindago.ui.localization.t

/**
 * Deep-link URIs used by notifications (V2.70 — analysis FR-7). The same URIs
 * are registered as navDeepLink patterns in NavGraph so tapping a notification
 * opens the exact screen, not just the app root.
 */
object NotificationDeepLinks {
    const val MORNING = "tindago://morning"        // overdue store
    const val INVENTORY = "tindago://inventory"    // stock / restock
    const val CLOSING = "tindago://closing"        // closing reminder
    const val DEBTS = "tindago://debts"            // weekly digest
    const val SETTINGS = "tindago://settings"      // backup failure -> settings
    const val BACKUP = "tindago://backup"          // alias for backup deep link
}

/**
 * Builds and posts local notifications (V2.70 — analysis §5.1.4 / FR-6/7/9,
 * NFR-4). Stable per-channel ids so cancel-on-resolution and same-day updates
 * work; PRIVATE visibility keeps figures generic on the lock screen.
 */
object NotificationCenter {
    const val ID_OVERDUE = 2001
    const val ID_STOCK = 2002
    const val ID_CLOSING = 2003
    const val ID_DIGEST = 2004
    const val ID_BACKUP = 2005

    /** True when the app may actually post on this device (permission granted
     *  on 13+, always true below). Channels may still be individually disabled
     *  in system settings — [post] returns false in that case too. */
    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Post a (possibly grouped / InboxStyle) notification. Returns TRUE only
     *  when the notification was actually handed to the system; FALSE when the
     *  runtime permission is missing (13+), the channel is blocked, or notify
     *  threw — so callers (worker / dev panel) can report the truth. */
    fun post(
        context: Context,
        channelId: String,
        notificationId: Int,
        title: String,
        text: String,
        deepLink: String,
        lines: List<String>? = null,
        groupKey: String? = null,
        groupSummary: Boolean = false,
        onlyAlertOnce: Boolean = true
    ): Boolean {
        if (!canPost(context)) {
            return false
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            notificationId,
            Intent(Intent.ACTION_VIEW, Uri.parse(deepLink), context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_stat_tindago)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setOnlyAlertOnce(onlyAlertOnce)

        if (lines != null) {
            val inbox = NotificationCompat.InboxStyle()
            lines.forEach { inbox.addLine(it) }
            if (groupSummary) inbox.setSummaryText(text)
            builder.setStyle(inbox)
        }
        if (groupKey != null) builder.setGroup(groupKey)
        if (groupSummary) builder.setGroupSummary(true)

        return try {
            NotificationManagerCompat.from(context).notify(notificationId, builder.build())
            true
        } catch (_: SecurityException) {
            // Permission revoked mid-flight — drop silently (in-app alerts still work).
            false
        }
    }

    /** Recreate the channels (idempotent). Lets a tester restore a channel
     *  they accidentally disabled in system settings — a disabled channel
     *  silently swallows posts. */
    fun resetChannels(context: Context) {
        NotificationChannels.createAll(context)
    }

    /** Cancel one notification by its stable id (cancel-on-resolution). */
    fun cancel(context: Context, notificationId: Int) {
        NotificationManagerCompat.from(context).cancel(notificationId)
    }

    /**
     * Dev-Panel helper (V2.73): posts one sample notification per channel with
     * localized text, bypassing the rules/cooldowns — for quickly verifying
     * appearance, channels, and deep links without setting up app state or
     * waiting for a scheduled run. Returns the number ACTUALLY posted (a post
     * silently no-ops without permission or with a blocked channel).
     *
     * V2.74 fix: parameterized keys ("Out of stock: {n} items") used to post
     * their raw placeholder because the sample bypasses the worker's replace
     * calls. Every posted title/text now runs a safety substitution, so a raw
     * "{n}"/"{amount}" can never reach a banner.
     */
    fun postSampleNotifications(context: Context, lang: String): Int {
        var posted = 0
        fun post(channelId: String, id: Int, title: String, text: String, deepLink: String) {
            // Safety net: substitute sample values for any leftover placeholder.
            val safeTitle = title.replace("{n}", "3").replace("{amount}", "₱1,000.00")
            val safeText = text.replace("{n}", "3").replace("{amount}", "₱1,000.00")
            if (NotificationCenter.post(context, channelId, id, safeTitle, safeText, deepLink)) posted++
        }
        post(NotificationChannels.OVERDUE, ID_OVERDUE, "notifOverdueTitle".t(lang), "notifOverdueText".t(lang), NotificationDeepLinks.MORNING)
        post(NotificationChannels.STOCK, ID_STOCK, "notifStockTitle".t(lang), "notifStockText".t(lang), NotificationDeepLinks.INVENTORY)
        post(NotificationChannels.CLOSING, ID_CLOSING, "notifClosingTitle".t(lang), "notifClosingText".t(lang), NotificationDeepLinks.CLOSING)
        post(NotificationChannels.DIGEST, ID_DIGEST, "notifDigestTitle".t(lang), "notifDigestText".t(lang), NotificationDeepLinks.DEBTS)
        return posted
    }

    /** Cancel every notification this app posted. */
    fun cancelAll(context: Context) {
        NotificationManagerCompat.from(context).cancelAll()
    }
}
