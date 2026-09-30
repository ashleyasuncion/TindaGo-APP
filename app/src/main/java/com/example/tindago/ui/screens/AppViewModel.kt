package com.example.tindago.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import com.example.tindago.data.*
import com.example.tindago.data.backup.BackupManager
import com.example.tindago.data.backup.BackupResult
import com.example.tindago.data.backup.BackupSerializer
import com.example.tindago.data.local.AppDatabase
import com.example.tindago.data.sync.SupabaseConfig
import com.example.tindago.data.sync.SyncRepository
import com.example.tindago.ui.localization.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

/**
 * Shared ViewModel for the app — holds in-memory state with Room database persistence.
 * Phase 4: Database-backed persistence via AppRepository.
 */
class AppViewModel : ViewModel() {

    // ── State ──────────────────────────────────────────────────────────
    private val _products = MutableStateFlow<List<Product>>(emptyList())
    val products: StateFlow<List<Product>> = _products.asStateFlow()

    private val _dailyEntry = MutableStateFlow<DailyEntry?>(null)
    val dailyEntry: StateFlow<DailyEntry?> = _dailyEntry.asStateFlow()

    private val _specificSales = MutableStateFlow<List<SpecificSale>>(emptyList())
    val specificSales: StateFlow<List<SpecificSale>> = _specificSales.asStateFlow()

    private val _debts = MutableStateFlow<List<CustomerDebt>>(emptyList())
    val debts: StateFlow<List<CustomerDebt>> = _debts.asStateFlow()

    private val _payments = MutableStateFlow<List<DebtPayment>>(emptyList())
    val payments: StateFlow<List<DebtPayment>> = _payments.asStateFlow()

    private val _debtTransactions = MutableStateFlow<List<DebtTransaction>>(emptyList())
    val debtTransactions: StateFlow<List<DebtTransaction>> = _debtTransactions.asStateFlow()

    private val _expenses = MutableStateFlow<List<Expense>>(emptyList())
    /** Expense log (web V2.71 parity) — all store expenses, newest first. */
    val expenses: StateFlow<List<Expense>> = _expenses.asStateFlow()

    // ── Quick-Sell (Phase 4.1) — top sellers ranked by SUM(quantity) ─────────
    private val _quickSellProducts = MutableStateFlow<List<Product>>(emptyList())
    val quickSellProducts: StateFlow<List<Product>> = _quickSellProducts.asStateFlow()

    // ── Smart Utang (Phase 4.3) ─ recent debtors with outstanding balance ──
    private val _recentDebtors = MutableStateFlow<List<CustomerDebt>>(emptyList())
    val recentDebtors: StateFlow<List<CustomerDebt>> = _recentDebtors.asStateFlow()

    private val _endOfDayData = MutableStateFlow<EndOfDayData?>(null)
    val endOfDayData: StateFlow<EndOfDayData?> = _endOfDayData.asStateFlow()

    private val _backupEnabled = MutableStateFlow(false)
    val backupEnabled: StateFlow<Boolean> = _backupEnabled.asStateFlow()
    private val _backupIntervalHours = MutableStateFlow(168)
    val backupIntervalHours: StateFlow<Int> = _backupIntervalHours.asStateFlow()
    private val _backupLocationUri = MutableStateFlow("")
    val backupLocationUri: StateFlow<String> = _backupLocationUri.asStateFlow()

    private val _reportPeriod = MutableStateFlow("day")
    val reportPeriod: StateFlow<String> = _reportPeriod.asStateFlow()

    private val _currentDate = MutableStateFlow(dateNow())
    /** Observable current date (yyyy-MM-dd). Kept in sync with the device clock at
     *  each midnight and on app resume, so date-dependent UI (Morning overdue
     *  banner, Day/Closing entry guards, headers) recomputes in REAL time instead
     *  of freezing at the value captured when it first composed. */
    val currentDate: StateFlow<String> = _currentDate.asStateFlow()

    val today: String
        get() = if (devDateOverride.isNotBlank()) devDateOverride else _currentDate.value

    private fun dateNow(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

    /** Re-sync [currentDate] with the device clock (wired to app ON_RESUME). */
    fun refreshCurrentDate() {
        _currentDate.value = dateNow()
    }

    /** Dev-only temporary date override (YYYY-MM-DD).
     *  In-memory only — NEVER persisted to AppSettings/Room, so it resets on
     *  app restart and never affects real data permanently. */
    var devDateOverride: String by mutableStateOf("")

    /** Snapshot of the real business day taken when a dev override is applied,
     *  so clearing the override restores the exact pre-test state.
     *  In-memory only — never persisted, matching the override's lifetime. */
    private data class DayStateSnapshot(
        val dayOpen: Boolean,
        val dayDate: String,
        val dayArchived: Boolean,
        val sales: List<SpecificSale>,
        val dailyEntry: DailyEntry?,
        val endOfDayData: EndOfDayData?,
        val expenses: List<Expense>
    )

    private var devDaySnapshot: DayStateSnapshot? = null

    /** Set a temporary dev date override. Returns false if the format is invalid. */
    fun setDevDateOverride(date: String): Boolean {
        val d = date.trim()
        if (d.isEmpty()) {
            // Empty input clears the override and restores the pre-test business state.
            devDateOverride = ""
            restoreDevDaySnapshot()
            return true
        }
        if (!d.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) return false
        // Reject impossible dates (e.g. 2026-99-99) via non-lenient parse
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        fmt.isLenient = false
        try { fmt.parse(d) } catch (e: Exception) { return false }
        // Snapshot the real business day BEFORE applying the override so clearing
        // it restores the exact pre-test state (no sales/day-state loss).
        if (devDaySnapshot == null) {
            devDaySnapshot = DayStateSnapshot(
                dayOpen = dayOpen,
                dayDate = dayDate,
                dayArchived = dayArchived,
                sales = _specificSales.value.toList(),
                dailyEntry = _dailyEntry.value,
                endOfDayData = _endOfDayData.value,
                expenses = _expenses.value.toList()
            )
        }
        devDateOverride = d
        // If the perceived date moved past the open day, the Morning page now
        // SURFACES the stale day (overdue banner + close-stale button) instead
        // of auto-archiving it (web v2.35 parity).
        return true
    }

    fun clearDevDateOverride() {
        devDateOverride = ""
        restoreDevDaySnapshot()
    }

    /** Dev-only temporary HOUR override (0-23) for testing the time-based greeting.
     *  In-memory only — NEVER persisted, matching devDateOverride's lifetime. */
    var devTimeOverride: Int? by mutableStateOf(null)

    /** Time-of-day greeting key (web greetingForTime parity): devTimeOverride wins,
     *  otherwise the real device hour. h<12 → morning, h<18 → afternoon, else evening. */
    fun greetingForTimeKey(): String {
        val h = devTimeOverride ?: Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when {
            h < 12 -> "greetingMorning"
            h < 18 -> "greetingAfternoon"
            else -> "greetingEvening"
        }
    }

    /** Set a temporary dev time override from a raw string (mirrors setDevDateOverride).
     *  Blank = clear; non-numeric or out-of-range 0-23 = invalid (returns false). */
    fun setDevTimeOverride(input: String): Boolean {
        val t = input.trim()
        if (t.isEmpty()) { devTimeOverride = null; return true }   // blank = clear
        val hour = t.toIntOrNull() ?: return false                 // garbage = invalid
        if (hour < 0 || hour > 23) return false                    // out of range = invalid
        devTimeOverride = hour
        return true
    }

    fun clearDevTimeOverride() {
        devTimeOverride = null
    }

    /** Restores the exact pre-override business day (flags, date, sales, earnings, EOD).
     *  Sales are REPLACED (not merged) so any sales recorded during the test —
     *  dated to the override date — are purged on clear, with no test data
     *  leaking into real records (web v2.34 captureDevSnapshot parity). */
    private fun restoreDevDaySnapshot() {
        val snap = devDaySnapshot ?: return
        devDaySnapshot = null
        _specificSales.value = snap.sales.toList()
        dayOpen = snap.dayOpen
        dayDate = snap.dayDate
        dayArchived = snap.dayArchived
        _dailyEntry.value = snap.dailyEntry
        _endOfDayData.value = snap.endOfDayData
        _expenses.value = snap.expenses.toList()
        persistDayState()
    }

    // ── Computed helpers ────────────────────────────────────────────────
    val totalOutstandingDebts: Double
        get() = _debts.value.sumOf { it.remainingBalance }

    val activeDebtorCount: Int
        get() = _debts.value.count { it.remainingBalance > 0 }

    /** Compute last activity string from createdAt timestamp and latest payment */
    fun getLastActivity(debt: CustomerDebt): String {
        val paymentsForDebt = _payments.value.filter { it.debtId == debt.id }
        val latestPaymentTime = paymentsForDebt.maxOfOrNull { it.timestamp }
        val latestTime = maxOf(latestPaymentTime ?: debt.createdAt, debt.createdAt)
        val now = System.currentTimeMillis()
        val diff = now - latestTime
        return when {
            diff < 60_000 -> "Just now"
            diff < 3600_000 -> "${diff / 60_000}m ago"
            diff < 86_400_000 -> "${diff / 3600_000}h ago"
            diff < 172_800_000 -> "Yesterday"
            diff < 604_800_000 -> "${diff / 86_400_000}d ago"
            else -> {
                val sdf = java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault())
                sdf.format(java.util.Date(latestTime))
            }
        }
    }

    val lowStockCount: Int
        get() = _products.value.count { it.status == StockStatus.LOW }

    val outOfStockCount: Int
        get() = _products.value.count { it.status == StockStatus.OUT_OF_STOCK }

    val todaySpecificSalesTotal: Double
        get() = _specificSales.value.filter { it.date == today }.sumOf { it.amount }

    /** Total cash sales today (non-utang) — this is the Cash Sales Today value */
    val todayRecordedSales: Double
        get() = _specificSales.value.filter { it.date == today && it.customerName == null }.sumOf { it.amount }

    /** Total profit from today's specific sales (per-sale: (sellingPrice - costPrice) × qty) */
    val todayProfit: Double
        get() = _specificSales.value.filter { it.date == today }.sumOf { it.profit }

    /** Difference between Actual Sales and Recorded Sales */
    fun getSalesDiff(actualSales: Double): Double = actualSales - todayRecordedSales

    // ── Expense log helpers (web V2.71 parity) ───────────────────────────
    /** Sum of today's store expenses (gastos sa tindahan). */
    val todayExpensesTotal: Double
        get() = _expenses.value.filter { it.date == today }.sumOf { it.amount }

    /** Sum of expenses recorded for one exact business date. */
    fun getExpensesTotalFor(date: String): Double =
        _expenses.value.filter { it.date == date }.sumOf { it.amount }

    /** Sum of expenses recorded on or after a start date (reports period). */
    fun getPeriodExpensesTotal(startDate: String): Double =
        _expenses.value.filter { it.date >= startDate }.sumOf { it.amount }

    /** Net Profit = gross profit (from items sold) - today's expenses. */
    val todayNetProfit: Double
        get() = todayProfit - todayExpensesTotal

    /** Add a store expense. Returns null (and records nothing) when the amount
     *  is invalid or the category is blank — mirrors web addExpense() validation. */
    fun addExpense(date: String, category: String, amount: Double, note: String = ""): Expense? {
        if (amount <= 0 || category.isBlank()) return null
        _expenseIdCounter++
        val expense = Expense(
            id = _expenseIdCounter,
            date = date.ifBlank { today },
            category = category,
            amount = amount,
            note = note.trim()
        )
        _expenses.value = _expenses.value + expense
        return expense
    }

    /** Delete one expense; totals are recomputed from the log (no memory decrement). */
    fun deleteExpense(id: Int) {
        _expenses.value = _expenses.value.filter { it.id != id }
    }

    // ── EOD Editability State ──────────────────────────────────────────
    // ── AppSettings persistence for day state ───────────────────────────
    private var appSettings: AppSettings? = null

    /**
     * Initialize day state from persisted AppSettings.
     * Call this after ViewModel creation so dayOpen/dayDate/dayArchived
     * survive app restart.
     */
    fun initAppSettings(settings: AppSettings) {
        appSettings = settings
        _backupEnabled.value = settings.backupEnabled
        _backupIntervalHours.value = settings.backupIntervalHours
        _backupLocationUri.value = settings.backupLocationUri

        dayOpen = settings.dayOpen
        dayDate = settings.dayDate
        dayArchived = settings.dayArchived
        _reportPeriod.value = settings.reportPeriod

        // Phase 4.2 — Auto-open: clean launch can sell immediately (no morning tap needed).
        // Stale open days are NOT auto-opened — they require explicit owner action via snackbar.
        if (!dayOpen && !isStaleOpenDay()) {
            openDay()
        }
    }

    /** Save current day state to AppSettings so it survives app restart */
    fun persistDayState() {
        appSettings?.let {
            it.dayOpen = dayOpen
            it.dayDate = dayDate
            it.dayArchived = dayArchived
        }
    }

    /** Whether the business day is currently open */
    var dayOpen: Boolean by mutableStateOf(false)

    /** The calendar date (yyyy-MM-dd) this business day started on */
    var dayDate: String by mutableStateOf("")

    /** Whether today's sales data has been archived to history */
    var dayArchived: Boolean by mutableStateOf(false)

    /** Start the business day — sets dayOpen, dayDate, clears archive flag.
     *  Also clears the manual daily entry so a fresh day starts clean
     *  (web parity: startDay() resets todayExpenses/todayEarnings to 0).
     *  Prevents a stale DailyEntry.earnings from a previous session (e.g. a
     *  previously entered 1,250,000) from leaking into the Closing page's
     *  Actual Sales input or the Day mode stat.
     */
    fun openDay() {
        if (dayOpen) return
        if (isStaleOpenDay()) return
        dayOpen = true
        dayDate = today
        dayArchived = false
        _dailyEntry.value = null
        persistDayState()
    }

    // ── Overdue store workflow (web v2.35 parity) ──────────────────────
    // A store left open across business days is now SURFACED on the Morning
    // page (amber banner + "Review Last Day's Sales" modal) instead of being
    // silently auto-archived. Archiving only happens on explicit user action
    // via closeStaleDayAndStartToday().

    /** True when the store is open but the business day it started on (dayDate)
     *  is strictly BEFORE today. Uses `<` (not `!=`) so a device clock moved
     *  backward never flags a "future" day as overdue (web isStaleOpenDay()). */
    fun isStaleOpenDay(): Boolean =
        dayOpen && dayDate.isNotBlank() && dayDate < today

    /** Whole calendar days the current open day has been open (0 = opened today).
     *  Uses the dev-override-aware `today` so an override simulates the date
     *  (web getDaysOpen()). */
    fun getDaysOpen(): Int {
        if (dayDate.isBlank()) return 0
        return try {
            val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val then = fmt.parse(dayDate) ?: return 0
            val now = fmt.parse(today) ?: Date()
            val days = ((now.time - then.time) / (1000L * 60 * 60 * 24)).toInt()
            days.coerceAtLeast(0)
        } catch (_: Exception) { 0 }
    }

    /** Close the previous (stale) day and start a fresh day for today.
     *  Saves the previous day's sales into history — nothing is lost.
     *  The UI shows a confirm dialog first when a dev date override is active
     *  (archiving during an override writes to REAL persisted history). */
    fun closeStaleDayAndStartToday() {
        if (!isStaleOpenDay()) return
        archiveDaySales()
        // Clear the previous day's manual earnings so the fresh day starts clean
        // (web parity: closeStaleDayAndStartToday resets expenses/earnings to 0).
        _dailyEntry.value = null
        dayDate = today
        dayArchived = false
        dayOpen = true
        persistDayState()
    }

    val isEodComplete: Boolean
        get() = _endOfDayData.value?.finished == true

    private var _productIdCounter = 10
    private var _saleIdCounter = 3
    private var _debtIdCounter = 4
    private var _paymentIdCounter = 10
    private var _debtTxIdCounter = 100
    private var _expenseIdCounter = 100
    private var _tipRotationIndex = 0
    private var _restockIdCounter = 0

    // ── Restock Day State ──────────────────────────────────────────────
    private val _restockTemp = MutableStateFlow(RestockTempState())
    val restockTemp: StateFlow<RestockTempState> = _restockTemp.asStateFlow()

    private val _lastRestockDate = MutableStateFlow<String?>(null)
    val lastRestockDate: StateFlow<String?> = _lastRestockDate.asStateFlow()

    private val _restockLog = MutableStateFlow<List<RestockLogEntry>>(emptyList())
    val restockLog: StateFlow<List<RestockLogEntry>> = _restockLog.asStateFlow()

