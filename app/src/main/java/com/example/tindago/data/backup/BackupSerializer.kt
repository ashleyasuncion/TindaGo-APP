package com.example.tindago.data.backup

import com.example.tindago.data.CustomerDebt
import com.example.tindago.data.DailyEntry
import com.example.tindago.data.DebtPayment
import com.example.tindago.data.DebtTransaction
import com.example.tindago.data.EndOfDayData
import com.example.tindago.data.Expense
import com.example.tindago.data.Product
import com.example.tindago.data.PurchaseEntry
import com.example.tindago.data.RestockLogEntry
import com.example.tindago.data.SpecificSale
import com.example.tindago.ui.localization.AppSettings
import org.json.JSONArray
import org.json.JSONObject

/**
 * Versioned backup serializer (V3.0 — Automatic Backup feature).
 *
 * One canonical on-disk format is shared by the automatic worker, the
 * "Back Up Now" action and the existing manual Export/Import, so every backup
 * file is interchangeable and mutually restorable.
 *
 * Envelope:
 * ```
 * {
 *   "formatVersion": 2,
 *   "appVersion": "2.1",
 *   "createdAt": 1699999999999,
 *   "data": { products, dailyEntry, specificSales, debts, payments,
 *             debtTransactions, expenses, restockLog, settings }
 * }
 * ```
 * Files produced by older builds (a bare section object with no
 * `formatVersion`) are still importable — [parseData] unwraps them as v1.
 */
object BackupSerializer {

    /** Current backup format. Bump when the payload shape changes. */
    const val FORMAT_VERSION = 2

    /** Mirrors the app's versionName (build.gradle.kts). */
    const val APP_VERSION = "2.1"

    const val MIME_TYPE = "application/json"

    /** Complete, self-contained snapshot of everything the app persists. */
    data class BackupData(
        val products: List<Product> = emptyList(),
        val dailyEntry: DailyEntry? = null,
        val specificSales: List<SpecificSale> = emptyList(),
        val debts: List<CustomerDebt> = emptyList(),
        val payments: List<DebtPayment> = emptyList(),
        val debtTransactions: List<DebtTransaction> = emptyList(),
        val expenses: List<Expense> = emptyList(),
        val restockLogs: List<RestockLogEntry> = emptyList(),
        val endOfDay: EndOfDayData? = null,
        val settings: BackupSettings = BackupSettings()
    )

    data class ParsedBackup(
        val formatVersion: Int,
        val appVersion: String,
        val createdAt: Long,
        val data: BackupData
    )

    // ── Envelope ────────────────────────────────────────────────────────

    fun buildEnvelope(createdAt: Long, data: BackupData): JSONObject =
        JSONObject().apply {
            put("formatVersion", FORMAT_VERSION)
            put("appVersion", APP_VERSION)
            put("createdAt", createdAt)
            put("data", buildData(data))
        }

    fun buildData(data: BackupData): JSONObject =
        JSONObject().apply {
            put("products", productsToJson(data.products))
            put("dailyEntry", data.dailyEntry?.let { dailyEntryToJson(it) } ?: JSONObject.NULL)
            put("specificSales", salesToJson(data.specificSales))
            put("debts", debtsToJson(data.debts))
            put("payments", paymentsToJson(data.payments))
            put("debtTransactions", debtTransactionsToJson(data.debtTransactions))
            put("expenses", expensesToJson(data.expenses))
            put("restockLog", restockLogsToJson(data.restockLogs))
            put("endOfDay", data.endOfDay?.let { endOfDayToJson(it) } ?: JSONObject.NULL)
            put("settings", data.settings.toJson())
        }

    /**
     * Returns the inner `data` object from either the current envelope or a
     * legacy (v1) bare section object. Throws only for a non-object input.
     */
    fun parseData(json: JSONObject): JSONObject =
        if (json.has("data") && json.optJSONObject("data") != null) {
            json.getJSONObject("data")
        } else {
            json
        }

