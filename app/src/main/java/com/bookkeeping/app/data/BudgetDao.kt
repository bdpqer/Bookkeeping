package com.bookkeeping.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.bookkeeping.app.data.entity.Budget
import kotlinx.coroutines.flow.Flow

@Dao
interface BudgetDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(budget: Budget): Long

    @Query("SELECT * FROM budgets WHERE ledgerId = :ledgerId")
    suspend fun getByLedger(ledgerId: Long): Budget?

    @Query("SELECT * FROM budgets")
    suspend fun getAll(): List<Budget>

    /** 设置页列表：账本 + 预算（左连接，无预算的账本也出现） */
    @Query("""
        SELECT l.id AS ledgerId, l.name AS ledgerName, l.icon AS ledgerIcon,
               COALESCE(b.monthlyAmount, 0.0) AS monthlyAmount
        FROM ledgers l LEFT JOIN budgets b ON b.ledgerId = l.id
        ORDER BY l.isDefault DESC, l.id ASC
    """)
    fun observeAllWithLedger(): Flow<List<BudgetWithLedger>>

    /** 全部账本预算合计（首页"全部账本"视图展示用） */
    @Query("SELECT COALESCE(SUM(monthlyAmount), 0.0) FROM budgets")
    suspend fun sumAllAmounts(): Double

    /** 改预算金额并重置当月已提醒状态（金额变了，旧超支结论失效） */
    @Query("UPDATE budgets SET monthlyAmount = :amount, notifiedMonth = NULL, updatedAt = :ts WHERE ledgerId = :ledgerId")
    suspend fun updateAmount(ledgerId: Long, amount: Double, ts: Long)

    /** 账本级节流：标记本月已提醒 */
    @Query("UPDATE budgets SET notifiedMonth = :month WHERE ledgerId = :ledgerId")
    suspend fun markNotified(ledgerId: Long, month: String)

    /** 取消预算（amount <= 0） */
    @Query("DELETE FROM budgets WHERE ledgerId = :ledgerId")
    suspend fun deleteByLedger(ledgerId: Long)

    /** 删除账本后遗留的孤儿预算行（启动清理） */
    @Query("DELETE FROM budgets WHERE ledgerId NOT IN (SELECT id FROM ledgers)")
    suspend fun deleteOrphans()
}

/** 设置页展示用：账本 + 预算 */
data class BudgetWithLedger(
    val ledgerId: Long,
    val ledgerName: String,
    val ledgerIcon: String,
    val monthlyAmount: Double
)