    val daysSinceLastRestock: Int get() {
        val date = _lastRestockDate.value ?: return -1
        return try {
            val then = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(date) ?: return -1
            val diff = System.currentTimeMillis() - then.time
            val days = (diff / (1000L * 60 * 60 * 24)).toInt()
            if (days < 0 || days > 365) { _lastRestockDate.value = null; -1 }
            else days
        } catch (_: Exception) { _lastRestockDate.value = null; -1 }
    }

    fun clearRestockData() {
        _lastRestockDate.value = null
        _restockLog.value = emptyList()
        _restockTemp.value = RestockTempState()
    }

    fun setRestockDateToday() {
        _lastRestockDate.value = today
    }

    fun viewRestockLogCount(): String = "${_restockLog.value.size} restock(s) on record"

    fun applyCorrection(correction: Correction) {
        val current = _restockTemp.value.corrections.toMutableList()
        val idx = current.indexOfFirst {
            (correction.productId != null && it.productId == correction.productId) ||
            (correction.productEntityId > 0 && it.productEntityId == correction.productEntityId)
        }
        if (idx >= 0) current[idx] = correction else current.add(correction)
        _restockTemp.value = _restockTemp.value.copy(corrections = current)
    }

    fun addPurchaseToTemp(purchase: PurchaseEntry) {
        val current = _restockTemp.value.purchases.toMutableList()
        current.add(purchase)
        _restockTemp.value = _restockTemp.value.copy(purchases = current)
    }

    fun removePurchaseFromTemp(index: Int) {
        val current = _restockTemp.value.purchases.toMutableList()
        if (index in current.indices) current.removeAt(index)
        _restockTemp.value = _restockTemp.value.copy(purchases = current)
    }

    fun setRestockStep(step: Int) {
        _restockTemp.value = _restockTemp.value.copy(step = step)
    }

    fun applyCorrectionsToProducts() {
        val corrections = _restockTemp.value.corrections
        val updated = _products.value.toMutableList()
        corrections.forEach { c ->
            if (c.productEntityId > 0) {
                val idx = updated.indexOfFirst { it.id == c.productEntityId }
                if (idx >= 0) updated[idx] = updated[idx].copy(quantity = c.newQty)
            }
        }
        _products.value = updated
    }

    fun completeRestock() {
        // Apply purchases to product quantities
        val updated = _products.value.toMutableList()
        val purchases = _restockTemp.value.purchases
        purchases.forEach { item ->
            if (item.productEntityId > 0) {
                val idx = updated.indexOfFirst { it.id == item.productEntityId }
                if (idx >= 0) {
                    updated[idx] = updated[idx].copy(
                        quantity = updated[idx].quantity + item.qtyAdded
                    )
                }
            } else {
                // New product
                _productIdCounter++
                updated.add(Product(
                    id = _productIdCounter,
                    name = item.productName,
                    quantity = item.qtyAdded,
                    costPrice = item.costPerUnit,
                    // New-product price uses the configured default markup from
                    // Settings (falls back to 20% when unset), matching the
                    // Add Stock markup helper.
                    sellingPrice = item.costPerUnit * (1 + (appSettings?.defaultMarkup ?: 20) / 100.0),
                    // Same for the low-stock alert threshold.
                    lowStockThreshold = appSettings?.lowStockThreshold ?: 5
                ))
            }
        }
        _products.value = updated

        // Create restock log entry
        _restockIdCounter++
        val entry = RestockLogEntry(
            id = "restock_$_restockIdCounter",
            date = today,
            items = purchases,
            totalCost = purchases.sumOf { it.totalCost }
        )
        _restockLog.value = _restockLog.value + entry
        _lastRestockDate.value = today

        // Reset temp state
        _restockTemp.value = RestockTempState()

        // Persist to Room (auto-saved via StateFlow.collect)
        viewModelScope.launch {
            repository?.saveRestockLog(entry)
        }
    }

    fun cancelRestock() {
        // Revert corrections already applied
        val corrections = _restockTemp.value.corrections
        val updated = _products.value.toMutableList()
        corrections.forEach { c ->
            if (c.productEntityId > 0) {
                val idx = updated.indexOfFirst { it.id == c.productEntityId }
                if (idx >= 0) updated[idx] = updated[idx].copy(quantity = c.oldQty)
            }
        }
        _products.value = updated
        _restockTemp.value = RestockTempState()
    }

    // ── Database persistence (Phase 4) ─────────────────────────────────
    private var repository: AppRepository? = null

    // ── Cloud sync (Phase 2 — Supabase push, offline-first unchanged) ──
    private var syncRepo: SyncRepository? = null
    private var appContext: Context? = null

    val syncStatus = MutableStateFlow<String?>(null)
    val isSyncing = MutableStateFlow(false)
    val isLoggedIn = MutableStateFlow(false)

    /**
     * Mirrors initRepository(): called once from NavGraph with the
     * application context + Room singleton. Idempotent.
     * Kept separate so previews (remember { AppViewModel() }) keep working —
     * no AndroidViewModel conversion.
     */
    fun initSync(context: Context, db: AppDatabase) {
        if (syncRepo == null) {
            appContext = context.applicationContext
            syncRepo = SyncRepository(appContext!!, db)
        }
    }

    fun checkLoginStatus() {
        val ctx = appContext ?: return
        isLoggedIn.value = SupabaseConfig.isLoggedIn(ctx)
    }

    fun signIn(email: String, password: String) {
        val repo = syncRepo ?: return
        viewModelScope.launch {
            syncStatus.value = "Signing in..."
            repo.signIn(email.trim(), password)
                .onSuccess { msg ->
                    syncStatus.value = msg
                    isLoggedIn.value = true
                }
                .onFailure { e ->
                    syncStatus.value = "Login failed: ${e.message}"
                }
        }
    }

    fun syncNow() {
        val repo = syncRepo ?: return
        if (isSyncing.value) return
        viewModelScope.launch {
            isSyncing.value = true
            syncStatus.value = "Syncing..."
            repo.syncAll()
                .onSuccess { msg -> syncStatus.value = msg }
                .onFailure { e -> syncStatus.value = "Sync failed: ${e.message}" }
            isSyncing.value = false
        }
    }

    fun signOut() {
        val ctx = appContext ?: return
        SupabaseConfig.logout(ctx)
        isLoggedIn.value = false
        syncStatus.value = "Signed out"
    }

    override fun onCleared() {
        syncRepo?.close()
        super.onCleared()
    }

    /**
     * Initialize Room database — loads saved data and sets up auto-save.
     * Uses `first()` for one-shot initial load, then observes state changes for persistence.
     */
    fun initRepository(repo: AppRepository) {
        repository = repo

        // Phase 1: Load initial data (one-shot)
        viewModelScope.launch {
            doInitialLoad(repo)
            if (_products.value.isEmpty()) {
                // No persisted products (fresh install or cleared inventory) →
                // auto-seed the full 225-item product catalog (web v2.59+ parity),
                // then persist so the seed survives app restarts.
                seedSampleData()
                persistAllToRepo(repo)
            }
            // Legacy stores (old 17-/120-item sample datasets) are left as-is;
            // the sample-product backfill was removed together with the dataset.
            // Stale open days are no longer auto-archived here — they are
            // surfaced on the Morning page (overdue banner, web v2.35 parity).
        }

        // Phase 2: Auto-save every state change to Room
        viewModelScope.launch {
            _products.collect { list ->
                list.forEach { repo.saveProduct(it) }
            }
        }
        viewModelScope.launch {
            _dailyEntry.collect { v ->
                v?.let { repo.saveDailyEntry(it) }
            }
        }
        viewModelScope.launch {
            _specificSales.collect { list ->
                list.forEach { repo.saveSpecificSale(it) }
            }
        }
        viewModelScope.launch {
            _debts.collect { list ->
                list.forEach { repo.saveDebt(it) }
            }
        }
        viewModelScope.launch {
            _payments.collect { list ->
                list.forEach { repo.savePayment(it) }
            }
        }
        viewModelScope.launch {
            _debtTransactions.collect { list ->
                list.forEach { repo.saveDebtTransaction(it) }
            }
        }
        viewModelScope.launch {
            _endOfDayData.collect { v ->
                v?.let { repo.saveEndOfDayData(it) }
            }
        }
        viewModelScope.launch {
            _expenses.collect { list ->
                list.forEach { repo.saveExpense(it) }
            }
        }

        // Phase 4.1 — Quick-Sell: rank by SUM(quantity) via DAO, map back to Product,
        // filter out-of-stock, pad with in-stock catalog when fewer than 8 ranked.
        // Previews never call initRepository() so they see emptyList() — safe.
        viewModelScope.launch {
            repo.getTopSellingNames().collect { names ->
                val byName = _products.value.associateBy { it.name }
                val ranked = names.mapNotNull { byName[it] }.filter { it.quantity > 0 }
                val padded = if (ranked.size < 8) {
                    val remaining = _products.value.filter { it.quantity > 0 && it !in ranked }
                        .sortedByDescending { it.quantity }.take(8 - ranked.size)
                    ranked + remaining
                } else ranked
                _quickSellProducts.value = padded.take(8)
            }
        }

        // Phase 4.3 ─ Smart Utang: derive recent debtors (no DAO migration) ──
        // Filters out paid-off debts, newest first (higher id = newer), max 5.
        // Previews never call initRepository() so they see emptyList() ─ safe.
        viewModelScope.launch {
            _debts.collect { all ->
                _recentDebtors.value = all.filter { it.remainingBalance > 0 }
                    .sortedByDescending { it.id }.take(5)
            }
        }
    }

    /**
     * One-shot initial load from Room using first() to get initial Flow emissions.
     */
    private suspend fun doInitialLoad(repo: AppRepository): Boolean {
        val products = repo.getAllProducts().first()
        if (products.isNotEmpty()) {
            _products.value = products
            // Restore product ID counter from max existing ID
            val maxId = products.maxOfOrNull { it.id } ?: 10
            if (maxId > _productIdCounter) _productIdCounter = maxId
        }
        val entry = repo.getLatestDailyEntry().first()
        if (entry != null) {
            _dailyEntry.value = entry
        }
        val sales = repo.getAllSpecificSales().first()
        if (sales.isNotEmpty()) {
            _specificSales.value = sales
            val maxSaleId = sales.maxOfOrNull { it.id } ?: 3
            if (maxSaleId > _saleIdCounter) _saleIdCounter = maxSaleId
        }
        val debts = repo.getAllDebts().first()
        if (debts.isNotEmpty()) {
            _debts.value = debts
            val maxDebtId = debts.maxOfOrNull { it.id } ?: 4
            if (maxDebtId > _debtIdCounter) _debtIdCounter = maxDebtId
        }
        val payments = repo.getAllPayments().first()
        if (payments.isNotEmpty()) {
            _payments.value = payments
            val maxPaymentId = payments.maxOfOrNull { it.id } ?: 10
            if (maxPaymentId > _paymentIdCounter) _paymentIdCounter = maxPaymentId
        }
        val debtTxs = repo.getAllDebtTransactions().first()
        if (debtTxs.isNotEmpty()) {
            _debtTransactions.value = debtTxs
            val maxTxId = debtTxs.maxOfOrNull { it.id } ?: 100
            if (maxTxId > _debtTxIdCounter) _debtTxIdCounter = maxTxId
        }
        // Web loadState parity: backfill a permanent initial ledger row for any
        // loaded debt that has none (pre-ledger data). Without this, a legacy
        // debt that later receives ONE new ledger entry would lose its initial
        // row and its running balance would silently stop reconciling.
        if (debts.isNotEmpty()) {
            val existingIds = _debtTransactions.value.map { it.debtId }.toSet()
            val missing = debts.filter { it.id !in existingIds }
            if (missing.isNotEmpty()) {
                val newTxs = _debtTransactions.value.toMutableList()
                missing.forEach { debt ->
                    _debtTxIdCounter++
                    newTxs.add(DebtTransaction(
                        id = _debtTxIdCounter,
                        debtId = debt.id,
                        type = "debt",
                        description = null, // rendered as the localized initial-debt label
                        amount = debt.amount,
                        timestamp = debt.createdAt
                    ))
                }
                _debtTransactions.value = newTxs
            }
        }
        val eod = repo.getLatestEndOfDayData().first()
        if (eod != null) _endOfDayData.value = eod
        val expenses = repo.getAllExpenses().first()
        if (expenses.isNotEmpty()) {
            _expenses.value = expenses
            val maxExpenseId = expenses.maxOfOrNull { it.id } ?: 100
            if (maxExpenseId > _expenseIdCounter) _expenseIdCounter = maxExpenseId
        }

        // Load latest restock log to restore last restock date (survives app restart)
        val restockLog = repo.getLatestRestockLog().first()
        if (restockLog != null) {
            _lastRestockDate.value = restockLog.date
            _restockLog.value = listOf(restockLog) + _restockLog.value.filter { it.id != restockLog.id }
        }

        return products.isNotEmpty() || debts.isNotEmpty() || payments.isNotEmpty() || sales.isNotEmpty() || entry != null || eod != null
    }

    private suspend fun persistAllToRepo(repo: AppRepository) {
        repo.saveProducts(_products.value)
        _dailyEntry.value?.let { repo.saveDailyEntry(it) }
        repo.saveSpecificSales(_specificSales.value)
        repo.saveDebts(_debts.value)
        repo.savePayments(_payments.value)
        repo.saveDebtTransactions(_debtTransactions.value)
        _endOfDayData.value?.let { repo.saveEndOfDayData(it) }
        repo.saveExpenses(_expenses.value)
    }

    /** True if data was loaded from persistence (not just seed data) */
    val hasPersistedData: Boolean get() = _products.value.isNotEmpty()

    // ── Stock Management ────────────────────────────────────────────────
    fun getProductById(id: Int): Product? = _products.value.find { it.id == id }

    fun deductStock(productId: Int, qty: Int) {
        val updated = _products.value.toMutableList()
        val index = updated.indexOfFirst { it.id == productId }
        if (index >= 0) {
            val p = updated[index]
            updated[index] = p.copy(quantity = (p.quantity - qty).coerceAtLeast(0))
            _products.value = updated
        }
    }

    /** Web v2.59 parity: identity fields (category/brand/unit/packageSize) are
     *  persisted on both the add and update paths so edits keep them intact. */
    fun addOrUpdateProduct(
        name: String,
        qty: Int,
        costPrice: Double,
        sellingPrice: Double,
        lowStockThreshold: Int = 5,
        category: String = "",
        subcategory: String = "",
        brand: String = "",
        unit: String = "piece",
        packageSize: String = ""
    ): Product {
        val existing = _products.value.find { it.name.equals(name, ignoreCase = true) }
        return if (existing != null) {
            val updated = _products.value.toMutableList()
            val index = updated.indexOfFirst { it.id == existing.id }
            updated[index] = existing.copy(
                quantity = existing.quantity + qty,
                costPrice = costPrice,
                sellingPrice = sellingPrice,
                lowStockThreshold = lowStockThreshold,
                category = category,
                subcategory = subcategory,
                brand = brand,
                unit = unit,
                packageSize = packageSize
            )
            _products.value = updated
            updated[index]
        } else {
            _productIdCounter++
            val newProduct = Product(
                _productIdCounter, name, qty, costPrice, sellingPrice,
                unit = unit, lowStockThreshold = lowStockThreshold,
                category = category, subcategory = subcategory, brand = brand, packageSize = packageSize
            )
            _products.value = _products.value + newProduct
            newProduct
        }
    }

    fun deleteProduct(productId: Int) {
        _products.value = _products.value.filter { it.id != productId }
    }

    /** Web v2.59 parity: product search covers ALL identity fields — name,
     *  category (key + EN/FIL labels), brand, unit (key + labels), and package
     *  size — so an owner can find products by category (e.g. "condiments" /
     *  "pampalasa"), brand, or size, not just by name. */
    fun searchProducts(query: String): List<Product> {
        if (query.isBlank()) return _products.value
        val q = query.lowercase()
        return _products.value.filter { p ->
            val hay = buildString {
                append(p.name.lowercase())
                if (p.category.isNotBlank()) {
                    append(' ').append(p.category.lowercase())
                    append(' ').append(com.example.tindago.ui.localization.Strings.productCategoryLabel(p.category, "en").lowercase())
                    append(' ').append(com.example.tindago.ui.localization.Strings.productCategoryLabel(p.category, "fil").lowercase())
                }
                if (p.subcategory.isNotBlank()) {
                    append(' ').append(p.subcategory.lowercase())
                    append(' ').append(com.example.tindago.ui.localization.Strings.productSubcategoryLabel(p.subcategory, "en").lowercase())
                    append(' ').append(com.example.tindago.ui.localization.Strings.productSubcategoryLabel(p.subcategory, "fil").lowercase())
                }
                if (p.brand.isNotBlank()) append(' ').append(p.brand.lowercase())
                if (p.unit.isNotBlank()) {
                    append(' ').append(p.unit.lowercase())
                    append(' ').append(com.example.tindago.ui.localization.Strings.productUnitLabel(p.unit, "en").lowercase())
                    append(' ').append(com.example.tindago.ui.localization.Strings.productUnitLabel(p.unit, "fil").lowercase())
                }
                if (p.packageSize.isNotBlank()) append(' ').append(p.packageSize.lowercase())
            }
            hay.contains(q)
        }
    }