    fun parse(json: JSONObject): ParsedBackup {
        val dataObj = parseData(json)
        return ParsedBackup(
            formatVersion = json.optInt("formatVersion", 1),
            appVersion = json.optString("appVersion", ""),
            createdAt = json.optLong("createdAt", 0L),
            data = BackupData(
                products = productsFromJson(dataObj.optJSONArray("products")),
                dailyEntry = dataObj.optJSONObject("dailyEntry")?.let { dailyEntryFromJson(it) },
                specificSales = salesFromJson(dataObj.optJSONArray("specificSales")),
                debts = debtsFromJson(dataObj.optJSONArray("debts")),
                payments = paymentsFromJson(dataObj.optJSONArray("payments")),
                debtTransactions = debtTransactionsFromJson(dataObj.optJSONArray("debtTransactions")),
                expenses = expensesFromJson(dataObj.optJSONArray("expenses")),
                restockLogs = restockLogsFromJson(dataObj.optJSONArray("restockLog")),
                endOfDay = dataObj.optJSONObject("endOfDay")?.let { endOfDayFromJson(it) },
                settings = BackupSettings.fromJson(dataObj.optJSONObject("settings"))
            )
        )
    }

    // ── Products ────────────────────────────────────────────────────────

    fun productsToJson(products: List<Product>): JSONArray =
        JSONArray().apply {
            products.forEach { p ->
                put(JSONObject().apply {
                    put("id", p.id); put("name", p.name)
                    put("quantity", p.quantity); put("costPrice", p.costPrice)
                    put("sellingPrice", p.sellingPrice); put("unit", p.unit)
                    put("lowStockThreshold", p.lowStockThreshold)
                    put("category", p.category); put("brand", p.brand)
                    put("packageSize", p.packageSize)
                })
            }
        }

    fun productsFromJson(arr: JSONArray?): List<Product> {
        if (arr == null) return emptyList()
        val out = mutableListOf<Product>()
        for (i in 0 until arr.length()) {
            val p = arr.optJSONObject(i) ?: continue
            out.add(
                Product(
                    id = p.optInt("id", i + 1),
                    name = p.optString("name", ""),
                    quantity = p.optInt("quantity", 0),
                    costPrice = p.optDouble("costPrice", 0.0),
                    sellingPrice = p.optDouble("sellingPrice", 0.0),
                    unit = p.optString("unit", "piece"),
                    lowStockThreshold = p.optInt("lowStockThreshold", 5),
                    category = p.optString("category", ""),
                    brand = p.optString("brand", ""),
                    packageSize = p.optString("packageSize", "")
                )
            )
        }
        return out
    }

    // ── Daily entry ─────────────────────────────────────────────────────

    private fun dailyEntryToJson(de: DailyEntry): JSONObject =
        JSONObject().apply {
            put("date", de.date)
            put("stockExpenses", de.stockExpenses)
            put("earnings", de.earnings)
        }

    private fun dailyEntryFromJson(o: JSONObject): DailyEntry =
        DailyEntry(
            date = o.optString("date", ""),
            stockExpenses = o.optDouble("stockExpenses", 0.0),
            earnings = o.optDouble("earnings", 0.0)
        )

    // ── Specific sales ──────────────────────────────────────────────────

    private fun salesToJson(sales: List<SpecificSale>): JSONArray =
        JSONArray().apply {
            sales.forEach { s ->
                put(JSONObject().apply {
                    put("id", s.id); put("date", s.date); put("description", s.description)
                    put("amount", s.amount); put("quantity", s.quantity)
                    put("customerName", s.customerName ?: JSONObject.NULL)
                    put("profit", s.profit)
                    put("timestamp", s.timestamp)
                    put("transactionId", s.transactionId)
                    put("paymentMethod", s.paymentMethod ?: JSONObject.NULL)
                })
            }
        }

