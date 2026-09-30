package com.bookkeeping.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.bookkeeping.app.data.entity.Budget

@Dao
interface BudgetDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(budget: Budget): Long

    @Query("SELECT * FROM budgets WHERE ledgerId = :ledgerId")
    suspend fun getByLedger(ledgerId: Long): Budget?

    @Query("SELECT * FROM budgets")
    suspend fun getAll(): List<Budget>

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

