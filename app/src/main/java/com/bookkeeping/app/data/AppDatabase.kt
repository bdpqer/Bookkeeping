package com.bookkeeping.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.bookkeeping.app.data.entity.Account
import com.bookkeeping.app.data.entity.Budget
import com.bookkeeping.app.data.entity.DebtRecord
import com.bookkeeping.app.data.entity.InstallmentPlan
import com.bookkeeping.app.data.entity.Ledger
import com.bookkeeping.app.data.entity.MerchantRule
import com.bookkeeping.app.data.entity.ParseRule
import com.bookkeeping.app.data.entity.RecurringItem
import com.bookkeeping.app.data.entity.Receivable
import com.bookkeeping.app.data.entity.Transaction

@Database(
    entities = [Transaction::class, ParseRule::class, Account::class, Ledger::class, RecurringItem::class, MerchantRule::class, DebtRecord::class, Receivable::class, InstallmentPlan::class, Budget::class],
    version = 12,
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
    abstract fun budgetDao(): BudgetDao

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

        /** v10 → v11：新增按账本预算表 budgets（金额迁移见 BookkeepingApp.migrateLegacyBudgetPrefs） */
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS budgets (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        ledgerId INTEGER NOT NULL,
                        monthlyAmount REAL NOT NULL,
                        notifiedMonth TEXT,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_budgets_ledgerId ON budgets(ledgerId)")
            }
        }

        /** v11 → v12：分期计划支持类型（自动记账/仅提醒）与记账账本 */
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 现有分期默认保持 AUTO_TX（自动入账）行为，账本为 NULL = 跟随账户/默认账本
                db.execSQL("ALTER TABLE installment_plans ADD COLUMN ledgerId INTEGER")
                db.execSQL("ALTER TABLE installment_plans ADD COLUMN mode TEXT NOT NULL DEFAULT 'AUTO_TX'")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_installment_plans_ledgerId ON installment_plans(ledgerId)")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "bookkeeping.db"
                )
                    .addMigrations(MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12)
                    // 兜底迁移：理论上永远不会走到，因为已配齐 v4→v12 所有 Migration。
                    // 真走到这里说明某次发布忘了写 Migration，用户数据会被清空——务必显著日志。
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
