package com.bookkeeping.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.bookkeeping.app.data.entity.DebtRecord

@Dao
interface DebtDao {

    /** 未结清在前，其余按时间倒序 */
    @Query("SELECT * FROM debts ORDER BY (amount - repaid) > 0.005 DESC, occurredAt DESC")
    suspend fun getAll(): List<DebtRecord>

    /** 按账本查 */
    @Query("SELECT * FROM debts WHERE ledgerId = :ledgerId ORDER BY (amount - repaid) > 0.005 DESC, occurredAt DESC")
    suspend fun getByLedger(ledgerId: Long): List<DebtRecord>

    @Insert
    suspend fun insert(debt: DebtRecord): Long

    @Query("UPDATE debts SET repaid = :repaid WHERE id = :id")
    suspend fun updateRepaid(id: Long, repaid: Double)

    /** 别人还欠我的合计 */
    @Query("SELECT COALESCE(SUM(amount - repaid), 0) FROM debts WHERE direction = 'LENT_OUT' AND (amount - repaid) > 0.005")
    suspend fun totalLentOut(): Double

    /** 我还欠别人的合计 */
    @Query("SELECT COALESCE(SUM(amount - repaid), 0) FROM debts WHERE direction = 'BORROWED' AND (amount - repaid) > 0.005")
    suspend fun totalBorrowed(): Double

    /** 按账本：别人还欠我的合计 */
    @Query("SELECT COALESCE(SUM(amount - repaid), 0) FROM debts WHERE direction = 'LENT_OUT' AND (amount - repaid) > 0.005 AND ledgerId = :ledgerId")
    suspend fun totalLentOutByLedger(ledgerId: Long): Double

    /** 按账本：我还欠别人的合计 */
    @Query("SELECT COALESCE(SUM(amount - repaid), 0) FROM debts WHERE direction = 'BORROWED' AND (amount - repaid) > 0.005 AND ledgerId = :ledgerId")
    suspend fun totalBorrowedByLedger(ledgerId: Long): Double

    @Query("SELECT COUNT(*) FROM debts")
    suspend fun count(): Int

    @Query("DELETE FROM debts WHERE id = :id")
    suspend fun delete(id: Long)
}
