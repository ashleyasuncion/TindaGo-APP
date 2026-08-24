package com.example.tindago.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * SMS delivery log — records every SMS send attempt (receipt or reminder).
 * Used to avoid duplicate sends and to show delivery history.
 */
@Entity(tableName = "sms_log")
data class SmsLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    /** The debt ID this SMS relates to. */
    val debtId: Int,
    /** Customer name (denormalized for quick display). */
    val customerName: String,
    /** Phone number the SMS was sent to. */
    val phoneNumber: String,
    /** "receipt" or "reminder". */
    val type: String,
    /** The message body that was sent. */
    val messageBody: String,
    /** "sent" or "failed". */
    val status: String,
    /** Timestamp of the send attempt. */
    val timestamp: Long = System.currentTimeMillis()
)