    // ── Product identity helpers (web v2.59 parity) ────────────────────────

    /** Distinct brands already used by products — for Add Stock suggestions
     *  (web getUsedBrands parity). */
    fun getUsedBrands(): List<String> =
        _products.value.map { it.brand }.filter { it.isNotBlank() }.distinct().sorted()

    /** Distinct package sizes already used by products — for Add Stock
     *  suggestions (web getUsedPackageSizes parity). */
    fun getUsedPackageSizes(): List<String> =
        _products.value.map { it.packageSize }.filter { it.isNotBlank() }.distinct().sorted()

    /** Filter products by a category key (web v2.59 parity). Empty filter or
     *  "" returns ALL products (uncategorized products only match "all"). */
    fun getProductsByCategory(category: String): List<Product> {
        if (category.isBlank()) return _products.value
        return _products.value.filter { it.category == category }
    }

    /** index.html Section 2B parity — filter by subcategory within a category. */
    fun getProductsBySubcategory(subcategory: String): List<Product> {
        if (subcategory.isBlank()) return _products.value
        return _products.value.filter { it.subcategory == subcategory }
    }

    /** Two-level drill-down filter (web v2.59 renderManageInventory parity for the
     *  225-item two-level taxonomy). `subcategory` is expected to belong to
     *  [category]; a non-blank subcategory wins and an inconsistent (category,
     *  subcategory) pair naturally yields nothing. Blank subcategory → filter by
     *  category only; blank both → all products ('' = uncategorized never
     *  matches a non-blank category). */
    fun getProductsBySubcategory(category: String, subcategory: String): List<Product> {
        val byCategory = if (category.isBlank()) _products.value
                         else _products.value.filter { it.category == category }
        if (subcategory.isBlank()) return byCategory
        return byCategory.filter { it.subcategory == subcategory }
    }

    /** index.html Section 2B parity — drill-down filter for Checkout suggestions.
     *  `subcategory` wins over `category`; blank both = no category filter.
     *  Combined with the text search so `filteredProducts = search ∩ (subcategory ?: category)`. */
    fun getCheckoutFilteredProducts(query: String, category: String, subcategory: String): List<Product> {
        val byCategory = when {
            subcategory.isNotBlank() -> _products.value.filter { it.subcategory == subcategory }
            category.isNotBlank() -> _products.value.filter { it.category == category }
            else -> _products.value
        }
        if (query.isBlank()) return byCategory
        return byCategory.filter { p ->
            val hay = buildString {
                append(p.name.lowercase())
                if (p.category.isNotBlank()) {
                    append(' ').append(p.category.lowercase())
                    append(' ').append(com.example.tindago.ui.localization.Strings.productCategoryLabel(p.category, "en").lowercase())
                    append(' ').append(com.example.tindago.ui.localization.Strings.productCategoryLabel(p.category, "fil").lowercase())
                }
                if (p.subcategory.isNotBlank()) {
                    append(' ').append(p.subcategory.lowercase())
                    append(' ').append(com.example.tindago.ui.localization.Strings.productSubcategoryLabel(p.subcategory, "en").lowercase())
                    append(' ').append(com.example.tindago.ui.localization.Strings.productSubcategoryLabel(p.subcategory, "fil").lowercase())
                }
                if (p.brand.isNotBlank()) append(' ').append(p.brand.lowercase())
                if (p.unit.isNotBlank()) {
                    append(' ').append(p.unit.lowercase())
                    append(' ').append(com.example.tindago.ui.localization.Strings.productUnitLabel(p.unit, "en").lowercase())
                    append(' ').append(com.example.tindago.ui.localization.Strings.productUnitLabel(p.unit, "fil").lowercase())
                }
                if (p.packageSize.isNotBlank()) append(' ').append(p.packageSize.lowercase())
            }
            hay.contains(query.lowercase())
        }
    }

    /** Inventory (Stocks) page parity — combined text + two-level drill-down
     *  filter for the stock-inventory list (web renderManageInventory parity).
     *  Semantically identical to [getCheckoutFilteredProducts] but named for the
     *  inventory screen so StocksScreen doesn't borrow the checkout-branded API.
     *  `subcategory` wins over `category`; blank both = no category filter.
     *  Result = search ∩ (subcategory ?: category). */
    fun getInventoryFilteredProducts(query: String, category: String, subcategory: String): List<Product> {
        val byCategory = when {
            subcategory.isNotBlank() -> _products.value.filter { it.subcategory == subcategory }
            category.isNotBlank() -> _products.value.filter { it.category == category }
            else -> _products.value
        }
        if (query.isBlank()) return byCategory
        return byCategory.filter { p ->
            val hay = buildString {
                append(p.name.lowercase())
                if (p.category.isNotBlank()) {
                    append(' ').append(p.category.lowercase())
                    append(' ').append(com.example.tindago.ui.localization.Strings.productCategoryLabel(p.category, "en").lowercase())
                    append(' ').append(com.example.tindago.ui.localization.Strings.productCategoryLabel(p.category, "fil").lowercase())
                }
                if (p.subcategory.isNotBlank()) {
                    append(' ').append(p.subcategory.lowercase())
                    append(' ').append(com.example.tindago.ui.localization.Strings.productSubcategoryLabel(p.subcategory, "en").lowercase())
                    append(' ').append(com.example.tindago.ui.localization.Strings.productSubcategoryLabel(p.subcategory, "fil").lowercase())
                }
                if (p.brand.isNotBlank()) append(' ').append(p.brand.lowercase())
                if (p.unit.isNotBlank()) {
                    append(' ').append(p.unit.lowercase())
                    append(' ').append(com.example.tindago.ui.localization.Strings.productUnitLabel(p.unit, "en").lowercase())
                    append(' ').append(com.example.tindago.ui.localization.Strings.productUnitLabel(p.unit, "fil").lowercase())
                }
                if (p.packageSize.isNotBlank()) append(' ').append(p.packageSize.lowercase())
            }
            hay.contains(query.lowercase())
        }
    }

    fun getFilteredProducts(filter: String): List<Product> {
        return when (filter) {
            "plenty" -> _products.value.filter { it.status == StockStatus.PLENTY }
            "low" -> _products.value.filter { it.status == StockStatus.LOW }
            "out" -> _products.value.filter { it.status == StockStatus.OUT_OF_STOCK }
            else -> _products.value
        }
    }

    // ── Actions ─────────────────────────────────────────────────────────
    fun setReportPeriod(period: String) {
        _reportPeriod.value = period
        appSettings?.reportPeriod = period
    }

    fun recordDailyEntry(stockExpenses: Double, earnings: Double) {
        _dailyEntry.value = DailyEntry(
            date = today,
            stockExpenses = stockExpenses,
            earnings = earnings
        )
    }

    fun getPaymentsForDebt(debtId: Int): List<DebtPayment> =
        _payments.value.filter { it.debtId == debtId }.sortedBy { it.timestamp }

    fun getPaymentsForDebtFlow(debtId: Int) =
        repository?.getPaymentsByDebtId(debtId)

    fun addSpecificSale(sale: SpecificSale) {
        _saleIdCounter++
        val saleWithId = sale.copy(id = _saleIdCounter)
        val updated = _specificSales.value.toMutableList()
        updated.add(0, saleWithId)
        _specificSales.value = updated
    }

    fun addToDebtBalance(debtId: Int, amount: Double) {
        val updated = _debts.value.toMutableList()
        val index = updated.indexOfFirst { it.id == debtId }
        if (index >= 0) {
            val debt = updated[index]
            updated[index] = debt.copy(
                amount = debt.amount + amount,
                remainingBalance = debt.remainingBalance + amount
            )
            _debts.value = updated
        }
    }

    fun addDebt(debt: CustomerDebt): CustomerDebt {
        _debtIdCounter++
        val debtWithId = debt.copy(id = _debtIdCounter)
        val updated = _debts.value.toMutableList()
        updated.add(0, debtWithId)
        _debts.value = updated
        return debtWithId
    }

    fun getDebtById(id: Int): CustomerDebt? = _debts.value.find { it.id == id }

    fun getUsedCustomerNames(): List<String> = _debts.value.map { it.customerName }.distinct()

    // ── Multi-item checkout (web v2.63/v2.64 parity) ──────────────────────
    // The Day page's Sell action now opens a standalone checkout screen that
    // builds a CART of multiple products, then completes them as ONE
    // transaction: a shared transactionId on every sale row, a single debt
    // entry per credit purchase, per-line ledger entries, one stock deduction
    // pass, and one cart total used for the credit-limit gate.

    /** One line of the in-progress sale cart. */
    data class CartLine(
        val productId: Int,
        val name: String,
        val brand: String,
        val unit: String,
        val packageSize: String,
        val sellingPrice: Double,
        val qty: Int
    ) {
        val subtotal: Double get() = sellingPrice * qty
    }

    private val _saleCart = MutableStateFlow<List<CartLine>>(emptyList())
    /** Items currently in the checkout cart. */
    val saleCart: StateFlow<List<CartLine>> = _saleCart.asStateFlow()

    private val _salePayment = MutableStateFlow("cash")
    /** "cash" or "credit" — the checkout payment method (web setSalePayment parity). */
    val salePayment: StateFlow<String> = _salePayment.asStateFlow()

    /** Sum of all cart lines (₱). */
    fun getCartTotal(): Double = _saleCart.value.sumOf { it.subtotal }

    /** Number of items across all cart lines (for the count badge). */
    fun getCartLineCount(): Int = _saleCart.value.sumOf { it.qty }

    fun setSalePayment(payment: String) {
        if (payment == "cash" || payment == "credit") _salePayment.value = payment
    }

    /** Add a product to the cart. Same product merges; qty is clamped to stock
     *  (web addToCart parity). Returns true when the item was added/merged. */
    fun addToCart(product: Product, qty: Int): Boolean {
        if (product.quantity <= 0 || qty <= 0) return false
        val current = _saleCart.value.toMutableList()
        val idx = current.indexOfFirst { it.productId == product.id }
        val clamped = qty.coerceAtMost(product.quantity)
        if (idx >= 0) {
            val line = current[idx]
            current[idx] = line.copy(qty = (line.qty + clamped).coerceAtMost(product.quantity))
        } else {
            current.add(
                CartLine(
                    productId = product.id,
                    name = product.name,
                    brand = product.brand,
                    unit = product.unit,
                    packageSize = product.packageSize,
                    sellingPrice = product.sellingPrice,
                    qty = clamped
                )
            )
        }
        _saleCart.value = current
        return true
    }

    /** Adjust a line's qty by [delta] (web cartAdjustQty parity). */
    fun cartAdjustQty(productId: Int, delta: Int) {
        val product = getProductById(productId)
        val current = _saleCart.value.toMutableList()
        val idx = current.indexOfFirst { it.productId == productId }
        if (idx < 0) return
        val line = current[idx]
        val max = product?.quantity?.coerceAtLeast(line.qty) ?: Int.MAX_VALUE
        val newQty = (line.qty + delta).coerceIn(1, max)
        current[idx] = line.copy(qty = newQty)
        _saleCart.value = current
    }

    /** Set a line's qty directly (web cartSetQty parity, clamped to stock). */
    fun cartSetQty(productId: Int, qty: Int) {
        if (qty < 1) return
        val product = getProductById(productId)
        val current = _saleCart.value.toMutableList()
        val idx = current.indexOfFirst { it.productId == productId }
        if (idx < 0) return
        val line = current[idx]
        val max = product?.quantity?.coerceAtLeast(line.qty) ?: Int.MAX_VALUE
        current[idx] = line.copy(qty = qty.coerceAtMost(max))
        _saleCart.value = current
    }

    /** Remove one line from the cart (web cartRemoveLine parity). */
    fun cartRemoveLine(productId: Int) {
        _saleCart.value = _saleCart.value.filter { it.productId != productId }
    }

    fun clearCart() {
        _saleCart.value = emptyList()
    }

    /**
     * Complete the whole cart as ONE transaction (web completeSale parity).
     *
     * - Every sale row shares the same [transactionId] and carries the payment
     *   method, so the Day feed / reports can group items of one purchase.
     * - A credit purchase creates ONE debt entry for the transaction total with
     *   PER-LINE ledger entries (web: one debt per transaction, per-item rows).
     * - Stock is deducted per line.
     * - The credit-limit gate checks the CART TOTAL, not a single item.
     *
     * Returns false (and does nothing) when the cart is empty, a credit sale
     * has no customer name, or the credit limit blocks the sale without [force].
     */
    fun completeSale(customerName: String = "", force: Boolean = false): Boolean {
        val lines = _saleCart.value
        if (lines.isEmpty()) return false
        val total = getCartTotal()
        val isCredit = _salePayment.value == "credit"
        if (isCredit && customerName.isBlank()) return false
        if (isCredit && !force) {
            val cs = getCreditStatus(customerName, total)
            if (cs.overLimit) return false
        }

        // Shared transaction id so all lines read as one purchase.
        val transactionId = System.currentTimeMillis()
        lines.forEach { line ->
            val sale = SpecificSale(
                id = 0, // auto-assigned
                date = today,
                description = line.name,
                amount = line.subtotal,
                quantity = line.qty,
                customerName = if (isCredit) customerName else null,
                profit = (line.sellingPrice - (getProductById(line.productId)?.costPrice ?: 0.0)) * line.qty,
                transactionId = transactionId,
                paymentMethod = if (isCredit) "credit" else "cash"
            )
            addSpecificSale(sale)
            deductStock(line.productId, line.qty)
        }

        // One debt entry per transaction; per-line ledger entries (web parity).
        if (isCredit) {
            val existingDebt = getDebtForName(customerName)
            if (existingDebt != null) {
                addToDebtBalance(existingDebt.id, total)
                lines.forEach { line ->
                    addDebtTransaction(existingDebt.id, "debt", line.name, line.subtotal)
                }
            } else {
                val newDebt = addDebt(
                    CustomerDebt(
                        id = 0,
                        customerName = customerName,
                        amount = total,
                        remainingBalance = total
                    )
                )
                lines.forEach { line ->
                    addDebtTransaction(newDebt.id, "debt", line.name, line.subtotal)
                }
            }
        }

        clearCart()
        return true
    }

    // ── Credit-limit engine (web v2.56/v2.57 parity) ────────────────────

    /** Global default credit limit (₱). 0 = no limit. Falls back to 500. */
    fun getDefaultCreditLimit(): Int = appSettings?.defaultCreditLimit ?: 500

    /** Store name from Settings (used in SMS messages). */
    fun getStoreName(): String = appSettings?.storeName ?: "My Store"

    /** The debt record for a customer name — active (balance > 0) first, else
     *  a settled record, else null. Mirrors web getDebtForName(). */
    fun getDebtForName(name: String): CustomerDebt? {
        if (name.isBlank()) return null
        val lower = name.trim().lowercase()
        var settled: CustomerDebt? = null
        _debts.value.forEach { d ->
            if (d.customerName.trim().lowercase() == lower) {
                if (d.remainingBalance > 0) return d
                if (settled == null) settled = d
            }
        }
        return settled
    }

    /** Effective limit for a customer: per-customer override wins, else global
     *  default (0 = no limit). Mirrors web getEffectiveCreditLimit(). */
    fun getEffectiveCreditLimit(name: String): Int {
        val debt = getDebtForName(name)
        val custom = debt?.creditLimit
        return if (custom != null && custom >= 0) custom else getDefaultCreditLimit()
    }

