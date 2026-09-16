package com.example.tindago.data.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.example.tindago.ui.localization.AppSettings
import com.example.tindago.ui.localization.t

/**
 * Notification channels (V2.70 — NotificationManagement analysis §5.2).
 * 4 channels, 1:1 with the notification categories. Only the OVERDUE channel
 * is IMPORTANCE_HIGH (heads-up + sound) — urgency discipline per the analysis:
 * overusing HIGH destroys its meaning.
 */
object NotificationChannels {
    const val OVERDUE = "overdue_store"
    const val STOCK = "stock_alerts"
    const val CLOSING = "closing_reminder"
    const val DIGEST = "weekly_digest"
    const val BACKUP = "backup"

    /** Create all channels — safe to call on every app start (idempotent). */
    fun createAll(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val lang = AppSettings(context).language

        val channels = listOf(
            NotificationChannel(
                OVERDUE, "notifChannelOverdue".t(lang),
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "notifChannelOverdueDesc".t(lang) },
            NotificationChannel(
                STOCK, "notifChannelStock".t(lang),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "notifChannelStockDesc".t(lang) },
            NotificationChannel(
                CLOSING, "notifChannelClosing".t(lang),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "notifChannelClosingDesc".t(lang) },
            NotificationChannel(
                DIGEST, "notifChannelDigest".t(lang),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "notifChannelDigestDesc".t(lang) },
            NotificationChannel(
                BACKUP, "notifChannelBackup".t(lang),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "notifChannelBackupDesc".t(lang) }
        )
        manager.createNotificationChannels(channels)
    }
}
