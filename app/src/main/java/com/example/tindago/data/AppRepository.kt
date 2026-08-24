package com.example.tindago.data

import com.example.tindago.data.local.dao.*
import com.example.tindago.data.local.entity.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Repository that wraps all Room DAOs and converts between Room entities and domain models.
 * This is the single source of truth for data access.
 */
class AppRepository(
    private val productDao: ProductDao,
    private val dailyEntryDao: DailyEntryDao,
    private val specificSaleDao: SpecificSaleDao,
    private val customerDebtDao: CustomerDebtDao,
    private val endOfDayDao: EndOfDayDao,
    private val restockLogDao: RestockLogDao,
    private val debtPaymentDao: DebtPaymentDao,
    private val debtTransactionDao: DebtTransactionDao,
    private val expenseDao: ExpenseDao,
    private val smsLogDao: SmsLogDao
) {
    // ── Products ────────────────────────────────────────────────────────
    fun getAllProducts(): Flow<List<Product>> =
        productDao.getAllProducts().map { entities -> entities.map { it.toDomainModel() } }

    suspend fun getProductById(id: Int): Product? =
        productDao.getProductById(id)?.toDomainModel()

    suspend fun saveProduct(product: Product) =
        productDao.insertProduct(ProductEntity.fromDomainModel(product))

    suspend fun saveProducts(products: List<Product>) =
        productDao.insertProducts(products.map { ProductEntity.fromDomainModel(it) })

    suspend fun deleteProduct(id: Int) = productDao.deleteProduct(id)

    suspend fun deleteAllProducts() = productDao.deleteAll()

    suspend fun searchProducts(query: String): List<Product> =
        productDao.searchProducts(query).map { it.toDomainModel() }

    // ── Daily Entries ───────────────────────────────────────────────────
    fun getLatestDailyEntry(): Flow<DailyEntry?> =
        dailyEntryDao.getLatestEntry().map { it?.toDomainModel() }

    suspend fun getDailyEntryByDate(date: String): DailyEntry? =
        dailyEntryDao.getByDate(date)?.toDomainModel()

    suspend fun saveDailyEntry(entry: DailyEntry) =
        dailyEntryDao.insertEntry(DailyEntryEntity.fromDomainModel(entry))

    // ── Specific Sales ──────────────────────────────────────────────────
    fun getAllSpecificSales(): Flow<List<SpecificSale>> =
        specificSaleDao.getAllSales().map { entities -> entities.map { it.toDomainModel() } }

    fun getSpecificSalesByDate(date: String): Flow<List<SpecificSale>> =
        specificSaleDao.getSalesByDate(date).map { entities -> entities.map { it.toDomainModel() } }

    suspend fun saveSpecificSale(sale: SpecificSale) =
        specificSaleDao.insertSale(SpecificSaleEntity.fromDomainModel(sale))

    suspend fun saveSpecificSales(sales: List<SpecificSale>) =
        specificSaleDao.insertSales(sales.map { SpecificSaleEntity.fromDomainModel(it) })

    suspend fun deleteAllSpecificSales() = specificSaleDao.deleteAll()

    // ── Debts ───────────────────────────────────────────────────────────
    fun getAllDebts(): Flow<List<CustomerDebt>> =
        customerDebtDao.getAllDebts().map { entities -> entities.map { it.toDomainModel() } }

    suspend fun getDebtById(id: Int): CustomerDebt? =
        customerDebtDao.getDebtById(id)?.toDomainModel()

    suspend fun saveDebt(debt: CustomerDebt) =
        customerDebtDao.insertDebt(CustomerDebtEntity.fromDomainModel(debt))

    suspend fun saveDebts(debts: List<CustomerDebt>) =
        customerDebtDao.insertDebts(debts.map { CustomerDebtEntity.fromDomainModel(it) })

    suspend fun updateDebt(debt: CustomerDebt) =
        customerDebtDao.updateDebt(CustomerDebtEntity.fromDomainModel(debt))

    suspend fun updateDebtPhoneNumber(debtId: Int, phoneNumber: String) {
        val debt = customerDebtDao.getDebtById(debtId) ?: return
        customerDebtDao.updateDebt(debt.copy(phoneNumber = phoneNumber))
    }

    suspend fun updateDebtSmsOptIn(debtId: Int, optIn: Boolean) {
        val debt = customerDebtDao.getDebtById(debtId) ?: return
        customerDebtDao.updateDebt(debt.copy(smsOptIn = if (optIn) 1 else 0))
    }

    suspend fun deleteDebt(id: Int) = customerDebtDao.deleteDebt(id)

    suspend fun deleteAllDebts() = customerDebtDao.deleteAll()

    suspend fun getUsedCustomerNames(): List<String> =
        customerDebtDao.getUsedCustomerNames()

    // ── Debt Payments ──────────────────────────────────────────────────
    fun getAllPayments(): Flow<List<DebtPayment>> =
        debtPaymentDao.getAllPayments().map { entities -> entities.map { it.toDomainModel() } }

    fun getPaymentsByDebtId(debtId: Int): Flow<List<DebtPayment>> =
        debtPaymentDao.getPaymentsByDebtId(debtId).map { entities -> entities.map { it.toDomainModel() } }

    suspend fun getPaymentsByDebtIdList(debtId: Int): List<DebtPayment> =
        debtPaymentDao.getPaymentsByDebtIdList(debtId).map { it.toDomainModel() }

    suspend fun getLatestPaymentTimestamp(debtId: Int): Long? =
        debtPaymentDao.getLatestPaymentTimestamp(debtId)

    suspend fun savePayment(payment: DebtPayment) =
        debtPaymentDao.insertPayment(DebtPaymentEntity.fromDomainModel(payment))

    suspend fun savePayments(payments: List<DebtPayment>) =
        debtPaymentDao.insertPayments(payments.map { DebtPaymentEntity.fromDomainModel(it) })

    suspend fun deletePaymentsByDebtId(debtId: Int) =
        debtPaymentDao.deletePaymentsByDebtId(debtId)

    suspend fun deleteAllPayments() = debtPaymentDao.deleteAll()

    // ── Debt Transactions (ledger) ─────────────────────────────────────
    fun getAllDebtTransactions(): Flow<List<DebtTransaction>> =
        debtTransactionDao.getAllTransactions().map { entities -> entities.map { it.toDomainModel() } }

    suspend fun saveDebtTransaction(tx: DebtTransaction) =
        debtTransactionDao.insertTransaction(DebtTransactionEntity.fromDomainModel(tx))

    suspend fun saveDebtTransactions(txs: List<DebtTransaction>) =
        debtTransactionDao.insertTransactions(txs.map { DebtTransactionEntity.fromDomainModel(it) })

    suspend fun deleteAllDebtTransactions() = debtTransactionDao.deleteAll()

    // ── End-of-Day Data ─────────────────────────────────────────────────
    fun getLatestEndOfDayData(): Flow<EndOfDayData?> =
        endOfDayDao.getLatest().map { it?.toDomainModel() }

    suspend fun getEndOfDayByDate(date: String): EndOfDayData? =
        endOfDayDao.getByDate(date)?.toDomainModel()

    suspend fun saveEndOfDayData(data: EndOfDayData) =
        endOfDayDao.insert(EndOfDayEntity.fromDomainModel(data))

    suspend fun deleteEndOfDayData(date: String) =
        endOfDayDao.deleteByDate(date)

    // ── Restock Log ─────────────────────────────────────────────────────
    fun getAllRestockLogs(): Flow<List<RestockLogEntry>> =
        restockLogDao.getAll().map { entities -> entities.map { it.toDomainModel() } }

    fun getLatestRestockLog(): Flow<RestockLogEntry?> =
        restockLogDao.getLatest().map { it?.toDomainModel() }

    suspend fun saveRestockLog(entry: RestockLogEntry) =
        restockLogDao.insert(RestockLogEntity.fromDomainModel(entry))

    suspend fun deleteAllRestockLogs() = restockLogDao.deleteAll()

    // ── Expenses (web V2.71 parity) ─────────────────────────────────────
    fun getAllExpenses(): Flow<List<Expense>> =
        expenseDao.getAllExpenses().map { entities -> entities.map { it.toDomainModel() } }

    suspend fun saveExpense(expense: Expense) =
        expenseDao.insert(ExpenseEntity.fromDomainModel(expense))

    suspend fun saveExpenses(expenses: List<Expense>) =
        expenses.forEach { expenseDao.insert(ExpenseEntity.fromDomainModel(it)) }

    suspend fun deleteExpense(id: Int) = expenseDao.deleteById(id)

    suspend fun deleteAllExpenses() = expenseDao.deleteAll()

    // ── SMS Log ─────────────────────────────────────────────────────
    fun getAllSmsLogs() = smsLogDao.getAllLogs()

    suspend fun getLatestSmsLog(debtId: Int, type: String) =
        smsLogDao.getLatestLogByDebtAndType(debtId, type)

    suspend fun getSmsLogsSince(debtId: Int, type: String, since: Long) =
        smsLogDao.getLogsSince(debtId, type, since)

    suspend fun insertSmsLog(log: com.example.tindago.data.local.entity.SmsLogEntity) =
        smsLogDao.insertLog(log)

    suspend fun deleteAllSmsLogs() = smsLogDao.deleteAll()

    // ── Batch operations ────────────────────────────────────────────────
    suspend fun deleteAll() {
        deleteAllProducts()
        deleteAllSpecificSales()
        deleteAllDebts()
        deleteAllPayments()
        deleteAllDebtTransactions()
        dailyEntryDao.deleteAll()
        endOfDayDao.deleteAll()
        deleteAllRestockLogs()
        deleteAllExpenses()
        deleteAllSmsLogs()
    }
}