    /** Credit status for a name given a prospective purchase. At-or-above the
     *  limit blocks a credit sale; near-limit warns at >=80% (web v2.57: at-or-above
     *  with a half-cent epsilon so float drift can't slip an at-limit sale through). */
    fun getCreditStatus(name: String, prospective: Double): CreditStatus {
        val limit = getEffectiveCreditLimit(name)
        val lower = name.trim().lowercase()
        val balance = _debts.value
            .filter { it.customerName.trim().lowercase() == lower && it.remainingBalance > 0 }
            .sumOf { it.remainingBalance }
        val total = balance + prospective
        val atLimit = limit > 0 && Math.abs(total - limit) < 0.005
        val overLimit = limit > 0 && total >= limit - 0.005
        val nearLimit = limit > 0 && !overLimit && total >= limit * 0.8
        return CreditStatus(
            limit = limit,
            balance = balance,
            total = total,
            overLimit = overLimit,
            atLimit = atLimit,
            nearLimit = nearLimit
        )
    }

    /** Set (or clear) a customer's per-customer credit limit. null = use default. */
    fun updateDebtCreditLimit(debtId: Int, limit: Int?) {
        val updated = _debts.value.toMutableList()
        val index = updated.indexOfFirst { it.id == debtId }
        if (index >= 0) {
            updated[index] = updated[index].copy(creditLimit = limit)
            _debts.value = updated
        }
    }

    /** Update a customer's phone number (SMS feature). */
    fun updateDebtPhoneNumber(debtId: Int, phoneNumber: String) {
        val updated = _debts.value.toMutableList()
        val index = updated.indexOfFirst { it.id == debtId }
        if (index >= 0) {
            updated[index] = updated[index].copy(phoneNumber = phoneNumber)
            _debts.value = updated
        }
    }

    /** Update a customer's SMS opt-in preference. */
    fun updateDebtSmsOptIn(debtId: Int, optIn: Boolean) {
        val updated = _debts.value.toMutableList()
        val index = updated.indexOfFirst { it.id == debtId }
        if (index >= 0) {
            updated[index] = updated[index].copy(smsOptIn = optIn)
            _debts.value = updated
        }
    }

    /** Number of customers whose TOTAL outstanding balance is at-or-above their
     *  effective credit limit (name-grouped across multiple debt records, web
     *  v2.56/v2.57 parity). 0 when a limit is 0 (no limit). */
    fun getOverLimitDebtorCount(): Int {
        val nameTotals = mutableMapOf<String, Double>()
        _debts.value.filter { it.remainingBalance > 0 }.forEach { d ->
            nameTotals[d.customerName] = (nameTotals[d.customerName] ?: 0.0) + d.remainingBalance
        }
        return nameTotals.count { (name, total) ->
            val limit = getEffectiveCreditLimit(name)
            limit > 0 && total >= limit - 0.005
        }
    }

    fun recordDebtPayment(debtId: Int, amount: Double, note: String? = null) {
        val updated = _debts.value.toMutableList()
        val index = updated.indexOfFirst { it.id == debtId }
        if (index >= 0) {
            val debt = updated[index]
            updated[index] = debt.copy(
                remainingBalance = debt.remainingBalance - amount
            )
            _debts.value = updated
        }
        // Create a payment record
        _paymentIdCounter++
        val payment = DebtPayment(
            id = _paymentIdCounter,
            debtId = debtId,
            amount = amount,
            timestamp = System.currentTimeMillis(),
            note = note
        )
        _payments.value = _payments.value + payment
    }

    /** Record a debt-balance increase in the ledger (web transactions[] parity). */
    fun addDebtTransaction(debtId: Int, type: String, description: String, amount: Double, timestamp: Long = System.currentTimeMillis()) {
        _debtTxIdCounter++
        _debtTransactions.value = _debtTransactions.value + DebtTransaction(
            id = _debtTxIdCounter,
            debtId = debtId,
            type = type,
            description = description,
            amount = amount,
            timestamp = timestamp
        )
    }

    /** Ledger entries for one debt, chronological (web transactions[]). */
    fun getDebtTransactionsForDebt(debtId: Int): List<DebtTransaction> =
        _debtTransactions.value.filter { it.debtId == debtId }.sortedBy { it.timestamp }

    /**
     * Complete the end-of-day closing.
     * Sets dayOpen = false but keeps dayArchived = false so data is still editable.
     * Overwrites today's history entry if one already exists.
     */
    fun completeEndOfDay(actualSales: Double = _dailyEntry.value?.earnings ?: 0.0) {
        val recordedSales = todayRecordedSales
        val salesDiff = actualSales - recordedSales
        val profit = todayProfit // Use per-sale profit (matching web app getTodayProfit())
        // V2.71: store the day's expenses + Net Profit snapshot (web completeDay parity)
        val expenses = todayExpensesTotal
        val netProfit = profit - expenses
        _endOfDayData.value = EndOfDayData(
            date = today,
            cashInDrawer = actualSales,
            stockCheckDone = true,
            debtPaymentsDone = true,
            finished = true,
            recordedSales = recordedSales,
            actualSales = actualSales,
            salesDiff = salesDiff,
            profit = profit,
            expenses = expenses,
            netProfit = netProfit
        )
        dayOpen = false
        dayArchived = false // Keep data available for editing
        dayDate = today
        persistDayState()
    }

    /**
     * Re-open closing for editing.
     * Restores today's expenses and earnings from the EOD history entry,
     * then sets dayOpen = true so the user can edit closing values.
     */
    fun reopenClosing() {
        val eod = _endOfDayData.value
        if (eod != null && eod.date == today) {
            // Restore actual sales from saved EOD data
            // The closing screen will pre-fill from this state
            dayDate = today
            dayOpen = true
            dayArchived = false
            persistDayState()
        }
    }

    /**
     * Archive today's sales to the history entry before starting a new day.
     * Copies today's specific sales into a structured snapshot and clears
     * them from the active sales list.
     */
    fun archiveDaySales() {
        if (dayDate.isBlank()) return
        val salesForDate = _specificSales.value.filter { it.date == dayDate }
        if (salesForDate.isNotEmpty()) {
            val existingEod = _endOfDayData.value
            if (existingEod != null && existingEod.date == dayDate) {
                // Archive into existing EOD entry
                val archivedProfit = salesForDate.sumOf { it.profit }
                val archivedSalesTotal = salesForDate.sumOf { it.amount }
                _endOfDayData.value = existingEod.copy(
                    recordedSales = existingEod.recordedSales.coerceAtLeast(archivedSalesTotal),
                    profit = existingEod.profit.coerceAtLeast(archivedProfit)
                )
            }
            // Remove archived sales from active list
            _specificSales.value = _specificSales.value.filter { it.date != dayDate }
        }
        dayArchived = true
        persistDayState()
    }

    fun resetTodaySales() {
        _dailyEntry.value = null
        _specificSales.value = _specificSales.value.filter { it.date != today }
        _endOfDayData.value = null
    }

    /**
     * Start a fresh business day from the Dev Panel.
     * Archives current day's sales to history, resets today's in-memory data,
     * and initializes a pre-opening state so the Morning page shows "Start the Day".
     */
    fun startNewDay() {
        archiveDaySales()       // Copy today's sales to history
        resetTodaySales()        // Clear daily entry, specific sales, and EOD data
        dayOpen = false
        dayDate = today          // Set to today so morning page can detect a fresh day
        dayArchived = true       // Sales were archived → "Edit Closing" condition won't match
        persistDayState()
    }

    fun resetAllData() {
        _products.value = emptyList()
        _dailyEntry.value = null
        _specificSales.value = emptyList()
        _debts.value = emptyList()
        _payments.value = emptyList()
        _debtTransactions.value = emptyList()
        _expenses.value = emptyList()
        _endOfDayData.value = null
        _reportPeriod.value = "day" // web parity: resetData() also resets the persisted period
        _productIdCounter = 10
        _saleIdCounter = 3
        _debtIdCounter = 4
        _paymentIdCounter = 10
        _debtTxIdCounter = 100
        _expenseIdCounter = 100
        dayOpen = false
        dayDate = ""
        dayArchived = false
        devDateOverride = "" // factory reset also clears the temporary dev override
        devDaySnapshot = null // and its pre-test snapshot
        devTimeOverride = null // ...and the temporary dev time override
        persistDayState()
    }

    // ── Business Tip Logic (mirrors web prototype 6-priority system + enhancements) ──
    data class BusinessTip(val message: String, val priority: Int)

    /**
     * Rotating generic tips for when everything is caught up.
     * Matches the web prototype's rotating tip system.
     */
    private val rotatingTips = listOf(
        "💡 Tip: Buying in bulk usually gets you a 10-20% discount from suppliers. Save more by stocking up on fast-moving items!",
        "💡 Tip: Check your inventory every morning to know what's running low before your customers ask.",
        "💡 Tip: Offer small discounts for cash payments instead of utang. This improves your cash flow!",
        "💡 Tip: Keep a notebook of which products sell fastest. Focus your restocking budget on those items.",
        "💡 Tip: Review your weekly profit trends every Monday. This helps you spot which products earn the most.",
        "💡 Tip: Set aside 20% of your daily earnings for savings. This builds a safety net for emergencies.",
    )

    fun getBusinessTip(): BusinessTip {
        val outOfStock = _products.value.filter { it.status == StockStatus.OUT_OF_STOCK }
        val lowStock = _products.value.filter { it.status == StockStatus.LOW }
        val hasDailyEntry = _dailyEntry.value?.date == today
        val debtsExist = totalOutstandingDebts > 0
        val eodDone = isEodComplete
        val todaySales = _specificSales.value.filter { it.date == today }

        // Priority 1: Out-of-stock items
        if (outOfStock.isNotEmpty()) {
            val names = outOfStock.take(3).joinToString(", ") { it.name }
            val suffix = if (outOfStock.size > 3) " +${outOfStock.size - 3} more" else ""
            return BusinessTip("⚠ Out of stock: $names$suffix. Restock immediately!", 1)
        }

        // Priority 2: Low-stock items
        if (lowStock.isNotEmpty()) {
            val names = lowStock.take(3).joinToString(", ") { "${it.name} (${it.quantity} left)" }
            val suffix = if (lowStock.size > 3) " +${lowStock.size - 3} more" else ""
            return BusinessTip("📦 Running low: $names$suffix. Consider restocking soon.", 2)
        }

        // Priority 3: Sales not recorded today
        if (!hasDailyEntry) {
            // Include pending profit awareness
            val todayUtangSales = todaySales.filter { it.customerName != null }
            val todayUtangTotal = todaySales.sumOf { it.amount } - todaySales.filter { it.customerName == null }.sumOf { it.amount }
            if (todaySales.isNotEmpty() && todayUtangTotal > 0) {
                return BusinessTip("📝 You have ₱${String.format("%,.2f", todayUtangTotal)} in utang sales today — record your daily earnings to see your real cash profit!", 3)
            }
            return BusinessTip("📝 Sales not recorded today. Tap the Earnings card to record your daily sales.", 3)
        }

        // Priority 4: Outstanding debts
        if (debtsExist) {
            return BusinessTip("📌 You have $activeDebtorCount debtor(s) with a total of ₱${String.format("%,.2f", totalOutstandingDebts)} outstanding.", 4)
        }

        // Priority 5: Sales recorded but EOD not done
        if (hasDailyEntry && !eodDone) {
            // Add pending profit awareness when profit is constrained by utang
            val todayUtangSales = todaySales.filter { it.customerName != null }
            val todayUtangTotal = todayUtangSales.sumOf { it.amount }
            val dailyEarnings = _dailyEntry.value?.earnings ?: 0.0
            if (dailyEarnings > 0 && todayUtangTotal > 0) {
                val utangPercentage = (todayUtangTotal / dailyEarnings) * 100
                if (utangPercentage >= 50) {
                    return BusinessTip("⚠ Over 50% of your earnings (₱${String.format("%,.2f", todayUtangTotal)}) is still in utang. Record payments to free up your cash flow.", 5)
                } else if (utangPercentage >= 20) {
                    return BusinessTip("📌 About ${String.format("%.0f", utangPercentage)}% of your earnings is still in utang. Follow up with debtors to keep cash flowing.", 5)
                }
            }
            return BusinessTip("🏁 Sales recorded! Complete your End-of-Day closing to finalize.", 5)
        }

        // Priority 6: All caught up — show rotating tip
        // Sales trend analysis: compare today's specific sales to 7-day average
        val todaySaleTotal = todaySales.sumOf { it.amount }
        val sevenDaysAgo = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            .format(Date(System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000))
        val weekSales = _specificSales.value.filter { it.date >= sevenDaysAgo }
        val weekSaleCount = weekSales.size.coerceAtLeast(1)
        val avgSaleTotal = weekSales.sumOf { it.amount } / weekSaleCount * todaySales.size.coerceAtLeast(1)

        // Only show trend when there are enough data points (3+ sales in the last 7 days)
        if (todaySaleTotal > 0 && weekSales.size >= 3 && avgSaleTotal > 0) {
            val diffPercent = ((todaySaleTotal - avgSaleTotal) / avgSaleTotal * 100).toInt()
            if (diffPercent > 10) {
                val prefix = if (_tipRotationIndex % 3 == 0) "📈" else "🌟"
                _tipRotationIndex = (_tipRotationIndex + 1) % rotatingTips.size
                return BusinessTip("$prefix Your sales are $diffPercent% higher than your recent average! Great momentum today. 🎉", 6)
            } else if (diffPercent < -10) {
                _tipRotationIndex = (_tipRotationIndex + 1) % rotatingTips.size
                return BusinessTip("📊 Your sales today are ${-diffPercent}% lower than your recent average. Check if you're missing any items.", 6)
            }
        }

        // Show rotating generic tip
        val tip = rotatingTips[_tipRotationIndex % rotatingTips.size]
        _tipRotationIndex = (_tipRotationIndex + 1) % rotatingTips.size
        return BusinessTip(tip, 6)
    }

    // ── Dev Panel Actions (Phase 1, adaptation_plan2) ────────────────────

    fun getRawStateJson(): JSONObject {
        return JSONObject().apply {
            put("products", org.json.JSONArray(_products.value.map { p ->
                JSONObject().apply {
                    put("id", p.id); put("name", p.name)
                    put("quantity", p.quantity); put("costPrice", p.costPrice)
                    put("sellingPrice", p.sellingPrice); put("unit", p.unit)
                    put("lowStockThreshold", p.lowStockThreshold)
                    // v2.59 parity: identity fields round-trip with the data
                    put("category", p.category); put("subcategory", p.subcategory); put("brand", p.brand)
                    put("packageSize", p.packageSize)
                }
            }))
            put("dailyEntry", _dailyEntry.value?.let { de ->
                JSONObject().apply {
                    put("date", de.date); put("stockExpenses", de.stockExpenses)
                    put("earnings", de.earnings)
                }
            } ?: org.json.JSONObject.NULL)
            put("specificSales", org.json.JSONArray(_specificSales.value.map { s ->
                JSONObject().apply {
                    put("id", s.id); put("date", s.date); put("description", s.description)
                    put("amount", s.amount); put("quantity", s.quantity)
                    if (s.customerName != null) put("customerName", s.customerName) else put("customerName", org.json.JSONObject.NULL)
                    put("profit", s.profit)
                    put("transactionId", s.transactionId)
                    if (s.paymentMethod != null) put("paymentMethod", s.paymentMethod) else put("paymentMethod", org.json.JSONObject.NULL)
                }
            }))
            put("debts", org.json.JSONArray(_debts.value.map { d ->
                JSONObject().apply {
                    put("id", d.id); put("customerName", d.customerName)
                    put("amount", d.amount); put("remainingBalance", d.remainingBalance)
                    put("createdAt", d.createdAt)
                    if (d.creditLimit != null) put("creditLimit", d.creditLimit)
                }
            }))
            put("payments", org.json.JSONArray(_payments.value.map { p ->
                JSONObject().apply {
                    put("id", p.id); put("debtId", p.debtId); put("amount", p.amount)
                    put("timestamp", p.timestamp); put("note", p.note ?: org.json.JSONObject.NULL)
                }
            }))
            put("debtTransactions", org.json.JSONArray(_debtTransactions.value.map { tx ->
                JSONObject().apply {
                    put("id", tx.id); put("debtId", tx.debtId); put("type", tx.type)
                    put("description", tx.description ?: org.json.JSONObject.NULL)
                    put("amount", tx.amount); put("timestamp", tx.timestamp)
                }
            }))
            put("expenses", org.json.JSONArray(_expenses.value.map { e ->
                JSONObject().apply {
                    put("id", e.id); put("date", e.date); put("category", e.category)
                    put("amount", e.amount); put("note", e.note)
                    put("timestamp", e.timestamp)
                }
            }))
            put("lowStockCount", lowStockCount)
            put("outOfStockCount", outOfStockCount)
            put("totalOutstandingDebts", totalOutstandingDebts)
        }
    }