    private fun salesFromJson(arr: JSONArray?): List<SpecificSale> {
        if (arr == null) return emptyList()
        val out = mutableListOf<SpecificSale>()
        for (i in 0 until arr.length()) {
            val s = arr.optJSONObject(i) ?: continue
            out.add(
                SpecificSale(
                    id = s.optInt("id", i + 1),
                    date = s.optString("date", ""),
                    description = s.optString("description", ""),
                    amount = s.optDouble("amount", 0.0),
                    quantity = s.optInt("quantity", 1),
                    customerName = if (s.isNull("customerName")) null else s.optString("customerName", null),
                    profit = s.optDouble("profit", 0.0),
                    timestamp = s.optLong("timestamp", System.currentTimeMillis()),
                    transactionId = s.optLong("transactionId", 0L),
                    paymentMethod = if (s.isNull("paymentMethod")) null else s.optString("paymentMethod", null)
                )
            )
        }
        return out
    }

    // ── Debts ───────────────────────────────────────────────────────────

    private fun debtsToJson(debts: List<CustomerDebt>): JSONArray =
        JSONArray().apply {
            debts.forEach { d ->
                put(JSONObject().apply {
                    put("id", d.id); put("customerName", d.customerName)
                    put("amount", d.amount); put("remainingBalance", d.remainingBalance)
                    put("createdAt", d.createdAt)
                    put("creditLimit", d.creditLimit ?: JSONObject.NULL)
                    put("phoneNumber", d.phoneNumber)
                    put("smsOptIn", d.smsOptIn ?: JSONObject.NULL)
                })
            }
        }

    private fun debtsFromJson(arr: JSONArray?): List<CustomerDebt> {
        if (arr == null) return emptyList()
        val out = mutableListOf<CustomerDebt>()
        for (i in 0 until arr.length()) {
            val d = arr.optJSONObject(i) ?: continue
            out.add(
                CustomerDebt(
                    id = d.optInt("id", i + 1),
                    customerName = d.optString("customerName", ""),
                    amount = d.optDouble("amount", 0.0),
                    remainingBalance = d.optDouble("remainingBalance", 0.0),
                    createdAt = d.optLong("createdAt", System.currentTimeMillis()),
                    creditLimit = if (d.isNull("creditLimit")) null
                    else d.optInt("creditLimit", -1).takeIf { it >= 0 },
                    phoneNumber = d.optString("phoneNumber", ""),
                    smsOptIn = if (d.isNull("smsOptIn")) null else d.optBoolean("smsOptIn", false)
                )
            )
        }
        return out
    }

    // ── Debt payments ───────────────────────────────────────────────────

    private fun paymentsToJson(payments: List<DebtPayment>): JSONArray =
        JSONArray().apply {
            payments.forEach { p ->
                put(JSONObject().apply {
                    put("id", p.id); put("debtId", p.debtId); put("amount", p.amount)
                    put("timestamp", p.timestamp)
                    put("note", p.note ?: JSONObject.NULL)
                })
            }
        }

    private fun paymentsFromJson(arr: JSONArray?): List<DebtPayment> {
        if (arr == null) return emptyList()
        val out = mutableListOf<DebtPayment>()
        for (i in 0 until arr.length()) {
            val p = arr.optJSONObject(i) ?: continue
            out.add(
                DebtPayment(
                    id = p.optInt("id", i + 1),
                    debtId = p.optInt("debtId", 0),
                    amount = p.optDouble("amount", 0.0),
                    timestamp = p.optLong("timestamp", System.currentTimeMillis()),
                    note = if (p.isNull("note")) null else p.optString("note", null)
                )
            )
        }
        return out
    }

    // ── Debt transactions (ledger) ──────────────────────────────────────

    private fun debtTransactionsToJson(txs: List<DebtTransaction>): JSONArray =
        JSONArray().apply {
            txs.forEach { t ->
                put(JSONObject().apply {
                    put("id", t.id); put("debtId", t.debtId); put("type", t.type)
                    put("description", t.description ?: JSONObject.NULL)
                    put("amount", t.amount); put("timestamp", t.timestamp)
                })
            }
        }

