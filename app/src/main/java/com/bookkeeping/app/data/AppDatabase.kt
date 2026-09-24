package com.bookkeeping.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.bookkeeping.app.data.entity.Account
import com.bookkeeping.app.data.entity.DebtRecord
import com.bookkeeping.app.data.entity.InstallmentPlan
import com.bookkeeping.app.data.entity.Ledger
import com.bookkeeping.app.data.entity.MerchantRule
import com.bookkeeping.app.data.entity.ParseRule
import com.bookkeeping.app.data.entity.RecurringItem
import com.bookkeeping.app.data.entity.Receivable
import com.bookkeeping.app.data.entity.Transaction

@Database(
    entities = [Transaction::class, ParseRule::class, Account::class, Ledger::class, RecurringItem::class, MerchantRule::class, DebtRecord::class, Receivable::class, InstallmentPlan::class],
    version = 10,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun transactionDao(): TransactionDao
    abstract fun parseRuleDao(): ParseRuleDao
    abstract fun accountDao(): AccountDao
    abstract fun ledgerDao(): LedgerDao
    abstract fun recurringDao(): RecurringDao
    abstract fun merchantRuleDao(): MerchantRuleDao
    abstract fun debtDao(): DebtDao
    abstract fun receivableDao(): ReceivableDao
    abstract fun installmentDao(): InstallmentDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /** v4 → v5：新增借贷表 debts（保留现有数据） */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS debts (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        direction TEXT NOT NULL,
                        person TEXT NOT NULL,
                        amount REAL NOT NULL,
                        repaid REAL NOT NULL,
                        note TEXT NOT NULL,
                        occurredAt INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        /** v5 → v6：新增应收/应付款表 receivables */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS receivables (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        direction TEXT NOT NULL,
                        counterparty TEXT NOT NULL,
                        amount REAL NOT NULL,
                        dueDate INTEGER NOT NULL,
                        description TEXT NOT NULL,
                        category TEXT NOT NULL,
                        status TEXT NOT NULL,
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_receivables_direction ON receivables(direction)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_receivables_status ON receivables(status)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_receivables_dueDate ON receivables(dueDate)")
            }
        }

        /** v6 → v7：周期任务加结束条件 + 新增信用卡账单分期表 installment_plans */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 周期任务新增字段（默认值必须与 @ColumnInfo(defaultValue=...) 一致）
                db.execSQL("ALTER TABLE recurring_items ADD COLUMN runCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE recurring_items ADD COLUMN endMode TEXT NOT NULL DEFAULT 'NEVER'")
                db.execSQL("ALTER TABLE recurring_items ADD COLUMN endAfterCount INTEGER")
                db.execSQL("ALTER TABLE recurring_items ADD COLUMN endDate INTEGER")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS installment_plans (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        accountId INTEGER NOT NULL,
                        totalAmount REAL NOT NULL,
                        installments INTEGER NOT NULL,
                        firstDate INTEGER NOT NULL,
                        totalFee REAL NOT NULL,
                        feeMode TEXT NOT NULL,
                        feeIntoDebt INTEGER NOT NULL,
                        remainderInto TEXT NOT NULL,
                        category TEXT NOT NULL,
                        paidPeriods INTEGER NOT NULL,
                        status TEXT NOT NULL,
                        note TEXT NOT NULL,
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_installment_plans_status ON installment_plans(status)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_installment_plans_accountId ON installment_plans(accountId)")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN reimburseStatus TEXT")
                // 将现有 category="报销" 的 EXPENSE 记为 PENDING
                db.execSQL("UPDATE transactions SET reimburseStatus = 'PENDING' WHERE category = '报销' AND type = 'EXPENSE'")
                // 将现有 category="报销" 的 INCOME 记为 DONE
                db.execSQL("UPDATE transactions SET reimburseStatus = 'DONE' WHERE category = '报销' AND type = 'INCOME'")
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE receivables ADD COLUMN ledgerId INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE debts ADD COLUMN ledgerId INTEGER NOT NULL DEFAULT 1")
                // 现有记录归入默认账本
                db.execSQL("""
                    UPDATE receivables SET ledgerId =
                    COALESCE((SELECT id FROM ledgers WHERE isDefault = 1 LIMIT 1), 1)
                """)
                db.execSQL("""
                    UPDATE debts SET ledgerId =
                    COALESCE((SELECT id FROM ledgers WHERE isDefault = 1 LIMIT 1), 1)
                """)
            }
        }

        /** v9 → v10：回收站（软删除标记 deletedAt） */
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN deletedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_transactions_deletedAt ON transactions(deletedAt)")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "bookkeeping.db"
                )
                    .addMigrations(MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }

        /** 关闭并清空单例（恢复备份前调用，之后需重启进程） */
        fun closeInstance() {
            synchronized(this) {
                INSTANCE?.close()
                INSTANCE = null
            }
        }
    }
}
