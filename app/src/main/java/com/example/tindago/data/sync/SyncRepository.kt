package com.example.tindago.data.sync

import android.content.Context
import io.ktor.client.*
import io.ktor.client.engine.android.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import com.example.tindago.data.local.AppDatabase

/**
 * Phase 2 — Supabase push sync (auth + 10-table upsert).
 * Schema v2: composite PKs (user_id, id) — every row carries user_id.
 * store_settings is intentionally NOT synced (web-managed, one row/user).
 */
class SyncRepository(
    private val context: Context,
    private val db: AppDatabase
) {
    private val client = HttpClient(Android) {
        engine { connectTimeout = 15_000; socketTimeout = 30_000 }
    }

    private val baseUrl = SupabaseConfig.SUPABASE_URL
    private val apiKey = SupabaseConfig.SUPABASE_ANON_KEY

    fun close() { client.close() }

    // ── AUTH ──────────────────────────────────────────────

    suspend fun signIn(email: String, password: String): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val body = JSONObject().apply {
                    put("email", email)
                    put("password", password)
                }
                val response: HttpResponse =
                    client.post("$baseUrl/auth/v1/token?grant_type=password") {
                        header("apikey", apiKey)
                        contentType(ContentType.Application.Json)
                        setBody(body.toString())
                    }
                val json = JSONObject(response.bodyAsText())
                if (response.status.isSuccess()) {
                    val token = json.getString("access_token")
                    val refresh = json.optString("refresh_token", null)
                    val uid = json.optJSONObject("user")?.optString("id", null)
                    SupabaseConfig.saveSession(context, token, refresh, uid, email)
                    Result.success("Signed in as $email")
                } else {
                    Result.failure(Exception(json.optString("error_description", "Login failed")))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    // ── FULL SYNC ─────────────────────────────────────────

    suspend fun syncAll(): Result<String> = withContext(Dispatchers.IO) {
        val token = SupabaseConfig.getToken(context)
            ?: return@withContext Result.failure(Exception("Not logged in"))

        try {
            val tables = listOf(
                "products" to productsToJson(),
                "customer_debts" to debtsToJson(),
                "debt_payments" to paymentsToJson(),
                "debt_transactions" to debtTransactionsToJson(),
                "specific_sales" to salesToJson(),
                "expenses" to expensesToJson(),
                "daily_entries" to dailyEntriesToJson(),
                "end_of_day_data" to endOfDayToJson(),
                "restock_log" to restockLogToJson(),
                "sms_log" to smsLogToJson()
            )

            var synced = 0
            for ((table, jsonArray) in tables) {
                if (jsonArray.length() == 0) continue
                upsertTable(token, table, jsonArray)
                synced++
            }

            SupabaseConfig.saveLastSyncTime(context, System.currentTimeMillis())
            Result.success("Synced $synced tables successfully")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ── HTTP UPSERT ───────────────────────────────────────

    private suspend fun upsertTable(token: String, table: String, data: JSONArray) {
        val response: HttpResponse = client.post("$baseUrl/rest/v1/$table") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $token")
            header("Prefer", "resolution=merge-duplicates")
            contentType(ContentType.Application.Json)
            setBody(data.toString())
        }
        if (!response.status.isSuccess()) {
            val error = response.bodyAsText()
            throw Exception("Failed to sync $table: ${response.status} — $error")
        }
    }

    private fun uid(): Any =
        SupabaseConfig.getUid(context) ?: JSONObject.NULL

    // ── ENTITY → JSON CONVERTERS (snake_case for Supabase) ─

    private suspend fun productsToJson(): JSONArray {
        val rows = db.productDao().getAllProducts().first()
        return JSONArray().apply {
            rows.forEach { e ->
                put(JSONObject().apply {
                    put("id", e.id)
                    put("user_id", uid())
                    put("name", e.name)
                    put("quantity", e.quantity)
                    put("cost_price", e.costPrice)
                    put("selling_price", e.sellingPrice)
                    put("unit", e.unit)
                    put("low_stock_threshold", e.lowStockThreshold)
                    put("category", e.category)
                    put("subcategory", e.subcategory)
                    put("brand", e.brand)
                    put("package_size", e.packageSize)
                })
            }
        }
    }

    private suspend fun salesToJson(): JSONArray {
        val rows = db.specificSaleDao().getAllSales().first()
        return JSONArray().apply {
            rows.forEach { e ->
                put(JSONObject().apply {
                    put("id", e.id)
                    put("user_id", uid())
                    put("date", e.date)
                    put("description", e.description)
                    put("amount", e.amount)
                    put("quantity", e.quantity)
                    put("customer_name", e.customerName ?: JSONObject.NULL)
                    put("profit", e.profit)
                    put("timestamp", e.timestamp)
                    put("transaction_id", e.transactionId)
                    put("payment_method", e.paymentMethod ?: JSONObject.NULL)
                })
            }
        }
    }

    private suspend fun debtsToJson(): JSONArray {
        val rows = db.customerDebtDao().getAllDebts().first()
        return JSONArray().apply {
            rows.forEach { e ->
                put(JSONObject().apply {
                    put("id", e.id)
                    put("user_id", uid())
                    put("customer_name", e.customerName)
                    put("amount", e.amount)
                    put("remaining_balance", e.remainingBalance)
                    put("created_at", e.createdAt)
                    put("credit_limit", e.creditLimit ?: JSONObject.NULL)
                    put("phone_number", e.phoneNumber)
                    put("sms_opt_in", e.smsOptIn ?: JSONObject.NULL)
                })
            }
        }
    }

    private suspend fun paymentsToJson(): JSONArray {
        val rows = db.debtPaymentDao().getAllPayments().first()
        return JSONArray().apply {
            rows.forEach { e ->
                put(JSONObject().apply {
                    put("id", e.id)
                    put("user_id", uid())
                    put("debt_id", e.debtId)
                    put("amount", e.amount)
                    put("timestamp", e.timestamp)
                    put("note", e.note ?: JSONObject.NULL)
                })
            }
        }
    }

    private suspend fun debtTransactionsToJson(): JSONArray {
        val rows = db.debtTransactionDao().getAllTransactions().first()
        return JSONArray().apply {
            rows.forEach { e ->
                put(JSONObject().apply {
                    put("id", e.id)
                    put("user_id", uid())
                    put("debt_id", e.debtId)
                    put("type", e.type)
                    put("description", e.description ?: JSONObject.NULL)
                    put("amount", e.amount)
                    put("timestamp", e.timestamp)
                })
            }
        }
    }

    private suspend fun expensesToJson(): JSONArray {
        val rows = db.expenseDao().getAllExpenses().first()
        return JSONArray().apply {
            rows.forEach { e ->
                put(JSONObject().apply {
                    put("id", e.id)
                    put("user_id", uid())
                    put("date", e.date)
                    put("category", e.category)
                    put("amount", e.amount)
                    put("note", e.note)
                    put("timestamp", e.timestamp)
                })
            }
        }
    }

    private suspend fun dailyEntriesToJson(): JSONArray {
        // Task 2 suspend query — returns List directly, no .first() needed.
        val rows = db.dailyEntryDao().getAllEntries()
        return JSONArray().apply {
            rows.forEach { e ->
                put(JSONObject().apply {
                    put("date", e.date)
                    put("user_id", uid())
                    put("stock_expenses", e.stockExpenses)
                    put("earnings", e.earnings)
                })
            }
        }
    }

    private suspend fun endOfDayToJson(): JSONArray {
        // Task 2 suspend query — returns List directly, no .first() needed.
        val rows = db.endOfDayDao().getAllEntries()
        return JSONArray().apply {
            rows.forEach { e ->
                put(JSONObject().apply {
                    put("date", e.date)
                    put("user_id", uid())
                    put("cash_in_drawer", e.cashInDrawer)
                    put("stock_check_done", e.stockCheckDone)
                    put("debt_payments_done", e.debtPaymentsDone)
                    put("finished", e.finished)
                    put("recorded_sales", e.recordedSales)
                    put("actual_sales", e.actualSales)
                    put("sales_diff", e.salesDiff)
                    put("profit", e.profit)
                    put("expenses", e.expenses)
                    put("net_profit", e.netProfit)
                })
            }
        }
    }

    private suspend fun restockLogToJson(): JSONArray {
        val rows = db.restockLogDao().getAll().first()
        return JSONArray().apply {
            rows.forEach { e ->
                put(JSONObject().apply {
                    put("id", e.id) // String PK — Supabase column is text
                    put("user_id", uid())
                    put("date", e.date)
                    put("items_json", e.itemsJson)
                    put("total_cost", e.totalCost)
                })
            }
        }
    }

    private suspend fun smsLogToJson(): JSONArray {
        val rows = db.smsLogDao().getAllLogs().first()
        return JSONArray().apply {
            rows.forEach { e ->
                put(JSONObject().apply {
                    put("id", e.id)
                    put("user_id", uid())
                    put("debt_id", e.debtId)
                    put("customer_name", e.customerName)
                    put("phone_number", e.phoneNumber)
                    put("type", e.type)
                    put("message_body", e.messageBody)
                    put("status", e.status)
                    put("timestamp", e.timestamp)
                })
            }
        }
    }
}