    fun importData(obj: JSONObject) {
        // Products
        if (obj.has("products")) {
            val arr = obj.getJSONArray("products")
            val products = mutableListOf<Product>()
            for (i in 0 until arr.length()) {
                val p = arr.getJSONObject(i)
                products.add(Product(
                    id = p.optInt("id", _productIdCounter + i + 1),
                    name = p.getString("name"),
                    quantity = p.optInt("quantity", 0),
                    costPrice = p.optDouble("costPrice", 0.0),
                    sellingPrice = p.optDouble("sellingPrice", 0.0),
                    unit = p.optString("unit", "piece"),
                    lowStockThreshold = p.optInt("lowStockThreshold", 5),
                    category = p.optString("category", ""),
                    subcategory = p.optString("subcategory", ""),
                    brand = p.optString("brand", ""),
                    packageSize = p.optString("packageSize", "")
                ))
            }
            if (products.isNotEmpty()) _products.value = products
        }
        // Daily Entry
        if (obj.has("dailyEntry") && !obj.isNull("dailyEntry")) {
            val de = obj.getJSONObject("dailyEntry")
            _dailyEntry.value = DailyEntry(
                date = de.optString("date", today),
                stockExpenses = de.optDouble("stockExpenses", 0.0),
                earnings = de.optDouble("earnings", 0.0)
            )
        }
        // Specific Sales
        if (obj.has("specificSales")) {
            val arr = obj.getJSONArray("specificSales")
            val sales = mutableListOf<SpecificSale>()
            for (i in 0 until arr.length()) {
                val s = arr.getJSONObject(i)
                val customerName = if (s.isNull("customerName")) null else s.optString("customerName", null)
                sales.add(SpecificSale(
                    id = s.optInt("id", _saleIdCounter + i + 1),
                    date = s.optString("date", today),
                    description = s.optString("description", ""),
                    amount = s.optDouble("amount", 0.0),
                    quantity = s.optInt("quantity", 1),
                    customerName = customerName,
                    profit = s.optDouble("profit", 0.0),
                    transactionId = s.optLong("transactionId", 0),
                    paymentMethod = if (s.isNull("paymentMethod")) null else s.optString("paymentMethod", null)
                ))
            }
            if (sales.isNotEmpty()) _specificSales.value = sales
        }
        // Debts
        if (obj.has("debts")) {
            val arr = obj.getJSONArray("debts")
            val debts = mutableListOf<CustomerDebt>()
            for (i in 0 until arr.length()) {
                val d = arr.getJSONObject(i)
                debts.add(CustomerDebt(
                    id = d.optInt("id", _debtIdCounter + i + 1),
                    customerName = d.optString("customerName", ""),
                    amount = d.optDouble("amount", 0.0),
                    remainingBalance = d.optDouble("remainingBalance", 0.0),
                    createdAt = d.optLong("createdAt", System.currentTimeMillis()),
                    creditLimit = if (d.has("creditLimit") && !d.isNull("creditLimit")) {
                        d.optInt("creditLimit", -1).takeIf { it >= 0 }
                    } else null
                ))
            }
            if (debts.isNotEmpty()) _debts.value = debts
        }
        // Payments
        if (obj.has("payments")) {
            val arr = obj.getJSONArray("payments")
            val payments = mutableListOf<DebtPayment>()
            for (i in 0 until arr.length()) {
                val p = arr.getJSONObject(i)
                payments.add(DebtPayment(
                    id = p.optInt("id", _paymentIdCounter + i + 1),
                    debtId = p.optInt("debtId", 0),
                    amount = p.optDouble("amount", 0.0),
                    timestamp = p.optLong("timestamp", System.currentTimeMillis()),
                    note = p.optString("note", null)
                ))
            }
            if (payments.isNotEmpty()) _payments.value = payments
        }
        // Debt Transactions
        if (obj.has("debtTransactions")) {
            val arr = obj.getJSONArray("debtTransactions")
            val txs = mutableListOf<DebtTransaction>()
            for (i in 0 until arr.length()) {
                val t = arr.getJSONObject(i)
                txs.add(DebtTransaction(
                    id = t.optInt("id", _debtTxIdCounter + i + 1),
                    debtId = t.optInt("debtId", 0),
                    type = t.optString("type", "debt"),
                    description = if (t.isNull("description")) null else t.optString("description", null),
                    amount = t.optDouble("amount", 0.0),
                    timestamp = t.optLong("timestamp", System.currentTimeMillis())
                ))
            }
            if (txs.isNotEmpty()) {
                _debtTransactions.value = txs
                val maxTxId = txs.maxOfOrNull { it.id } ?: 100
                if (maxTxId > _debtTxIdCounter) _debtTxIdCounter = maxTxId
            }
        }
        // Expenses (web V2.71 parity)
        if (obj.has("expenses")) {
            val arr = obj.getJSONArray("expenses")
            val expenses = mutableListOf<Expense>()
            for (i in 0 until arr.length()) {
                val e = arr.getJSONObject(i)
                expenses.add(Expense(
                    id = e.optInt("id", _expenseIdCounter + i + 1),
                    date = e.optString("date", today),
                    category = e.optString("category", "other"),
                    amount = e.optDouble("amount", 0.0),
                    note = e.optString("note", ""),
                    timestamp = e.optLong("timestamp", System.currentTimeMillis())
                ))
            }
            if (expenses.isNotEmpty()) {
                _expenses.value = expenses
                val maxExpenseId = expenses.maxOfOrNull { it.id } ?: 100
                if (maxExpenseId > _expenseIdCounter) _expenseIdCounter = maxExpenseId
            }
        }
        // Web loadState parity: backfill an initial ledger row for imported debts
        // that have none, so history + running balance stay consistent.
        val importedDebts = _debts.value
        val ledgerIds = _debtTransactions.value.map { it.debtId }.toSet()
        val missing = importedDebts.filter { it.id !in ledgerIds }
        if (missing.isNotEmpty()) {
            val newTxs = _debtTransactions.value.toMutableList()
            missing.forEach { debt ->
                _debtTxIdCounter++
                newTxs.add(DebtTransaction(
                    id = _debtTxIdCounter,
                    debtId = debt.id,
                    type = "debt",
                    description = null,
                    amount = debt.amount,
                    timestamp = debt.createdAt
                ))
            }
            _debtTransactions.value = newTxs
        }
    }

    // ── Automatic backup integration (V3.0) ──────────────────────────────

    /** Build the canonical versioned backup envelope from persisted Room data.
     *  Used by automatic backups, "Back Up Now", and the Settings export.
     *  Falls back to the in-memory snapshot before the repository loads. */
    suspend fun buildBackupJson(): JSONObject {
        val repo = repository
        val settings = appSettings
        return if (repo != null && settings != null) {
            BackupManager.buildFromRepository(repo, settings)
        } else {
            getRawStateJson()
        }
    }

    /** Run a manual backup to the configured location (V3.0). */
    fun updateBackupSettings(enabled: Boolean, intervalHours: Int, locationUri: String) {
        _backupEnabled.value = enabled
        appSettings?.backupEnabled = enabled
        _backupIntervalHours.value = intervalHours
        appSettings?.backupIntervalHours = intervalHours
        _backupLocationUri.value = locationUri
        appSettings?.backupLocationUri = locationUri
    }


    suspend fun backupNow(
        context: android.content.Context,
        manual: Boolean = true
    ): BackupResult {
        val repo = repository ?: return BackupResult(false, error = "not_ready")
        val settings = appSettings ?: return BackupResult(false, error = "not_ready")
        return BackupManager.createBackup(context.applicationContext, repo, settings, manual)
    }

    /**
     * Restore a backup file (V3.0): takes a pre-restore safety snapshot, clears
     * the Room tables, then applies the parsed data + settings. Executes on the
     * ViewModel scope and reports success/failure through [onResult].
     */
    fun restoreBackup(
        context: android.content.Context,
        json: JSONObject,
        onResult: (Boolean) -> Unit
    ) {
        viewModelScope.launch {
            try {
                val parsed = BackupSerializer.parse(json)
                val repo = repository
                val settings = appSettings
                if (repo != null && settings != null) {
                    // Best-effort safety snapshot before we overwrite anything.
                    BackupManager.createBackup(
                        context.applicationContext, repo, settings,
                        manual = true, preRestore = true
                    )
                    repo.deleteAll()
                    applyBackupData(parsed.data, replaceAll = true)
                    persistAllToRepo(repo)
                    parsed.data.restockLogs.forEach { repo.saveRestockLog(it) }
                } else {
                    applyBackupData(parsed.data, replaceAll = true)
                }
                onResult(true)
            } catch (e: Exception) {
                onResult(false)
            }
        }
    }

    /**
     * Apply a parsed backup to the in-memory state, counters and settings.
     * [replaceAll] = true reproduces the backup exactly (empty sections clear
     * data); false keeps existing data for empty sections (legacy import).
     */
    private fun applyBackupData(
        data: BackupSerializer.BackupData,
        replaceAll: Boolean
    ) {
        if (replaceAll || data.products.isNotEmpty()) {
            _products.value = data.products
            _productIdCounter = maxOf(_productIdCounter, data.products.maxOfOrNull { it.id } ?: 0)
        }
        if (replaceAll) _dailyEntry.value = data.dailyEntry
        else data.dailyEntry?.let { _dailyEntry.value = it }

        if (replaceAll || data.specificSales.isNotEmpty()) {
            _specificSales.value = data.specificSales
            _saleIdCounter = maxOf(_saleIdCounter, data.specificSales.maxOfOrNull { it.id } ?: 0)
        }
        if (replaceAll || data.debts.isNotEmpty()) {
            _debts.value = data.debts
            _debtIdCounter = maxOf(_debtIdCounter, data.debts.maxOfOrNull { it.id } ?: 0)
        }
        if (replaceAll || data.payments.isNotEmpty()) {
            _payments.value = data.payments
            _paymentIdCounter = maxOf(_paymentIdCounter, data.payments.maxOfOrNull { it.id } ?: 0)
        }
        if (replaceAll || data.debtTransactions.isNotEmpty()) {
            _debtTransactions.value = data.debtTransactions
            _debtTxIdCounter = maxOf(_debtTxIdCounter, data.debtTransactions.maxOfOrNull { it.id } ?: 0)
        }
        if (replaceAll || data.expenses.isNotEmpty()) {
            _expenses.value = data.expenses
            _expenseIdCounter = maxOf(_expenseIdCounter, data.expenses.maxOfOrNull { it.id } ?: 0)
        }
        if (replaceAll || data.restockLogs.isNotEmpty()) {
            _restockLog.value = data.restockLogs
            _lastRestockDate.value = data.restockLogs.maxByOrNull { it.date }?.date
        }
        if (replaceAll) _endOfDayData.value = data.endOfDay
        else data.endOfDay?.let { _endOfDayData.value = it }

        applyBackupSettings(data.settings)
        backfillDebtLedger()
    }

    /** Apply the allowlisted settings snapshot and re-persist day state. */
    private fun applyBackupSettings(b: BackupSerializer.BackupSettings) {
        appSettings?.let { BackupSerializer.applySettings(it, b) }
        dayOpen = b.dayOpen
        dayDate = b.dayDate
        dayArchived = b.dayArchived
        _reportPeriod.value = b.reportPeriod
        persistDayState()
    }

    /** Web loadState parity: give every debt an initial ledger row when missing. */
    private fun backfillDebtLedger() {
        val ledgerIds = _debtTransactions.value.map { it.debtId }.toSet()
        val missing = _debts.value.filter { it.id !in ledgerIds }
        if (missing.isEmpty()) return
        val newTxs = _debtTransactions.value.toMutableList()
        missing.forEach { debt ->
            _debtTxIdCounter++
            newTxs.add(
                DebtTransaction(
                    id = _debtTxIdCounter,
                    debtId = debt.id,
                    type = "debt",
                    description = null,
                    amount = debt.amount,
                    timestamp = debt.createdAt
                )
            )
        }
        _debtTransactions.value = newTxs
    }

    fun generateTestSale() {
        val prods = _products.value
        if (prods.isEmpty()) {
            // Sample-data auto-seed was removed; nothing to test against.
            return
        }
        val rand = java.util.Random()
        val product = prods[rand.nextInt(prods.size)]
        val qty = rand.nextInt(5) + 1
        val hasCustomer = rand.nextBoolean()
        val customerName = if (hasCustomer && _debts.value.isNotEmpty()) {
            _debts.value[rand.nextInt(_debts.value.size)].customerName
        } else null

        val amount = product.sellingPrice * qty
        val profit = (product.sellingPrice - product.costPrice) * qty

        _saleIdCounter++
        val sale = SpecificSale(
            id = _saleIdCounter,
            date = today,
            description = "${product.name} (test)",
            amount = amount,
            quantity = qty,
            customerName = customerName,
            profit = profit
        )
        val updated = _specificSales.value.toMutableList()
        updated.add(0, sale)
        _specificSales.value = updated

        // Deduct stock
        deductStock(product.id, qty)

        // If customer, create debt + record ledger entry (web saveSale parity)
        if (customerName != null) {
            addToDebtBalance(_debts.value[0].id, amount)
            addDebtTransaction(_debts.value[0].id, "debt", product.name, amount)
        }
    }

    fun generateTestDebts(): Int {
        val rand = java.util.Random()
        val names = listOf("Aling Nena", "Mang Kanor", "Teresa", "Bong", "Liza", "Rolly", "Elena", "Pedro")
        val count = rand.nextInt(4) + 2 // 2-5 debts
        val now = System.currentTimeMillis()
        val day = 86_400_000L

        val newDebts = _debts.value.toMutableList()
        val newPayments = _payments.value.toMutableList()
        val newTxs = _debtTransactions.value.toMutableList()
        for (i in 0 until count) {
            _debtIdCounter++
            val name = names[rand.nextInt(names.size)]
            val amount = (rand.nextInt(46) + 5) * 10.0 // 50-500 in steps of 10
            val isSettled = i == 0 && rand.nextBoolean()
            val debtId = _debtIdCounter
            newDebts.add(CustomerDebt(
                id = debtId,
                customerName = name,
                amount = amount,
                remainingBalance = if (isSettled) 0.0 else amount,
                createdAt = now - (i + 1) * day
            ))
            // Initial ledger entry (web: initial transactions[] row)
            _debtTxIdCounter++
            newTxs.add(DebtTransaction(
                id = _debtTxIdCounter,
                debtId = debtId,
                type = "debt",
                description = "Test debt",
                amount = amount,
                timestamp = now - (i + 1) * day
            ))
            if (isSettled) {
                _paymentIdCounter++
                newPayments.add(DebtPayment(
                    id = _paymentIdCounter,
                    debtId = debtId,
                    amount = amount,
                    timestamp = now - i * day + 3600_000,
                    note = "Fully paid"
                ))
            }
        }
        _debts.value = newDebts
        _payments.value = newPayments
        _debtTransactions.value = newTxs
        return count
    }

    /**
     * Generates one month (30 days) of realistic test data spread across the calendar.
     */
    fun generateMonthOfTestData(): String {
        val rand = java.util.Random()
        val now = System.currentTimeMillis()
        val dayMs = 24L * 60 * 60 * 1000
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val names = listOf("Aling Nena", "Mang Kanor", "Teresa", "Bong", "Liza", "Rolly", "Elena", "Pedro")
        val categories = ExpenseCatalog.CATEGORIES

        // Sample-data auto-seed was removed; the empty guard below handles it.
        val pList = _products.value
        if (pList.isEmpty()) return "No products available"

        var sCount = 0; var dCount = 0; var pPay = 0; var eCount = 0

        for (day in 0..29) {
            val dayStart = now - (day * dayMs)
            val dayStartMs = fmt.parse(fmt.format(Date(dayStart)))?.time ?: dayStart

            // Sales (2-5)
            repeat(2 + rand.nextInt(4)) { i ->
                val p = pList[rand.nextInt(pList.size)]
                val qty = 1 + rand.nextInt(4)
                val amount = p.sellingPrice * qty
                val ts = dayStartMs + (7 + rand.nextInt(14)) * 3600000L + rand.nextInt(3600000)
                val hasCust = rand.nextDouble() < 0.2
                val cName = if (hasCust) names[rand.nextInt(names.size)] else null

                _saleIdCounter++
                val sale = SpecificSale(
                    id = _saleIdCounter,
                    date = fmt.format(Date(ts)),
                    description = "${p.name} (test)",
                    amount = amount,
                    quantity = qty,
                    customerName = cName,
                    profit = (p.sellingPrice - p.costPrice) * qty,
                    timestamp = ts
                )
                _specificSales.value = listOf(sale) + _specificSales.value
                deductStock(p.id, qty)

                if (cName != null) {
                    val existing = _debts.value.find { it.customerName == cName }
                    val dId = existing?.id ?: run {
                        _debtIdCounter++
                        val newD = CustomerDebt(id = _debtIdCounter, customerName = cName, amount = amount, remainingBalance = amount, createdAt = ts)
                        _debts.value = listOf(newD) + _debts.value
                        _debtIdCounter
                    }
                    addToDebtBalance(dId, amount)
                    _debtTxIdCounter++
                    _debtTransactions.value = listOf(DebtTransaction(id = _debtTxIdCounter, debtId = dId, type = "debt", description = p.name, amount = amount, timestamp = ts)) + _debtTransactions.value
                    dCount++
                }
                sCount++
            }

            // Expenses (1-3)
            repeat(1 + rand.nextInt(3)) {
                val cat = categories[rand.nextInt(categories.size)]
                val amount = (30 + rand.nextInt(300)).toDouble()
                val ts = dayStartMs + (8 + rand.nextInt(12)) * 3600000L
                _expenseIdCounter++
                val exp = Expense(id = _expenseIdCounter, date = fmt.format(Date(ts)), category = cat, amount = amount, note = "Test expense", timestamp = ts)
                _expenses.value = _expenses.value + exp
                eCount++
            }
        }
        return "Generated: $sCount sales, $dCount debts, $eCount expenses over 30 days"
    }

