package com.bookkeeping.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.bookkeeping.app.data.entity.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(tx: Transaction): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfNew(tx: Transaction): Long

    @Query("SELECT * FROM transactions WHERE confirmed = 1 AND deletedAt = 0 ORDER BY occurredAt DESC")
    suspend fun getAll(): List<Transaction>

    /** Flow 变体：数据库变化自动重发，供首页/明细页 collectAsState 实时刷新 */
    @Query("SELECT * FROM transactions WHERE confirmed = 1 AND deletedAt = 0 ORDER BY occurredAt DESC")
    fun observeAll(): Flow<List<Transaction>>

    @Query("SELECT * FROM transactions WHERE ledgerId = :ledgerId AND confirmed = 1 AND deletedAt = 0 ORDER BY occurredAt DESC")
    fun observeByLedger(ledgerId: Long): Flow<List<Transaction>>

    @Query("SELECT * FROM transactions WHERE confirmed = 1 AND deletedAt = 0 ORDER BY occurredAt DESC")
    suspend fun getAllIncludingUnconfirmed(): List<Transaction>

    @Query("SELECT * FROM transactions WHERE confirmed = 1 AND deletedAt = 0 ORDER BY occurredAt DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<Transaction>

    @Query("SELECT * FROM transactions WHERE occurredAt BETWEEN :start AND :end AND confirmed = 1 AND deletedAt = 0 ORDER BY occurredAt DESC")
    suspend fun getByTimeRange(start: Long, end: Long): List<Transaction>

    /** 待确认队列：confirmed = false */
    @Query("SELECT * FROM transactions WHERE confirmed = 0 AND deletedAt = 0 ORDER BY occurredAt DESC")
    suspend fun getPending(): List<Transaction>

    @Query("SELECT COUNT(*) FROM transactions WHERE confirmed = 0 AND deletedAt = 0")
    suspend fun getPendingCount(): Int

    /** 确认入账 */
    @Query("UPDATE transactions SET confirmed = 1 WHERE id = :id")
    suspend fun confirm(id: Long)

    /** 批量确认 */
    @Query("UPDATE transactions SET confirmed = 1 WHERE confirmed = 0")
    suspend fun confirmAll()

    /** Flow 变体：明细页搜索 */
    @Query("""
        SELECT * FROM transactions WHERE confirmed = 1 AND deletedAt = 0
          AND (merchant LIKE '%' || :keyword || '%'
               OR note LIKE '%' || :keyword || '%'
               OR rawText LIKE '%' || :keyword || '%')
        ORDER BY occurredAt DESC
    """)
    fun observeSearch(keyword: String): Flow<List<Transaction>>

    /** Flow 变体：明细页按账本 + 关键词搜索 */
    @Query("""
        SELECT * FROM transactions WHERE confirmed = 1 AND deletedAt = 0 AND ledgerId = :ledgerId
          AND (merchant LIKE '%' || :keyword || '%'
               OR note LIKE '%' || :keyword || '%'
               OR rawText LIKE '%' || :keyword || '%')
        ORDER BY occurredAt DESC
    """)
    fun observeSearchByLedger(ledgerId: Long, keyword: String): Flow<List<Transaction>>

    /** 首页搜索弹窗：商户/分类/备注模糊 + 金额前缀，SQL 下推避免全表内存过滤 */
    @Query("""
        SELECT * FROM transactions WHERE confirmed = 1 AND deletedAt = 0
          AND (merchant LIKE '%' || :q || '%'
               OR category LIKE '%' || :q || '%'
               OR note LIKE '%' || :q || '%'
               OR printf('%.2f', amount) LIKE :qFmt || '%')
        ORDER BY occurredAt DESC
        LIMIT 30
    """)
    suspend fun searchQuick(q: String, qFmt: String): List<Transaction>

    @Query("""
        SELECT * FROM transactions
        WHERE ABS(amount - :amount) < 0.01
          AND type = :type
          AND merchant = :merchant
          AND occurredAt BETWEEN :since AND :until
          AND deletedAt = 0
    """)
    suspend fun findDuplicate(amount: Double, type: String, merchant: String, since: Long, until: Long): List<Transaction>

    @Query("""
        SELECT COALESCE(SUM(amount), 0.0) FROM transactions 
        WHERE type = :type AND confirmed = 1 AND deletedAt = 0 AND occurredAt BETWEEN :start AND :end
    """)
    suspend fun sumAmount(type: String, start: Long, end: Long): Double

    @Update
    suspend fun update(tx: Transaction)

    /** 报销相关 */
    @Query("SELECT * FROM transactions WHERE reimburseStatus = :status AND confirmed = 1 AND deletedAt = 0 ORDER BY occurredAt DESC")
    suspend fun getReimburseByStatus(status: String): List<Transaction>

    @Query("UPDATE transactions SET reimburseStatus = :status WHERE id = :id")
    suspend fun updateReimburseStatus(id: Long, status: String)

    @Query("UPDATE transactions SET reimburseStatus = :status WHERE id IN (:ids)")
    suspend fun batchUpdateReimburseStatus(ids: List<Long>, status: String)

    /** 回收站相关（软删除） */
    @Query("SELECT * FROM transactions WHERE deletedAt > 0 ORDER BY deletedAt DESC")
    suspend fun getDeleted(): List<Transaction>

    @Query("UPDATE transactions SET deletedAt = :ts WHERE id = :id")
    suspend fun softDelete(id: Long, ts: Long)

    @Query("UPDATE transactions SET deletedAt = 0 WHERE id = :id")
    suspend fun restore(id: Long)

    /** 历史数据修复：无账本关联的交易归入默认账本（账户缺失时补第一个账户） */
    @Query("UPDATE transactions SET ledgerId = :ledgerId, accountId = COALESCE(accountId, :accountId) WHERE ledgerId IS NULL AND deletedAt = 0")
    suspend fun fixNullAssociations(ledgerId: Long, accountId: Long?)

    @Query("DELETE FROM transactions WHERE id = :id AND deletedAt > 0")
    suspend fun purge(id: Long)

    @Query("DELETE FROM transactions WHERE deletedAt > 0")
    suspend fun purgeAll()

    /** 清除删除时间早于 cutoff 的记录 */
    @Query("DELETE FROM transactions WHERE deletedAt > 0 AND deletedAt < :cutoff")
    suspend fun purgeOlderThan(cutoff: Long)

    data class CategorySum(val category: String, val total: Double)
    data class DaySum(val dayBucket: Long, val expense: Double, val income: Double)
}
