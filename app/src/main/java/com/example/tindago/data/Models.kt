package com.example.tindago.data

/**
 * Data models matching the web prototype (git/TindaGo/)
 *
 * v2.59 parity: products carry structured identity fields — category, brand,
 * unit, and package size — instead of relying on the name alone to identify
 * the product (e.g. "Canned Tuna · Ligo · 155g can").
 */
data class Product(
    val id: Int,
    val name: String,
    val quantity: Int,
    val costPrice: Double,
    val sellingPrice: Double,
    val unit: String = "piece",
    val lowStockThreshold: Int = 5,
    /** Category key (web v2.59 parity — one of [ProductCatalog.CATEGORIES]).
     *  Empty string = uncategorized. */
    val category: String = "",
    /** Brand name (web v2.59 parity). Empty string = no brand. */
    val brand: String = "",
    /** Package size (web v2.59 parity), e.g. "155g", "1L". Empty = none. */
    val packageSize: String = ""
) {
    val status: StockStatus get() = when {
        quantity <= 0 -> StockStatus.OUT_OF_STOCK
        quantity <= lowStockThreshold -> StockStatus.LOW
        else -> StockStatus.PLENTY
    }
}

/**
 * Product identity catalogs — must stay in sync with the web prototype's
 * PRODUCT_CATEGORIES / PRODUCT_UNITS arrays (app.js) and the `cat*` / `unit*`
 * i18n keys in Strings.kt. Label lookup lives in Strings.productCategoryLabel()
 * / Strings.productUnitLabel().
 */
object ProductCatalog {
    val CATEGORIES = listOf(
        "soft_drinks", "bottled_water", "instant_coffee", "instant_noodles",
        "rice", "canned_sardines", "canned_tuna", "eggs", "bread",
        "biscuits", "chocolate", "chips", "salt", "sugar",
        "shampoo", "bath_soap", "laundry", "toothcare",
        "mosquito", "cigarettes"
    )

    val UNITS = listOf(
        "piece", "sachet", "pack", "box", "bottle", "can",
        "kg", "g", "L", "mL", "bundle", "dozen",
        "sack", "loaf", "tube", "bar", "sticks"
    )
}

enum class StockStatus { PLENTY, LOW, OUT_OF_STOCK }

data class DailyEntry(
    val date: String,
    val stockExpenses: Double,
    val earnings: Double
) {
    val grossProfit: Double get() = earnings - stockExpenses
}

data class SpecificSale(
    val id: Int,
    val date: String,
    val description: String,
    val amount: Double,
    val quantity: Int = 1,
    val customerName: String? = null,
    val profit: Double = 0.0,
    val timestamp: Long = System.currentTimeMillis(),
    /** Shared transaction id for all items of one multi-item checkout (web v2.63 parity).
     *  0 = standalone sale created outside the cart flow. */
    val transactionId: Long = 0,
    /** "cash" or "credit" — set on cart-based checkouts (web v2.63 parity). */
    val paymentMethod: String? = null
)

data class CustomerDebt(
    val id: Int,
    val customerName: String,
    val amount: Double,
    val remainingBalance: Double,
    val createdAt: Long = System.currentTimeMillis(),
    /** Per-customer credit limit (web v2.56 parity). null = uses the global
     *  default; 0 = no limit for this customer. */
    val creditLimit: Int? = null,
    /** Customer's mobile phone number (SMS feature). Empty = not provided. */
    val phoneNumber: String = "",
    /** Whether the customer has opted in to SMS reminders. null = not yet asked. */
    val smsOptIn: Boolean? = null
)

data class EndOfDayData(
    val date: String,
    val cashInDrawer: Double = 0.0,
    val stockCheckDone: Boolean = false,
    val debtPaymentsDone: Boolean = false,
    val finished: Boolean = false,
    val recordedSales: Double = 0.0,
    val actualSales: Double = 0.0,
    val salesDiff: Double = 0.0,
    val profit: Double = 0.0,
    /** Store expenses for the day (web V2.71 parity — Expense Log). */
    val expenses: Double = 0.0,
    /** Net Profit = profit (from items sold) - expenses. May be negative. */
    val netProfit: Double = 0.0
)

/**
 * Store operating expense — web V2.71 parity (ExpenseTracking analysis §10.1).
 * Net Profit = gross profit (from items sold) - sum of expenses. Only two
 * inputs are required: amount + category. Date defaults to today (editable for
 * backfilling) and the note is optional.
 */
data class Expense(
    val id: Int,
    /** YYYY-MM-DD business date this expense belongs to (defaults to today). */
    val date: String,
    /** Fixed category key (see [ExpenseCatalog.CATEGORIES]). */
    val category: String,
    val amount: Double,
    val note: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Fixed expense categories — must stay in sync with the web prototype's
 * EXPENSE_CATEGORIES array (app.js) and the `exp*` i18n keys in Strings.kt.
 * Filipino-first per the ExpenseTracking analysis §10.2.
 */
object ExpenseCatalog {
    val CATEGORIES = listOf(
        "utilities", "rent", "transport", "permits", "labor", "supplies", "maintenance", "other"
    )
}

data class DebtPayment(
    val id: Int,
    val debtId: Int,
    val amount: Double,
    val timestamp: Long = System.currentTimeMillis(),
    val note: String? = null
)

/**
 * Per-debt transaction ledger — mirrors the web's `debt.transactions[]` array.
 * Every debt-balance increase (utang sale, manual add) becomes a row so the
 * debt history can show individual entries with descriptions and dates,
 * exactly like the web prototype. Payments live in [DebtPayment].
 */
data class DebtTransaction(
    val id: Int,
    val debtId: Int,
    val type: String,            // "debt" = added to balance
    val description: String?,    // product name (utang sale) or "Manual"
    val amount: Double,
    val timestamp: Long = System.currentTimeMillis()
)

// ── Restock Day Models ─────────────────────────────────────────────────

data class RestockLogEntry(
    val id: String,
    val date: String,
    val items: List<PurchaseEntry>,
    val totalCost: Double
)

data class RestockTempState(
    val step: Int = 1,
    val corrections: List<Correction> = emptyList(),
    val purchases: List<PurchaseEntry> = emptyList()
)

data class Correction(
    val productId: String? = null,
    val productEntityId: Int = 0,
    val oldQty: Int = 0,
    val newQty: Int = 0
)

data class PurchaseEntry(
    val productId: String? = null,
    val productEntityId: Int = 0,
    val productName: String,
    val costPerUnit: Double,
    val qtyAdded: Int,
    val totalCost: Double
)