    fun bulkAddItems(): Int {
        val rand = java.util.Random()
        val names = listOf(
            "Biscuit Pack", "Candle Pack", "Toothpaste", "Soap", "Shampoo 200ml",
            "Candy Pack", "Chips", "Juice Pack", "Noodles Cup", "Canned Corned Beef",
            "Coffee Ground", "Milk 1L", "Bread (Pan de Sal)", "Eggs (per piece)", "Sardines Hot"
        )
        val newProducts = _products.value.toMutableList()
        var added = 0

        names.forEach { name ->
            val costPrice = (rand.nextInt(20) + 5) * 1.0 // 5-25
            val markup = 1.2 + rand.nextDouble() * 0.3 // 1.2x to 1.5x
            val sellingPrice = (costPrice * markup).let { Math.round(it * 10.0) / 10.0 }
            val qty = rand.nextInt(96) + 5 // 5-100

            _productIdCounter++
            newProducts.add(Product(_productIdCounter, name, qty, costPrice, sellingPrice))
            added++
        }
        _products.value = newProducts
        return added
    }

    /**
     * Seeds the full 225-item product catalog (web v2.59+ parity, matches
     * app.js getSampleProducts). Called automatically on first launch when
     * inventory is empty, and available as a manual fallback in the Dev Panel.
     */
    fun seedSampleData() {
        _productIdCounter = 225
        _products.value = listOf(
            p(1, "Coca-Cola Original Taste", "beverages", "Coca-Cola", "bottle", "290ml", 20, 18, 22.5, 6, "soft_drinks"),
            p(2, "Pepsi", "beverages", "Pepsi", "bottle", "330ml", 20, 18, 22.5, 6, "soft_drinks"),
            p(3, "Royal Tru-Orange", "beverages", "Royal", "bottle", "330ml", 0, 18, 22.5, 6, "soft_drinks"),
            p(4, "Sprite", "beverages", "Sprite", "bottle", "330ml", 20, 18, 22.5, 6, "soft_drinks"),
            p(5, "Mountain Dew", "beverages", "Mountain Dew", "bottle", "330ml", 20, 18, 22.5, 6, "soft_drinks"),
            p(6, "RC Cola", "beverages", "RC Cola", "bottle", "330ml", 3, 15, 18.75, 6, "soft_drinks"),
            p(7, "Wilkins Pure", "beverages", "Wilkins", "bottle", "500ml", 20, 10, 13, 6, "bottled_water"),
            p(8, "Absolute Purified Water", "beverages", "Absolute", "bottle", "500ml", 20, 10, 13, 6, "bottled_water"),
            p(9, "Nature’s Spring", "beverages", "Nature’s Spring", "bottle", "500ml", 20, 9, 11.7, 6, "bottled_water"),
            p(10, "Summit Water", "beverages", "Summit", "bottle", "500ml", 0, 10, 13, 6, "bottled_water"),
            p(11, "Viva Mineral Water", "beverages", "Viva", "bottle", "500ml", 4, 9, 11.7, 6, "bottled_water"),
            p(12, "Aquabest Purified Water", "beverages", "Aquabest", "bottle", "500ml", 20, 8, 10.4, 6, "bottled_water"),
            p(13, "Nescafé Classic", "beverages", "Nescafé", "sachet", "25g", 20, 9, 11.7, 6, "coffee_mix"),
            p(14, "Great Taste 3-in-1", "beverages", "Great Taste", "sachet", "25g", 20, 8, 10.4, 6, "coffee_mix"),
            p(15, "Kopiko Brown Coffee", "beverages", "Kopiko", "sachet", "25g", 20, 8, 10.4, 6, "coffee_mix"),
            p(16, "San Mig Coffee 3-in-1", "beverages", "San Mig Coffee", "sachet", "20g", 3, 7, 9.1, 6, "coffee_mix"),
            p(17, "Café Puro", "beverages", "Café Puro", "sachet", "25g", 20, 8, 10.4, 6, "coffee_mix"),
            p(18, "UCC 3-in-1 Coffee", "beverages", "UCC", "sachet", "20g", 20, 10, 13, 6, "coffee_mix"),
            p(19, "Lucky Me! Pancit Canton Original", "instant_dry_goods", "Lucky Me!", "pack", "60g", 20, 11, 13.75, 6, "instant_noodles"),
            p(20, "Payless Pancit Canton", "instant_dry_goods", "Payless", "pack", "60g", 0, 9, 11.25, 6, "instant_noodles"),
            p(21, "Nissin Ramen", "instant_dry_goods", "Nissin", "pack", "55g", 20, 10, 12.5, 6, "instant_noodles"),
            p(22, "QuickChow Pancit Canton", "instant_dry_goods", "QuickChow", "pack", "60g", 20, 9, 11.25, 6, "instant_noodles"),
            p(23, "Ho-Mi Instant Noodles", "instant_dry_goods", "Ho-Mi", "pack", "55g", 4, 8, 10, 6, "instant_noodles"),
            p(24, "Yakisoba Instant Noodles", "instant_dry_goods", "Yakisoba", "pack", "60g", 20, 11, 13.75, 6, "instant_noodles"),
            p(25, "Doña Maria Jasponica", "pantry_staples", "Doña Maria", "sack", "5kg", 20, 360, 414, 2, "rice"),
            p(26, "Dinorado Rice", "pantry_staples", "Dinarado", "sack", "5kg", 20, 330, 379.5, 2, "rice"),
            p(27, "Sinandomeng Rice", "pantry_staples", "Sinandomeng", "sack", "5kg", 20, 300, 345, 2, "rice"),
            p(28, "Maharlika Rice", "pantry_staples", "Maharlika", "sack", "5kg", 0, 320, 368, 2, "rice"),
            p(29, "Jasmine Rice", "pantry_staples", "Jasmine", "sack", "5kg", 1, 350, 402.5, 2, "rice"),
            p(30, "Jasmate Rice", "pantry_staples", "Jasmate", "sack", "5kg", 20, 340, 391, 2, "rice"),
            p(31, "Ligo Sardines in Tomato Sauce", "canned_goods", "Ligo", "can", "155g", 20, 20, 25, 4, "sardines"),
            p(32, "Mega Sardines in Tomato Sauce", "canned_goods", "Mega", "can", "155g", 20, 20, 25, 4, "sardines"),
            p(33, "Young’s Town Sardines", "canned_goods", "Young’s Town", "can", "155g", 20, 18, 22.5, 4, "sardines"),
            p(34, "555 Sardines", "canned_goods", "555", "can", "155g", 20, 21, 26.25, 4, "sardines"),
            p(35, "Argentina Sardines", "canned_goods", "Argentina", "can", "155g", 2, 19, 23.75, 4, "sardines"),
            p(36, "Atami Sardines", "canned_goods", "Atami", "can", "155g", 20, 18, 22.5, 4, "sardines"),
            p(37, "Century Tuna Flakes", "canned_goods", "Century Tuna", "can", "180g", 20, 34, 42.5, 4, "tuna"),
            p(38, "555 Tuna Flakes", "canned_goods", "555", "can", "155g", 20, 28, 35, 4, "tuna"),
            p(39, "Mega Tuna Flakes", "canned_goods", "Mega", "can", "180g", 20, 30, 37.5, 4, "tuna"),
            p(40, "San Marino Tuna Flakes", "canned_goods", "San Marino", "can", "180g", 0, 29, 36.25, 4, "tuna"),
            p(41, "Ligo Tuna Flakes", "canned_goods", "Ligo", "can", "180g", 3, 30, 37.5, 4, "tuna"),
            p(42, "Family’s Choice Tuna", "canned_goods", "Family’s Choice", "can", "180g", 20, 27, 33.75, 4, "tuna"),
            p(43, "Bounty Fresh Chicken Egg", "fresh_section", "Bounty Fresh", "piece", "Large", 20, 9, 10.8, 12, "eggs"),
            p(44, "Magnolia Chicken Egg", "fresh_section", "Magnolia", "piece", "Large", 20, 9.5, 11.4, 12, "eggs"),
            p(45, "Sarimanok Chicken Egg", "fresh_section", "Sarimanok", "piece", "Large", 8, 8.5, 10.2, 12, "eggs"),
            p(46, "Local Farm Chicken Egg", "fresh_section", "Local Farm", "piece", "Medium", 20, 8, 9.6, 12, "eggs"),
            p(47, "Free Range Chicken Egg", "fresh_section", "Free Range Farm", "piece", "Large", 20, 12, 14.4, 12, "eggs"),
            p(48, "Organic Chicken Egg", "fresh_section", "Organic Farm", "piece", "Large", 20, 13, 15.6, 12, "eggs"),
            p(49, "Gardenia Pinoy Tasty", "pantry_staples", "Gardenia", "loaf", "400g", 20, 45, 54, 4, "bread"),
            p(50, "Gardenia Classic White Bread", "pantry_staples", "Gardenia", "loaf", "400g", 20, 48, 57.6, 4, "bread"),
            p(51, "Pinoy Tasty White Bread", "pantry_staples", "Pinoy Tasty", "loaf", "450g", 0, 40, 48, 4, "bread"),
            p(52, "Marby White Bread", "pantry_staples", "Marby", "loaf", "400g", 2, 38, 45.6, 4, "bread"),
            p(53, "Julie’s Pandesal", "pantry_staples", "Julie’s", "pack", "10pcs", 20, 30, 36, 4, "bread"),
            p(54, "Local Bakery Pandesal", "pantry_staples", "Local Bakery", "pack", "10pcs", 20, 25, 30, 4, "bread"),
            p(55, "Fita Crackers", "snacks_sweets", "Fita", "pack", "30g", 20, 8, 10.4, 6, "crackers"),
            p(56, "SkyFlakes Crackers", "snacks_sweets", "SkyFlakes", "pack", "25g", 20, 8, 10.4, 6, "crackers"),
            p(57, "Cream-O Chocolate Sandwich", "snacks_sweets", "Cream-O", "pack", "33g", 20, 9, 11.7, 6, "cookies"),
            p(58, "Oreo Original", "snacks_sweets", "Oreo", "pack", "27g", 3, 10, 13, 6, "cookies"),
            p(59, "Marie Biscuits", "snacks_sweets", "Marie", "pack", "30g", 20, 8, 10.4, 6, "crackers"),
            p(60, "Rebisco Crackers", "snacks_sweets", "Rebisco", "pack", "32g", 20, 8, 10.4, 6, "crackers"),
            p(61, "Choc-Nut", "snacks_sweets", "Choc-Nut", "piece", "24g", 20, 8, 10.4, 8, "chocolates"),
            p(62, "Flat Tops Chocolate", "snacks_sweets", "Flat Tops", "piece", "24g", 20, 7, 9.1, 8, "chocolates"),
            p(63, "Cloud 9 Chocolate Bar", "snacks_sweets", "Cloud 9", "piece", "27g", 20, 10, 13, 8, "chocolates"),
            p(64, "Maxx Candy", "snacks_sweets", "Maxx", "piece", "single", 20, 2.5, 3.5, 15, "candies"),
            p(65, "White Rabbit Candy", "snacks_sweets", "White Rabbit", "piece", "single", 20, 3, 4.05, 15, "candies"),
            p(66, "Kendi Mint Candy", "snacks_sweets", "Kendi Mint", "piece", "single", 0, 2, 2.8, 15, "candies"),
            p(67, "Piattos Cheese", "snacks_sweets", "Piattos", "pack", "85g", 20, 30, 37.5, 6, "chips"),
            p(68, "Nova Multigrain Snacks", "snacks_sweets", "Nova", "pack", "78g", 20, 30, 37.5, 6, "chips"),
            p(69, "Clover Chips Cheese", "snacks_sweets", "Clover Chips", "pack", "55g", 20, 20, 25, 6, "chips"),
            p(70, "Chippy Barbecue", "snacks_sweets", "Chippy", "pack", "110g", 20, 25, 31.25, 6, "chips"),
            p(71, "Oishi Prawn Crackers", "snacks_sweets", "Oishi", "pack", "60g", 3, 18, 22.5, 6, "chips"),
            p(72, "Mang Juan Espesyal", "snacks_sweets", "Mang Juan", "pack", "90g", 20, 25, 31.25, 6, "chips"),
            p(73, "La Filipina Iodized Salt", "pantry_staples", "La Filipina", "pack", "500g", 20, 15, 19.5, 5, "salt"),
            p(74, "Diamond Crystal Salt", "pantry_staples", "Diamond Crystal", "pack", "500g", 20, 18, 23.4, 5, "salt"),
            p(75, "Morton Iodized Salt", "pantry_staples", "Morton", "pack", "500g", 20, 22, 28.6, 5, "salt"),
            p(76, "Local Sea Salt", "pantry_staples", "Sea Salt", "pack", "500g", 20, 12, 15.6, 5, "salt"),
            p(77, "Iodized Salt", "pantry_staples", "Iodized Salt", "pack", "500g", 0, 13, 16.9, 5, "salt"),
            p(78, "Fine Table Salt", "pantry_staples", "Fine Salt", "pack", "500g", 20, 12, 15.6, 5, "salt"),
            p(79, "Victorias Refined Sugar", "pantry_staples", "Victorias", "pack", "1kg", 20, 80, 96, 5, "sugar"),
            p(80, "Central Refined Sugar", "pantry_staples", "Central Azucarera", "pack", "1kg", 20, 78, 93.6, 5, "sugar"),
            p(81, "Sweet Crystal Sugar", "pantry_staples", "Sweet Crystal", "pack", "1kg", 20, 75, 90, 5, "sugar"),
            p(82, "C&H Sugar", "pantry_staples", "C&H", "pack", "1kg", 0, 95, 114, 5, "sugar"),
            p(83, "Domino Sugar", "pantry_staples", "Domino", "pack", "1kg", 2, 90, 108, 5, "sugar"),
            p(84, "Brown Sugar", "pantry_staples", "Brown Sugar", "pack", "1kg", 20, 75, 90, 5, "sugar"),
            p(85, "Sunsilk Shampoo", "personal_care", "Sunsilk", "sachet", "12ml", 20, 7, 9.45, 8, "shampoo"),
            p(86, "Cream Silk Conditioner", "personal_care", "Cream Silk", "sachet", "12ml", 20, 7, 9.45, 8, "conditioner"),
            p(87, "Pantene Shampoo", "personal_care", "Pantene", "sachet", "12ml", 20, 8, 10.8, 8, "shampoo"),
            p(88, "Head & Shoulders Shampoo", "personal_care", "Head & Shoulders", "sachet", "12ml", 20, 8, 10.8, 8, "shampoo"),
            p(89, "Palmolive Shampoo", "personal_care", "Palmolive", "sachet", "12ml", 20, 6.5, 8.78, 8, "shampoo"),
            p(90, "Rejoice Shampoo", "personal_care", "Rejoice", "sachet", "12ml", 3, 7, 9.45, 8, "conditioner"),
            p(91, "Safeguard Classic", "personal_care", "Safeguard", "bar", "60g", 20, 22, 27.5, 6, "bath_soap"),
            p(92, "Dove Beauty Bar", "personal_care", "Dove", "bar", "90g", 20, 45, 56.25, 5, "bath_soap"),
            p(93, "Palmolive Naturals", "personal_care", "Palmolive", "bar", "90g", 20, 25, 31.25, 6, "bath_soap"),
            p(94, "Bioderm Soap", "personal_care", "Bioderm", "bar", "90g", 0, 20, 25, 6, "bath_soap"),
            p(95, "Silka Papaya Soap", "personal_care", "Silka", "bar", "65g", 20, 25, 31.25, 6, "bath_soap"),
            p(96, "Kojic Acid Soap", "personal_care", "Kojic", "bar", "65g", 4, 25, 31.25, 6, "bath_soap"),
            p(97, "Surf Powder Detergent", "household_care", "Surf", "sachet", "40g", 20, 8, 10.4, 8, "laundry"),
            p(98, "Ariel Powder Detergent", "household_care", "Ariel", "sachet", "40g", 20, 9, 11.7, 8, "laundry"),
            p(99, "Tide Powder Detergent", "household_care", "Tide", "sachet", "40g", 20, 9, 11.7, 8, "laundry"),
            p(100, "Champion Powder Detergent", "household_care", "Champion", "sachet", "40g", 20, 7, 9.1, 8, "laundry"),
            p(101, "Pride Powder Detergent", "household_care", "Pride", "sachet", "40g", 0, 7, 9.1, 8, "laundry"),
            p(102, "Breeze Powder Detergent", "household_care", "Breeze", "sachet", "40g", 3, 9, 11.7, 8, "laundry"),
            p(103, "Colgate Toothpaste", "personal_care", "Colgate", "tube", "50g", 20, 45, 56.25, 5, "toothpaste"),
            p(104, "Closeup Toothpaste", "personal_care", "Closeup", "tube", "50g", 20, 42, 52.5, 5, "toothpaste"),
            p(105, "Hapee Toothpaste", "personal_care", "Hapee", "tube", "50g", 20, 35, 43.75, 5, "toothpaste"),
            p(106, "Oral-B Toothbrush", "personal_care", "Oral-B", "piece", "1pc", 20, 35, 43.75, 5, "toothbrush"),
            p(107, "Pepsodent Toothpaste", "personal_care", "Pepsodent", "tube", "50g", 20, 35, 43.75, 5, "toothpaste"),
            p(108, "Systema Toothbrush", "personal_care", "Systema", "piece", "1pc", 2, 30, 37.5, 5, "toothbrush"),
            p(109, "Katol Mosquito Coil", "household_care", "Katol", "pack", "10 coils", 20, 22, 28.6, 5, "mosquito_control"),
            p(110, "Baygon Mosquito Coil", "household_care", "Baygon", "pack", "10 coils", 20, 35, 45.5, 5, "mosquito_control"),
            p(111, "Off! Mosquito Repellent", "household_care", "Off!", "sachet", "1pc", 20, 12, 15.6, 5, "mosquito_control"),
            p(112, "Raid Mosquito Coil", "household_care", "Raid", "pack", "10 coils", 20, 30, 39, 5, "mosquito_control"),
            p(113, "Lion Tiger Mosquito Coil", "household_care", "Lion Tiger", "pack", "10 coils", 0, 20, 26, 5, "mosquito_control"),
            p(114, "Local Mosquito Coil", "household_care", "Local Brand", "pack", "10 coils", 4, 18, 23.4, 5, "mosquito_control"),
            p(115, "Marlboro Red", "liquor_wine", "Marlboro", "pack", "20 sticks", 20, 140, 154, 5, "cigarettes"),
            p(116, "Fortune Red", "liquor_wine", "Fortune", "pack", "20 sticks", 20, 120, 132, 5, "cigarettes"),
            p(117, "Winston Red", "liquor_wine", "Winston", "pack", "20 sticks", 20, 130, 143, 5, "cigarettes"),
            p(118, "Camel Blue", "liquor_wine", "Camel", "pack", "20 sticks", 20, 130, 143, 5, "cigarettes"),
            p(119, "Philip Morris Red", "liquor_wine", "Philip Morris", "pack", "20 sticks", 20, 125, 137.5, 5, "cigarettes"),
            p(120, "Mighty Red", "liquor_wine", "Mighty", "pack", "20 sticks", 0, 110, 121, 5, "cigarettes"),
            p(121, "Minola Cooking Oil", "pantry_staples", "Minola", "bottle", "1L", 20, 120, 132, 5, "cooking_oil"),
            p(122, "Baguio Oil", "pantry_staples", "Baguio", "bottle", "1L", 20, 110, 121, 5, "cooking_oil"),
            p(123, "Palm Oil Cooking Oil", "pantry_staples", "Palm Oil", "bottle", "1L", 20, 115, 126.5, 5, "cooking_oil"),
            p(124, "Datu Puti Vinegar", "pantry_staples", "Datu Puti", "bottle", "1L", 20, 60, 66, 5, "vinegar"),
            p(125, "Silver Swan Vinegar", "pantry_staples", "Silver Swan", "bottle", "1L", 20, 58, 63.8, 5, "vinegar"),
            p(126, "Mafran Cane Vinegar", "pantry_staples", "Mafran", "bottle", "1L", 20, 55, 60.5, 5, "vinegar"),
            p(127, "Purefoods Corned Beef", "canned_goods", "Purefoods", "can", "150g", 20, 80, 88, 5, "corned_beef"),
            p(128, "Argentina Corned Beef", "canned_goods", "Argentina", "can", "155g", 20, 75, 82.5, 5, "corned_beef"),
            p(129, "CDO Corned Beef", "canned_goods", "CDO", "can", "150g", 20, 72, 79.2, 5, "corned_beef"),
            p(130, "CDO Meat Loaf Plain", "canned_goods", "CDO", "can", "150g", 20, 50, 55, 5, "meat_loaf"),
            p(131, "CDO Meat Loaf Sweet Style", "canned_goods", "CDO", "can", "150g", 20, 50, 55, 5, "meat_loaf"),
            p(132, "Purefoods Luncheon Meat", "canned_goods", "Purefoods", "can", "150g", 20, 55, 60.5, 5, "meat_loaf"),
            p(133, "Argentina Sausage", "canned_goods", "Argentina", "can", "150g", 20, 45, 49.5, 5, "sausage"),
            p(134, "Purefoods Sausage", "canned_goods", "Purefoods", "can", "150g", 20, 48, 52.8, 5, "sausage"),
            p(135, "CDO Vienna Sausage", "canned_goods", "CDO", "can", "150g", 20, 46, 50.6, 5, "sausage"),
            p(136, "Nissin Cup Noodles Beef", "instant_dry_goods", "Nissin", "piece", "65g", 20, 25, 27.5, 5, "cup_noodles"),
            p(137, "Lucky Me! Cup Noodles", "instant_dry_goods", "Lucky Me!", "piece", "55g", 20, 18, 19.8, 5, "cup_noodles"),
            p(138, "Payless Cup Noodles", "instant_dry_goods", "Payless", "piece", "55g", 20, 15, 16.5, 5, "cup_noodles"),
            p(139, "Royal Spaghetti", "instant_dry_goods", "Royal", "pack", "500g", 20, 35, 38.5, 5, "pasta"),
            p(140, "San Remo Spaghetti", "instant_dry_goods", "San Remo", "pack", "500g", 20, 40, 44, 5, "pasta"),
            p(141, "Del Monte Spaghetti", "instant_dry_goods", "Del Monte", "pack", "500g", 20, 38, 41.8, 5, "pasta"),
            p(142, "Knorr Chicken Soup Mix", "instant_dry_goods", "Knorr", "pack", "30g", 20, 15, 16.5, 5, "soup_mixes"),
            p(143, "Knorr Beef Soup Mix", "instant_dry_goods", "Knorr", "pack", "30g", 20, 15, 16.5, 5, "soup_mixes"),
            p(144, "Mama Sita's Sinigang Mix", "instant_dry_goods", "Mama Sita's", "pack", "40g", 20, 20, 22, 5, "soup_mixes"),
            p(145, "Bear Brand Powdered Milk", "beverages", "Bear Brand", "pack", "600g", 20, 380, 418, 5, "powdered_milk"),
            p(146, "Nido 3+ Powdered Milk", "beverages", "Nido", "pack", "700g", 20, 420, 462, 5, "powdered_milk"),
            p(147, "Nestlé Milkady", "beverages", "Nestlé", "pack", "650g", 20, 400, 440, 5, "powdered_milk"),
            p(148, "Milo Chocolate Drink", "beverages", "Milo", "pack", "500g", 20, 280, 308, 5, "chocolate_drink"),
            p(149, "Ovaltine Chocolate Drink", "beverages", "Ovaltine", "pack", "500g", 20, 270, 297, 5, "chocolate_drink"),
            p(150, "Nestlé Nesquik", "beverages", "Nesquik", "pack", "400g", 20, 260, 286, 5, "chocolate_drink"),
            p(151, "Zesto Orange Juice", "beverages", "Zesto", "bottle", "1L", 20, 65, 71.5, 5, "juice"),
            p(152, "Tang Orange", "beverages", "Tang", "pack", "500g", 20, 120, 132, 5, "juice"),
            p(153, "Sunkist Apple Juice", "beverages", "Sunkist", "bottle", "1L", 20, 70, 77, 5, "juice"),
            p(154, "Eden Cheese", "dairy_refrigerated", "Eden", "pack", "160g", 20, 95, 104.5, 5, "cheese"),
            p(155, "Magnolia Cheese", "dairy_refrigerated", "Magnolia", "pack", "160g", 20, 90, 99, 5, "cheese"),
            p(156, "Quickmelt Cheese", "dairy_refrigerated", "Quickmelt", "pack", "150g", 20, 88, 96.8, 5, "cheese"),
            p(157, "Buttercup", "dairy_refrigerated", "Buttercup", "bar", "200g", 20, 65, 71.5, 5, "butter"),
            p(158, "Dari Creme Butter", "dairy_refrigerated", "Dari Creme", "bar", "200g", 20, 60, 66, 5, "butter"),
            p(159, "Anchor Butter", "dairy_refrigerated", "Anchor", "bar", "250g", 20, 240, 264, 5, "butter"),
            p(160, "Star Margarine", "dairy_refrigerated", "Star", "bar", "200g", 20, 45, 49.5, 5, "margarine"),
            p(161, "Dari Creme Margarine", "dairy_refrigerated", "Dari Creme", "bar", "200g", 20, 42, 46.2, 5, "margarine"),
            p(162, "Reyes Margarine", "dairy_refrigerated", "Reyes", "bar", "200g", 20, 40, 44, 5, "margarine"),
            p(163, "Purefoods Vienna Sausage", "dairy_refrigerated", "Purefoods", "pack", "125g", 20, 40, 44, 5, "chilled_meats"),
            p(164, "CDO Chicken Hotdog", "dairy_refrigerated", "CDO", "pack", "500g", 20, 120, 132, 5, "chilled_meats"),
            p(165, "Purefoods Star Hotdog", "dairy_refrigerated", "Purefoods", "pack", "500g", 20, 130, 143, 5, "chilled_meats"),
            p(166, "Pork Belly", "fresh_section", "Local", "kg", "1kg", 20, 280, 308, 5, "fresh_meat"),
            p(167, "Chicken Leg Quarter", "fresh_section", "Local", "kg", "1kg", 20, 180, 198, 5, "fresh_meat"),
            p(168, "Beef Sirloin", "fresh_section", "Local", "kg", "1kg", 20, 420, 462, 5, "fresh_meat"),
            p(169, "Tilapia", "fresh_section", "Local", "kg", "1kg", 20, 150, 165, 5, "fresh_seafood"),
            p(170, "Galunggong", "fresh_section", "Local", "kg", "1kg", 20, 160, 176, 5, "fresh_seafood"),
            p(171, "Sugpo Shrimp", "fresh_section", "Local", "kg", "1kg", 20, 450, 495, 5, "fresh_seafood"),
            p(172, "Lakatan Banana", "fresh_section", "Local", "kg", "1kg", 20, 70, 77, 5, "fruits"),
            p(173, "Carabao Mango", "fresh_section", "Local", "kg", "1kg", 20, 120, 132, 5, "fruits"),
            p(174, "Red Apple", "fresh_section", "Imported", "kg", "1kg", 20, 180, 198, 5, "fruits"),
            p(175, "Kangkong", "fresh_section", "Local", "bundle", "1 bundle", 20, 20, 22, 5, "vegetables"),
            p(176, "Ampalaya", "fresh_section", "Local", "kg", "1kg", 20, 60, 66, 5, "vegetables"),
            p(177, "Sitaw", "fresh_section", "Local", "bundle", "1 bundle", 20, 25, 27.5, 5, "vegetables"),
            p(178, "San Miguel Pale Pilsen", "liquor_wine", "San Miguel", "bottle", "330mL", 20, 55, 60.5, 5, "beer"),
            p(179, "Red Horse Beer", "liquor_wine", "Red Horse", "bottle", "500mL", 20, 65, 71.5, 5, "beer"),
            p(180, "San Mig Light", "liquor_wine", "San Miguel", "bottle", "330mL", 20, 55, 60.5, 5, "beer"),
            p(181, "Ginebra San Miguel", "liquor_wine", "Ginebra", "bottle", "350mL", 20, 95, 104.5, 5, "gin"),
            p(182, "Ginebra Premium Gin", "liquor_wine", "Ginebra", "bottle", "350mL", 20, 130, 143, 5, "gin"),
            p(183, "Ginebra Flavors", "liquor_wine", "Ginebra", "bottle", "350mL", 20, 140, 154, 5, "gin"),
            p(184, "Fundador Brandy", "liquor_wine", "Fundador", "bottle", "350mL", 20, 180, 198, 5, "brandy"),
            p(185, "Emperador Brandy", "liquor_wine", "Emperador", "bottle", "350mL", 20, 150, 165, 5, "brandy"),
            p(186, "Generoso Brandy", "liquor_wine", "Generoso", "bottle", "350mL", 20, 140, 154, 5, "brandy"),
            p(187, "Moscato d'Asti", "liquor_wine", "Moscato", "bottle", "750mL", 20, 320, 352, 5, "wine"),
            p(188, "Sangria Red Wine", "liquor_wine", "Sangria", "bottle", "750mL", 20, 280, 308, 5, "wine"),
            p(189, "Chardonnay White Wine", "liquor_wine", "Chardonnay", "bottle", "750mL", 20, 300, 330, 5, "wine"),
            p(190, "Vaseline Body Lotion", "personal_care", "Vaseline", "bottle", "200mL", 20, 180, 198, 5, "lotion"),
            p(191, "Jergens Body Lotion", "personal_care", "Jergens", "bottle", "200mL", 20, 220, 242, 5, "lotion"),
            p(192, "Nivea Body Lotion", "personal_care", "Nivea", "bottle", "200mL", 20, 240, 264, 5, "lotion"),
            p(193, "Ever Bilena Lipstick", "personal_care", "Ever Bilena", "piece", "1 pc", 20, 120, 132, 5, "cosmetics"),
            p(194, "Nichido Pressed Powder", "personal_care", "Nichido", "piece", "1 pc", 20, 180, 198, 5, "cosmetics"),
            p(195, "Ever Bilena Face Powder", "personal_care", "Ever Bilena", "piece", "1 pc", 20, 150, 165, 5, "cosmetics"),
            p(196, "Downy Fabric Conditioner", "household_care", "Downy", "sachet", "1L", 20, 150, 165, 5, "fabric_softener"),
            p(197, "Comfort Fabric Conditioner", "household_care", "Comfort", "sachet", "1L", 20, 145, 159.5, 5, "fabric_softener"),
            p(198, "Surf Fabric Conditioner", "household_care", "Surf", "sachet", "1L", 20, 140, 154, 5, "fabric_softener"),
            p(199, "Joy Dishwashing Liquid", "household_care", "Joy", "sachet", "170mL", 20, 30, 33, 5, "dishwashing"),
            p(200, "Surf Dishwashing Liquid", "household_care", "Surf", "sachet", "170mL", 20, 28, 30.8, 5, "dishwashing"),
            p(201, "Zonrox Dishwashing", "household_care", "Zonrox", "sachet", "170mL", 20, 29, 31.9, 5, "dishwashing"),
            p(202, "Zonrox Bleach", "household_care", "Zonrox", "bottle", "1L", 20, 85, 93.5, 5, "cleaners"),
            p(203, "Mr. Muscle Cleaner", "household_care", "Mr. Muscle", "bottle", "500mL", 20, 120, 132, 5, "cleaners"),
            p(204, "Lysol Disinfectant", "household_care", "Lysol", "bottle", "1L", 20, 150, 165, 5, "cleaners"),
            p(205, "Champion Trash Bags", "household_care", "Champion", "pack", "14 pcs", 20, 60, 66, 5, "trash_bags"),
            p(206, "Green Trash Bags", "household_care", "Green", "pack", "14 pcs", 20, 55, 60.5, 5, "trash_bags"),
            p(207, "Ecobag Trash Bags", "household_care", "Ecobag", "pack", "14 pcs", 20, 58, 63.8, 5, "trash_bags"),
            p(208, "Pampers Diapers Medium", "baby_care", "Pampers", "pack", "30 pcs", 20, 280, 308, 5, "diapers"),
            p(209, "EQ Diapers Medium", "baby_care", "EQ", "pack", "30 pcs", 20, 260, 286, 5, "diapers"),
            p(210, "Baby Love Diapers", "baby_care", "Baby Love", "pack", "30 pcs", 20, 240, 264, 5, "diapers"),
            p(211, "Pampers Baby Wipes", "baby_care", "Pampers", "pack", "48 pcs", 20, 80, 88, 5, "baby_wipes"),
            p(212, "EQ Baby Wipes", "baby_care", "EQ", "pack", "48 pcs", 20, 70, 77, 5, "baby_wipes"),
            p(213, "Baby Care Wipes", "baby_care", "Baby Care", "pack", "40 pcs", 20, 65, 71.5, 5, "baby_wipes"),
            p(214, "Johnson's Baby Lotion", "baby_care", "Johnson's", "bottle", "200mL", 20, 180, 198, 5, "baby_toiletries"),
            p(215, "Johnson's Baby Powder", "baby_care", "Johnson's", "bottle", "200g", 20, 120, 132, 5, "baby_toiletries"),
            p(216, "Cetaphil Baby Wash", "baby_care", "Cetaphil", "bottle", "400mL", 20, 280, 308, 5, "baby_toiletries"),
            p(217, "CDO Facial Tissue", "paper_sanitary", "CDO", "pack", "100 pcs", 20, 40, 44, 5, "tissue"),
            p(218, "Kleenex Tissues", "paper_sanitary", "Kleenex", "pack", "100 pcs", 20, 60, 66, 5, "tissue"),
            p(219, "Scott Tissues", "paper_sanitary", "Scott", "pack", "100 pcs", 20, 55, 60.5, 5, "tissue"),
            p(220, "Velvex Paper Towel", "paper_sanitary", "Velvex", "piece", "1 roll", 20, 45, 49.5, 5, "paper_towels"),
            p(221, "Scott Paper Towel", "paper_sanitary", "Scott", "piece", "1 roll", 20, 60, 66, 5, "paper_towels"),
            p(222, "Bounty Paper Towel", "paper_sanitary", "Bounty", "piece", "1 roll", 20, 65, 71.5, 5, "paper_towels"),
            p(223, "Nurse Beauty Sanitary Pads", "paper_sanitary", "Nurse Beauty", "pack", "10 pcs", 20, 25, 27.5, 5, "sanitary_pads"),
            p(224, "Modess Sanitary Pads", "paper_sanitary", "Modess", "pack", "10 pcs", 20, 55, 60.5, 5, "sanitary_pads"),
            p(225, "Whisper Sanitary Pads", "paper_sanitary", "Whisper", "pack", "10 pcs", 20, 50, 55, 5, "sanitary_pads"),
        )
    }

