package com.example.tindago.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/**
 * SMS helper — one-tap pre-composed SMS via Android's native messaging app.
 *
 * Uses ACTION_SENDTO with "sms:" URI — no SEND_SMS permission needed,
 * no third-party gateway, no internet. The store owner taps Send in their
 * own messaging app (costs their SIM load, typically ₱0 on PH unlimited-text promos).
 */
object SmsHelper {

    /**
     * Philippine phone number validation.
     * Accepts: 09XX XXX XXXX, +63 9XX XXX XXXX, 63 9XX XXX XXXX
     * Returns the normalized 11-digit format (09XXXXXXXXX) or null if invalid.
     */
    fun normalizePhoneNumber(input: String): String? {
        val digits = input.replace(Regex("[^0-9+]"), "")
        return when {
            // 09XXXXXXXXX (11 digits) — already local format
            Regex("^09\\d{9}$").matches(digits) -> digits
            // +63XXXXXXXXXX (13 digits with +) — international format
            Regex("^\\+63\\d{10}$").matches(digits) -> "0" + digits.substring(3)
            // 63XXXXXXXXXX (12 digits without +) — international format
            Regex("^63\\d{10}$").matches(digits) -> "0" + digits.substring(2)
            // 9XXXXXXXXX (10 digits) — missing leading 0
            Regex("^9\\d{9}$").matches(digits) -> "0$digits"
            else -> null
        }
    }

    /**
     * Check if a phone number looks like a valid PH mobile number (before full validation).
     */
    fun isValidPartialPhone(input: String): Boolean {
        val digits = input.replace(Regex("[^0-9+]"), "")
        return digits.length in 10..13 && digits.any { it.isDigit() }
    }

    /**
     * Open the phone's native SMS app with a pre-composed message.
     * No permissions needed — uses ACTION_SENDTO which opens the default messaging app.
     */
    fun sendSmsIntent(context: Context, phoneNumber: String, message: String): Boolean {
        val normalized = normalizePhoneNumber(phoneNumber)
        if (normalized == null) {
            Toast.makeText(context, "Invalid phone number", Toast.LENGTH_SHORT).show()
            return false
        }
        try {
            val uri = Uri.parse("sms:$normalized")
            val intent = Intent(Intent.ACTION_SENDTO, uri).apply {
                putExtra("sms_body", message)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            return true
        } catch (e: Exception) {
            Toast.makeText(context, "No messaging app found", Toast.LENGTH_SHORT).show()
            return false
        }
    }

    /**
     * Generate a debt receipt message (immediately after credit transaction).
     *
     * Example:
     * "Hi Juan! Your purchase of ₱500.00 at Tindahan ni Maria is recorded.
     *  Your total balance is ₱500.00. Salamat!"
     */
    fun buildReceiptMessage(
        customerName: String,
        amount: Double,
        storeName: String,
        totalBalance: Double,
        lang: String = "fil"
    ): String {
        val peso = "₱${String.format("%,.2f", amount)}"
        val balance = "₱${String.format("%,.2f", totalBalance)}"
        return if (lang == "fil") {
            "Hi $customerName! Ang iyong purchase na $peso sa $storeName ay naitala na. " +
            "Ang iyong kabuuang balance ay $balance. Salamat!"
        } else {
            "Hi $customerName! Your purchase of $peso at $storeName has been recorded. " +
            "Your total balance is $balance. Thank you!"
        }
    }

    /**
     * Generate a debt reminder message (scheduled recurring).
     *
     * Example:
     * "Hi Juan! Reminder: Ang iyong balance sa Tindahan ni Maria ay ₱500.00
     *  (3 araw na; bayad na ₱0.00; natitirang credit ₱1,000.00 ng ₱1,500.00).
     *  Salamat! Reply STOP para tumigil."
     */
    fun buildReminderMessage(
        customerName: String,
        storeName: String,
        totalBalance: Double,
        daysOpen: Int,
        totalPaid: Double,
        effectiveCreditLimit: Int,
        remainingCredit: Double,
        lang: String = "fil"
    ): String {
        val balance = "₱${String.format("%,.2f", totalBalance)}"
        val paid = "₱${String.format("%,.2f", totalPaid)}"
        val credit = "₱${String.format("%,.2f", remainingCredit)}"
        val limit = "₱${String.format("%,.2f", effectiveCreditLimit.toDouble())}"
        val dayWord = if (lang == "fil") "araw" else "days"
        val stopLine = if (lang == "fil") "Reply STOP para tumigil." else "Reply STOP to unsubscribe."
        return if (lang == "fil") {
            "Hi $customerName! Reminder: Ang iyong balance sa $storeName ay $balance " +
            "($daysOpen $dayWord na; bayad na $paid; natitirang credit $credit ng $limit). " +
            "Salamat! $stopLine"
        } else {
            "Hi $customerName! Reminder: Your balance at $storeName is $balance " +
            "($daysOpen $dayWord old; paid $paid; remaining credit $credit of $limit). " +
            "Thank you! $stopLine"
        }
    }

    /**
     * Generate a fully-paid confirmation message.
     */
    fun buildPaidMessage(
        customerName: String,
        storeName: String,
        lang: String = "fil"
    ): String {
        return if (lang == "fil") {
            "Hi $customerName! Ang iyong utang sa $storeName ay bayad na lahat. " +
            "Maraming salamat sa pagbabayad! 🙏"
        } else {
            "Hi $customerName! Your debt at $storeName is fully paid. " +
            "Thank you so much! 🙏"
        }
    }

    /**
     * Format a debt age in human-readable form.
     */
    fun formatDebtAge(days: Int, lang: String = "fil"): String {
        return if (lang == "fil") {
            when {
                days == 0 -> "ngayong araw"
                days == 1 -> "1 araw"
                else -> "$days araw"
            }
        } else {
            when {
                days == 0 -> "today"
                days == 1 -> "1 day"
                else -> "$days days"
            }
        }
    }
}
