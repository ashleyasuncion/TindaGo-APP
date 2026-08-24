package com.example.tindago.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.tindago.data.CustomerDebt

@Entity(tableName = "customer_debts")
data class CustomerDebtEntity(
    @PrimaryKey val id: Int,
    val customerName: String,
    val amount: Double,
    val remainingBalance: Double,
    val createdAt: Long = System.currentTimeMillis(),
    /** Per-customer credit limit; null = uses global default (web v2.56 parity). */
    val creditLimit: Int? = null,
    /** Customer's mobile phone number (SMS feature). Empty = not provided. */
    val phoneNumber: String = "",
    /** Whether the customer has opted in to SMS reminders. null = not yet asked. */
    val smsOptIn: Int? = null  // 0 = false, 1 = true, null = not asked
) {
    fun toDomainModel(): CustomerDebt = CustomerDebt(
        id = id,
        customerName = customerName,
        amount = amount,
        remainingBalance = remainingBalance,
        createdAt = createdAt,
        creditLimit = creditLimit,
        phoneNumber = phoneNumber,
        smsOptIn = smsOptIn?.let { it == 1 }
    )

    companion object {
        fun fromDomainModel(debt: CustomerDebt): CustomerDebtEntity = CustomerDebtEntity(
            id = debt.id,
            customerName = debt.customerName,
            amount = debt.amount,
            remainingBalance = debt.remainingBalance,
            createdAt = debt.createdAt,
            creditLimit = debt.creditLimit,
            phoneNumber = debt.phoneNumber,
            smsOptIn = debt.smsOptIn?.let { if (it) 1 else 0 }
        )
    }
}