    private fun p(
        id: Int, name: String, category: String, brand: String,
        unit: String, packageSize: String, qty: Int, cost: Number,
        sell: Number, threshold: Int, subcategory: String
    ) = Product(
        id = id, name = name, quantity = qty, costPrice = cost.toDouble(),
        sellingPrice = sell.toDouble(), lowStockThreshold = threshold,
        category = category, subcategory = subcategory, brand = brand,
        packageSize = packageSize, unit = unit
    )

    fun clearAllInventory() {
        _products.value = emptyList()
    }

    fun clearSelectedData(types: List<String>) {
        types.forEach { type ->
            when (type) {
                "products" -> _products.value = emptyList()
                "sales" -> {
                    _dailyEntry.value = null
                    _specificSales.value = emptyList()
                }
                "debts" -> {
                    _debts.value = emptyList()
                    _payments.value = emptyList()
                    _debtTransactions.value = emptyList()
                }
                "eod" -> _endOfDayData.value = null
                "expenses" -> _expenses.value = emptyList()
            }
        }
    }

    init {
        // Don't seed data here anymore — it's now done in initPersistence()
        // if no persisted data exists.

        // Observable-date ticker: fires at each midnight so any UI depending on
        // `today` (overdue banner, day guards, headers) recomposes in real time
        // even while the app process stays alive across calendar days.
        viewModelScope.launch {
            while (true) {
                val now = Calendar.getInstance()
                val nextMidnight = Calendar.getInstance().apply {
                    timeInMillis = now.timeInMillis
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                    add(Calendar.DAY_OF_YEAR, 1)
                }
                delay((nextMidnight.timeInMillis - now.timeInMillis).coerceAtLeast(1000L))
                _currentDate.value = dateNow()
            }
        }
    }