    private fun debtTransactionsFromJson(arr: JSONArray?): List<DebtTransaction> {
        if (arr == null) return emptyList()
        val out = mutableListOf<DebtTransaction>()
        for (i in 0 until arr.length()) {
            val t = arr.optJSONObject(i) ?: continue
            out.add(
                DebtTransaction(
                    id = t.optInt("id", i + 1),
                    debtId = t.optInt("debtId", 0),
                    type = t.optString("type", "debt"),
                    description = if (t.isNull("description")) null else t.optString("description", null),
                    amount = t.optDouble("amount", 0.0),
                    timestamp = t.optLong("timestamp", System.currentTimeMillis())
                )
            )
        }
        return out
    }

    // ── Expenses ────────────────────────────────────────────────────────

    private fun expensesToJson(expenses: List<Expense>): JSONArray =
        JSONArray().apply {
            expenses.forEach { e ->
                put(JSONObject().apply {
                    put("id", e.id); put("date", e.date); put("category", e.category)
                    put("amount", e.amount); put("note", e.note); put("timestamp", e.timestamp)
                })
            }
        }

    private fun expensesFromJson(arr: JSONArray?): List<Expense> {
        if (arr == null) return emptyList()
        val out = mutableListOf<Expense>()
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            out.add(
                Expense(
                    id = e.optInt("id", i + 1),
                    date = e.optString("date", ""),
                    category = e.optString("category", "other"),
                    amount = e.optDouble("amount", 0.0),
                    note = e.optString("note", ""),
                    timestamp = e.optLong("timestamp", System.currentTimeMillis())
                )
            )
        }
        return out
    }

    // ── Restock log ─────────────────────────────────────────────────────

    private fun restockLogsToJson(logs: List<RestockLogEntry>): JSONArray =
        JSONArray().apply {
            logs.forEach { entry ->
                val items = JSONArray()
                entry.items.forEach { item ->
                    items.put(JSONObject().apply {
                        item.productId?.let { put("productId", it) }
                        put("productEntityId", item.productEntityId)
                        put("productName", item.productName)
                        put("costPerUnit", item.costPerUnit)
                        put("qtyAdded", item.qtyAdded)
                        put("totalCost", item.totalCost)
                    })
                }
                put(JSONObject().apply {
                    put("id", entry.id)
                    put("date", entry.date)
                    put("totalCost", entry.totalCost)
                    put("items", items)
                })
            }
        }

    private fun restockLogsFromJson(arr: JSONArray?): List<RestockLogEntry> {
        if (arr == null) return emptyList()
        val out = mutableListOf<RestockLogEntry>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val items = mutableListOf<PurchaseEntry>()
            val itemsArr = o.optJSONArray("items")
            if (itemsArr != null) {
                for (j in 0 until itemsArr.length()) {
                    val it = itemsArr.optJSONObject(j) ?: continue
                    items.add(
                        PurchaseEntry(
                            productId = if (it.isNull("productId")) null else it.optString("productId", null),
                            productEntityId = it.optInt("productEntityId", 0),
                            productName = it.optString("productName", ""),
                            costPerUnit = it.optDouble("costPerUnit", 0.0),
                            qtyAdded = it.optInt("qtyAdded", 0),
                            totalCost = it.optDouble("totalCost", 0.0)
                        )
                    )
                }
            }
            out.add(
                RestockLogEntry(
                    id = o.optString("id", "restock_$i"),
                    date = o.optString("date", ""),
                    items = items,
                    totalCost = o.optDouble("totalCost", 0.0)
                )
            )
        }
        return out
    }

    // ── End-of-day snapshot ─────────────────────────────────────────────

    private fun endOfDayToJson(e: EndOfDayData): JSONObject =
        JSONObject().apply {
            put("date", e.date); put("cashInDrawer", e.cashInDrawer)
            put("stockCheckDone", e.stockCheckDone); put("debtPaymentsDone", e.debtPaymentsDone)
            put("finished", e.finished); put("recordedSales", e.recordedSales)
            put("actualSales", e.actualSales); put("salesDiff", e.salesDiff)
            put("profit", e.profit); put("expenses", e.expenses); put("netProfit", e.netProfit)
        }

    private fun endOfDayFromJson(o: JSONObject): EndOfDayData =
        EndOfDayData(
            date = o.optString("date", ""),
            cashInDrawer = o.optDouble("cashInDrawer", 0.0),
            stockCheckDone = o.optBoolean("stockCheckDone", false),
            debtPaymentsDone = o.optBoolean("debtPaymentsDone", false),
            finished = o.optBoolean("finished", false),
            recordedSales = o.optDouble("recordedSales", 0.0),
            actualSales = o.optDouble("actualSales", 0.0),
            salesDiff = o.optDouble("salesDiff", 0.0),
            profit = o.optDouble("profit", 0.0),
            expenses = o.optDouble("expenses", 0.0),
            netProfit = o.optDouble("netProfit", 0.0)
        )

    // ── AppSettings snapshot ────────────────────────────────────────────

    /**
     * Allowlisted snapshot of the app's SharedPreferences. Device/runtime-only
     * keys (throttle maps, primer flags, launch counters, backup scheduling
     * state) are intentionally excluded so a restore can't clobber the
     * receiving device's own scheduling.
     */
    data class BackupSettings(
        val language: String = "en",
        val textSize: String = "standard",
        val storeName: String = "My Store",
        val ownerName: String = "Owner",
        val hasCompletedSetup: Boolean = false,
        val hasCompletedTutorial: Boolean = false,
        val defaultMarkup: Int = 20,
        val lowStockThreshold: Int = 5,
        val defaultCreditLimit: Int = 500,
        val reportPeriod: String = "day",
        val dayOpen: Boolean = false,
        val dayDate: String = "",
        val dayArchived: Boolean = false,
        val notificationsEnabled: Boolean = true,
        val notifyOverdue: Boolean = true,
        val notifyStock: Boolean = true,
        val notifyClosing: Boolean = true,
        val notifyDigest: Boolean = false,
        val closingReminderHour: Int = 18,
        val smsEnabled: Boolean = false,
        val smsReminderDays: Int = 7,
        val smsQuietHoursStart: Int = 8,
        val smsQuietHoursEnd: Int = 20,
        val smsSendPaidConfirmation: Boolean = true
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("language", language); put("textSize", textSize)
            put("storeName", storeName); put("ownerName", ownerName)
            put("hasCompletedSetup", hasCompletedSetup)
            put("hasCompletedTutorial", hasCompletedTutorial)
            put("defaultMarkup", defaultMarkup)
            put("lowStockThreshold", lowStockThreshold)
            put("defaultCreditLimit", defaultCreditLimit)
            put("reportPeriod", reportPeriod)
            put("dayOpen", dayOpen); put("dayDate", dayDate); put("dayArchived", dayArchived)
            put("notificationsEnabled", notificationsEnabled)
            put("notifyOverdue", notifyOverdue); put("notifyStock", notifyStock)
            put("notifyClosing", notifyClosing); put("notifyDigest", notifyDigest)
            put("closingReminderHour", closingReminderHour)
            put("smsEnabled", smsEnabled); put("smsReminderDays", smsReminderDays)
            put("smsQuietHoursStart", smsQuietHoursStart)
            put("smsQuietHoursEnd", smsQuietHoursEnd)
            put("smsSendPaidConfirmation", smsSendPaidConfirmation)
        }

        companion object {
            fun fromJson(o: JSONObject?): BackupSettings {
                val d = BackupSettings()
                if (o == null) return d
                return BackupSettings(
                    language = o.optString("language", d.language),
                    textSize = o.optString("textSize", d.textSize),
                    storeName = o.optString("storeName", d.storeName),
                    ownerName = o.optString("ownerName", d.ownerName),
                    hasCompletedSetup = o.optBoolean("hasCompletedSetup", d.hasCompletedSetup),
                    hasCompletedTutorial = o.optBoolean("hasCompletedTutorial", d.hasCompletedTutorial),
                    defaultMarkup = o.optInt("defaultMarkup", d.defaultMarkup),
                    lowStockThreshold = o.optInt("lowStockThreshold", d.lowStockThreshold),
                    defaultCreditLimit = o.optInt("defaultCreditLimit", d.defaultCreditLimit),
                    reportPeriod = o.optString("reportPeriod", d.reportPeriod),
                    dayOpen = o.optBoolean("dayOpen", d.dayOpen),
                    dayDate = o.optString("dayDate", d.dayDate),
                    dayArchived = o.optBoolean("dayArchived", d.dayArchived),
                    notificationsEnabled = o.optBoolean("notificationsEnabled", d.notificationsEnabled),
                    notifyOverdue = o.optBoolean("notifyOverdue", d.notifyOverdue),
                    notifyStock = o.optBoolean("notifyStock", d.notifyStock),
                    notifyClosing = o.optBoolean("notifyClosing", d.notifyClosing),
                    notifyDigest = o.optBoolean("notifyDigest", d.notifyDigest),
                    closingReminderHour = o.optInt("closingReminderHour", d.closingReminderHour),
                    smsEnabled = o.optBoolean("smsEnabled", d.smsEnabled),
                    smsReminderDays = o.optInt("smsReminderDays", d.smsReminderDays),
                    smsQuietHoursStart = o.optInt("smsQuietHoursStart", d.smsQuietHoursStart),
                    smsQuietHoursEnd = o.optInt("smsQuietHoursEnd", d.smsQuietHoursEnd),
                    smsSendPaidConfirmation = o.optBoolean("smsSendPaidConfirmation", d.smsSendPaidConfirmation)
                )
            }
        }
    }

    /** Read the allowlisted settings out of SharedPreferences. */
    fun readSettings(settings: AppSettings): BackupSettings =
        BackupSettings(
            language = settings.language,
            textSize = settings.textSize,
            storeName = settings.storeName,
            ownerName = settings.ownerName,
            hasCompletedSetup = settings.hasCompletedSetup,
            hasCompletedTutorial = settings.hasCompletedTutorial,
            defaultMarkup = settings.defaultMarkup,
            lowStockThreshold = settings.lowStockThreshold,
            defaultCreditLimit = settings.defaultCreditLimit,
            reportPeriod = settings.reportPeriod,
            dayOpen = settings.dayOpen,
            dayDate = settings.dayDate,
            dayArchived = settings.dayArchived,
            notificationsEnabled = settings.notificationsEnabled,
            notifyOverdue = settings.notifyOverdue,
            notifyStock = settings.notifyStock,
            notifyClosing = settings.notifyClosing,
            notifyDigest = settings.notifyDigest,
            closingReminderHour = settings.closingReminderHour,
            smsEnabled = settings.smsEnabled,
            smsReminderDays = settings.smsReminderDays,
            smsQuietHoursStart = settings.smsQuietHoursStart,
            smsQuietHoursEnd = settings.smsQuietHoursEnd,
            smsSendPaidConfirmation = settings.smsSendPaidConfirmation
        )

    /** Write the allowlisted settings back into SharedPreferences. */
    fun applySettings(settings: AppSettings, b: BackupSettings) {
        settings.language = b.language
        settings.textSize = b.textSize
        settings.storeName = b.storeName
        settings.ownerName = b.ownerName
        settings.hasCompletedSetup = b.hasCompletedSetup
        settings.hasCompletedTutorial = b.hasCompletedTutorial
        settings.defaultMarkup = b.defaultMarkup
        settings.lowStockThreshold = b.lowStockThreshold
        settings.defaultCreditLimit = b.defaultCreditLimit
        settings.reportPeriod = b.reportPeriod
        settings.dayOpen = b.dayOpen
        settings.dayDate = b.dayDate
        settings.dayArchived = b.dayArchived
        settings.notificationsEnabled = b.notificationsEnabled
        settings.notifyOverdue = b.notifyOverdue
        settings.notifyStock = b.notifyStock
        settings.notifyClosing = b.notifyClosing
        settings.notifyDigest = b.notifyDigest
        settings.closingReminderHour = b.closingReminderHour
        settings.smsEnabled = b.smsEnabled
        settings.smsReminderDays = b.smsReminderDays
        settings.smsQuietHoursStart = b.smsQuietHoursStart
        settings.smsQuietHoursEnd = b.smsQuietHoursEnd
        settings.smsSendPaidConfirmation = b.smsSendPaidConfirmation
    }
}
