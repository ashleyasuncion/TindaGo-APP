package com.example.tindago.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.tindago.data.local.dao.*
import com.example.tindago.data.local.entity.*

@Database(
    entities = [
        ProductEntity::class,
        DailyEntryEntity::class,
        SpecificSaleEntity::class,
        CustomerDebtEntity::class,
        EndOfDayEntity::class,
        RestockLogEntity::class,
        DebtPaymentEntity::class,
        DebtTransactionEntity::class,
        ExpenseEntity::class,
        SmsLogEntity::class
    ],
    version = 11,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun productDao(): ProductDao
    abstract fun dailyEntryDao(): DailyEntryDao
    abstract fun specificSaleDao(): SpecificSaleDao
    abstract fun customerDebtDao(): CustomerDebtDao
    abstract fun endOfDayDao(): EndOfDayDao
    abstract fun restockLogDao(): RestockLogDao
    abstract fun debtPaymentDao(): DebtPaymentDao
    abstract fun debtTransactionDao(): DebtTransactionDao
    abstract fun expenseDao(): ExpenseDao
    abstract fun smsLogDao(): SmsLogDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?:                Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "tindago_db"
                )
                    .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }

        /**
         * v3 → v4: add the per-debt transaction ledger (web transactions[] parity).
         * Created non-destructively so existing user data survives the upgrade.
         * Schema must match DebtTransactionEntity exactly.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `debt_transactions` (`id` INTEGER NOT NULL, " +
                        "`debtId` INTEGER NOT NULL, `type` TEXT NOT NULL, `description` TEXT, " +
                        "`amount` REAL NOT NULL, `timestamp` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`), " +
                        "FOREIGN KEY(`debtId`) REFERENCES `customer_debts`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_debt_transactions_debtId` ON `debt_transactions` (`debtId`)"
                )
            }
        }

        /**
         * v4 → v5: drop the obsolete `costOfGoods` column from `end_of_day_data`.
         * Cost of Goods was removed from the Closing page with the web Restock Day
         * feature (web progress.txt, July 8 2026) — mobile parity fix.
         * ALTER TABLE DROP COLUMN needs SQLite >= 3.35 but minSdk 24 ships SQLite
         * 3.9, so use the Room-recommended recreate-and-copy pattern.
         * Non-destructive: existing rows carry over with costOfGoods dropped.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `end_of_day_data_new` (" +
                        "`date` TEXT NOT NULL, `cashInDrawer` REAL NOT NULL, " +
                        "`stockCheckDone` INTEGER NOT NULL, `debtPaymentsDone` INTEGER NOT NULL, " +
                        "`finished` INTEGER NOT NULL, `recordedSales` REAL NOT NULL, " +
                        "`actualSales` REAL NOT NULL, `salesDiff` REAL NOT NULL, " +
                        "`profit` REAL NOT NULL, PRIMARY KEY(`date`))"
                )
                db.execSQL(
                    "INSERT INTO `end_of_day_data_new` (`date`, `cashInDrawer`, `stockCheckDone`, " +
                        "`debtPaymentsDone`, `finished`, `recordedSales`, `actualSales`, " +
                        "`salesDiff`, `profit`) " +
                        "SELECT `date`, `cashInDrawer`, `stockCheckDone`, `debtPaymentsDone`, " +
                        "`finished`, `recordedSales`, `actualSales`, `salesDiff`, `profit` " +
                        "FROM `end_of_day_data`"
                )
                db.execSQL("DROP TABLE `end_of_day_data`")
                db.execSQL("ALTER TABLE `end_of_day_data_new` RENAME TO `end_of_day_data`")
            }
        }

        /**
         * v5 → v6: add the per-customer `creditLimit` column (web v2.56 parity).
         * NULL = uses the global default; 0 = no limit for that customer.
         * Non-destructive: existing debts keep their data, new column defaults NULL.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `customer_debts` ADD COLUMN `creditLimit` INTEGER")
            }
        }

        /**
         * v6 → v7: add the product identity columns (web v2.59 parity — units,
         * brands, and categories feature). All columns default to empty so
         * existing products remain valid and uncategorized.
         * Non-destructive: existing product rows keep their data.
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `products` ADD COLUMN `category` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `products` ADD COLUMN `brand` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `products` ADD COLUMN `packageSize` TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * v7 → v8: add multi-item checkout columns (web v2.63 parity).
         * `transactionId` groups all items of one checkout under a shared id;
         * `paymentMethod` records whether the sale was cash or credit.
         * Non-destructive: existing sale rows keep their data.
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `specific_sales` ADD COLUMN `transactionId` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `specific_sales` ADD COLUMN `paymentMethod` TEXT")
            }
        }

        /**
         * v8 → v9: add the expense log (web V2.71 parity — ExpenseTracking
         * analysis). Creates the `expenses` table and adds the EOD snapshot
         * columns (`expenses`, `netProfit`) so a finished closing stores the
         * day's expense total + Net Profit. Non-destructive: existing rows
         * keep their data, new columns default to 0.
         * Schema must match ExpenseEntity / EndOfDayEntity exactly.
         */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `expenses` (`id` INTEGER NOT NULL, " +
                        "`date` TEXT NOT NULL, `category` TEXT NOT NULL, " +
                        "`amount` REAL NOT NULL, `note` TEXT NOT NULL DEFAULT '', " +
                        "`timestamp` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL("ALTER TABLE `end_of_day_data` ADD COLUMN `expenses` REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `end_of_day_data` ADD COLUMN `netProfit` REAL NOT NULL DEFAULT 0")
            }
        }

        /**
         * v9 → v10: add SMS fields to customer_debts (phone number + opt-in).
         * Non-destructive: existing debts keep their data, new columns default to empty/null.
         */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `customer_debts` ADD COLUMN `phoneNumber` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `customer_debts` ADD COLUMN `smsOptIn` INTEGER")
            }
        }

        /**
         * v10 → v11: create sms_log table for tracking SMS send attempts.
         */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sms_log` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`debtId` INTEGER NOT NULL, " +
                        "`customerName` TEXT NOT NULL, " +
                        "`phoneNumber` TEXT NOT NULL, " +
                        "`type` TEXT NOT NULL, " +
                        "`messageBody` TEXT NOT NULL, " +
                        "`status` TEXT NOT NULL, " +
                        "`timestamp` INTEGER NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_sms_log_debtId` ON `sms_log` (`debtId`)"
                )
            }
        }
    }
}