    // ── CSV Export ────────────────────────────────────────────────────
    fun exportCsv(): String {
        val sb = StringBuilder()
        sb.appendLine("TindaGo - Data Export")
        sb.appendLine("Exported: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date())}")
        sb.appendLine()

        // Products section (v2.59: identity columns — category, brand, unit, package size)
        sb.appendLine("=== PRODUCTS ===")
        sb.appendLine("ID,Name,Category,Brand,Unit,Package Size,Quantity,Cost Price,Selling Price,Markup,Status")
        _products.value.forEach { p ->
            val margin = if (p.costPrice > 0) String.format("%.1f%%", ((p.sellingPrice - p.costPrice) / p.costPrice) * 100) else "N/A"
            sb.appendLine("${p.id},${p.name},${p.category},${p.brand},${p.unit},${p.packageSize},${p.quantity},${p.costPrice},${p.sellingPrice},$margin,${p.status}")
        }
        sb.appendLine()

        // Sales section
        sb.appendLine("=== TODAY'S SALES ===")
        sb.appendLine("ID,Date,Description,Quantity,Amount,Profit,Customer")
        val todaySales = _specificSales.value.filter { it.date == today }
        todaySales.forEach { s ->
            sb.appendLine("${s.id},${s.date},${s.description},${s.quantity},${s.amount},${s.profit},${s.customerName ?: "Cash"}")
        }
        sb.appendLine()

        // Weekly summary
        sb.appendLine("=== WEEKLY SUMMARY ===")
        val sevenDaysAgo = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            .format(java.util.Date(System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000))
        val weekSales = _specificSales.value.filter { it.date >= sevenDaysAgo }
        sb.appendLine("Total Sales (7 days),${String.format("%.2f", weekSales.sumOf { it.amount })}")
        sb.appendLine("Total Profit (7 days),${String.format("%.2f", weekSales.sumOf { it.profit })}")
        sb.appendLine("Items Sold (7 days),${weekSales.sumOf { it.quantity }}")
        sb.appendLine()

        // Debts section
        sb.appendLine("=== OUTSTANDING DEBTS ===")
        sb.appendLine("Customer,Total Amount,Remaining Balance,Last Activity")
        _debts.value.filter { it.remainingBalance > 0 }.forEach { d ->
            val lastAct = getLastActivity(d)
            sb.appendLine("${d.customerName},${d.amount},${d.remainingBalance},$lastAct")
        }
        sb.appendLine()

        // Expenses section (web V2.71 parity)
        sb.appendLine("=== EXPENSES ===")
        sb.appendLine("ID,Date,Category,Amount,Note")
        _expenses.value.sortedByDescending { it.date }.forEach { e ->
            sb.appendLine("${e.id},${e.date},${e.category},${e.amount},${e.note}")
        }
        sb.appendLine()

        // Inventory status
        sb.appendLine("=== INVENTORY STATUS ===")
        sb.appendLine("Status,Count")
        sb.appendLine("Out of Stock,${outOfStockCount}")
        sb.appendLine("Low Stock,${lowStockCount}")
        sb.appendLine("Plenty,${_products.value.size - outOfStockCount - lowStockCount}")

        return sb.toString()
    }

    fun getLowStockItems(): List<Product> {
        return _products.value.filter { it.status != StockStatus.PLENTY }
            .sortedBy { it.quantity }
    }

    // ── Weekly Snapshot (Phase 3b) ────────────────────────────────────
    /** Total sales from specific sales in the last 7 days */
    fun getWeekSales(): Double {
        val sevenDaysAgo = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            .format(Date(System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000))
        return _specificSales.value
            .filter { it.date >= sevenDaysAgo }
            .sumOf { it.amount }
    }

    /** Estimated weekly earnings (daily entries + specific sales for last 7 days) */
    fun getWeekEarnings(): Double {
        val sevenDaysAgo = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            .format(Date(System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000))
        val specificTotal = _specificSales.value
            .filter { it.date >= sevenDaysAgo }
            .sumOf { it.amount }
        val dailyTotal = _dailyEntry.value?.earnings ?: 0.0
        return specificTotal + dailyTotal
    }

    /** Estimated weekly profit */
    fun getWeekProfit(): Double {
        return getWeekEarnings() * 0.15 // rough estimate: 15% margin on total
    }

    // ── Reports engine (web v2.55 parity) ────────────────────────────────
    /**
     * Compute everything the Reports screen needs for a period: current-window
     * sales, previous-window comparison totals, utang/receivables summary with
     * aging buckets, and cash collected within the period. Anchored to the
     * (possibly dev-overridden) [today].
     */
    fun computeReportStats(period: String): ReportStats {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val todayDate = try { fmt.parse(today) ?: Date() } catch (_: Exception) { Date() }
        val dayMs = 24L * 60 * 60 * 1000
        val curLen = when (period) {
            "week" -> 7
            "month" -> 30
            else -> 1
        }
        val curStart = fmt.format(Date(todayDate.time - (curLen - 1) * dayMs))
        val prevEnd = fmt.format(Date(todayDate.time - curLen * dayMs))
        val prevStart = fmt.format(Date(todayDate.time - (2 * curLen - 1) * dayMs))

        val curSales = _specificSales.value.filter { it.date >= curStart && it.date <= today }
        val prevSales = _specificSales.value.filter { it.date >= prevStart && it.date <= prevEnd }
        val activeDebts = _debts.value.filter { it.remainingBalance > 0 }

        // Cash collected within the period: any payment recorded at/after the period start
        val curStartMs = fmt.parse(curStart)?.time ?: 0L
        val collected = _payments.value.filter { it.timestamp >= curStartMs }.sumOf { it.amount }

        // Aging buckets (0-30 / 31-60 / 60+ days) by debt creation date
        val nowMs = System.currentTimeMillis()
        val aging = MutableList(3) { AgingBucket(0.0, 0) }
        activeDebts.forEach { d ->
            val ageDays = ((nowMs - d.createdAt) / dayMs).toInt().coerceAtLeast(0)
            val idx = if (ageDays >= 60) 2 else if (ageDays >= 30) 1 else 0
            val b = aging[idx]
            aging[idx] = AgingBucket(b.amount + d.remainingBalance, b.count + 1)
        }

        // V2.71: expenses + Net Profit for the period (gross profit stays as-is)
        val expenses = getPeriodExpensesTotal(curStart)
        val netProfit = curSales.sumOf { it.profit } - expenses
        val prevExpensesTotal = getPeriodExpensesTotal(prevStart) - expenses
        val prevNetProfit = prevSales.sumOf { it.profit } - prevExpensesTotal

        return ReportStats(
            period = period,
            periodStart = curStart,
            sales = curSales,
            prevSalesTotal = prevSales.sumOf { it.amount },
            prevProfitTotal = prevSales.sumOf { it.profit },
            outstandingUtang = activeDebts.sumOf { it.remainingBalance },
            activeDebtors = activeDebts.size,
            collectedThisPeriod = collected,
            aging = aging,
            expenses = expenses,
            netProfit = netProfit,
            prevExpensesTotal = prevExpensesTotal,
            prevNetProfit = prevNetProfit
        )
    }

    /** Period-scoped CSV report (web exportCurrentReport parity). */
    fun exportReportCsv(period: String): String {
        val st = computeReportStats(period)
        val periodLabel = when (period) {
            "week" -> "Week"
            "month" -> "Month"
            else -> "Day"
        }
        val sb = StringBuilder()
        sb.appendLine("TindaGo - Report ($periodLabel)")
        sb.appendLine("Period,$today")
        sb.appendLine("Total Sales,${String.format("%.2f", st.sales.sumOf { it.amount })}")
        sb.appendLine("Total Profit,${String.format("%.2f", st.sales.sumOf { it.profit })}")
        sb.appendLine("Expenses,${String.format("%.2f", st.expenses)}")
        sb.appendLine("Net Profit,${String.format("%.2f", st.netProfit)}")
        sb.appendLine("Items Sold,${st.sales.sumOf { it.quantity }}")
        sb.appendLine("Transactions,${st.sales.size}")
        sb.appendLine("Cash Sales,${String.format("%.2f", st.sales.filter { it.customerName == null }.sumOf { it.amount })}")
        sb.appendLine("Credit Sales,${String.format("%.2f", st.sales.filter { it.customerName != null }.sumOf { it.amount })}")
        sb.appendLine("vs Previous Sales,${String.format("%.2f", st.prevSalesTotal)}")
        sb.appendLine("Outstanding Debts,${String.format("%.2f", st.outstandingUtang)}")
        sb.appendLine("Active Debtors,${st.activeDebtors}")
        sb.appendLine("Collected This Period,${String.format("%.2f", st.collectedThisPeriod)}")
        sb.appendLine("Aging 0-30 days,${String.format("%.2f", st.aging[0].amount)} (${st.aging[0].count})")
        sb.appendLine("Aging 31-60 days,${String.format("%.2f", st.aging[1].amount)} (${st.aging[1].count})")
        sb.appendLine("Aging 60+ days,${String.format("%.2f", st.aging[2].amount)} (${st.aging[2].count})")
        sb.appendLine()
        sb.appendLine("=== TRANSACTIONS ===")
        sb.appendLine("Date,Description,Quantity,Amount,Profit,Customer")
        st.sales.sortedByDescending { it.timestamp }.forEach { s ->
            sb.appendLine("${s.date},${s.description},${s.quantity},${String.format("%.2f", s.amount)},${String.format("%.2f", s.profit)},${s.customerName ?: "Cash"}")
        }
        sb.appendLine()
        sb.appendLine("=== EXPENSES ===")
        sb.appendLine("Date,Category,Amount,Note")
        _expenses.value.filter { it.date >= st.periodStart }.forEach { e ->
            sb.appendLine("${e.date},${e.category},${String.format("%.2f", e.amount)},${e.note}")
        }
        sb.appendLine()
        sb.appendLine("=== LOW STOCK ===")
        sb.appendLine("Name,Quantity,Status")
        _products.value.filter { it.status != StockStatus.PLENTY }.sortedBy { it.quantity }.forEach { p ->
            val status = if (p.quantity <= 0) "Out of stock" else "Low"
            sb.appendLine("${p.name},${p.quantity},$status")
        }
        return sb.toString()
    }
}

/** Credit status for a customer given a prospective purchase (web getCreditStatus parity). */
data class CreditStatus(
    val limit: Int,
    val balance: Double,
    val total: Double,
    val overLimit: Boolean,
    val atLimit: Boolean,
    val nearLimit: Boolean
)

/** Aggregated report stats for one period (web computeReportStats parity). */
data class ReportStats(
    val period: String,
    /** First date of the current window (YYYY-MM-DD) — used for period expenses. */
    val periodStart: String = "",
    val sales: List<SpecificSale>,
    val prevSalesTotal: Double,
    val prevProfitTotal: Double,
    val outstandingUtang: Double,
    val activeDebtors: Int,
    val collectedThisPeriod: Double,
    val aging: List<AgingBucket>,
    /** Total store expenses in the period (web V2.71 parity). */
    val expenses: Double = 0.0,
    /** Net Profit = gross profit - expenses for the period. May be negative. */
    val netProfit: Double = 0.0,
    /** Previous-window expenses + net profit (for vs-previous badges). */
    val prevExpensesTotal: Double = 0.0,
    val prevNetProfit: Double = 0.0
)

/** One aging bucket (0-30 / 31-60 / 60+ days) — amount outstanding + debtor count. */
data class AgingBucket(
    val amount: Double,
    val count: Int
)