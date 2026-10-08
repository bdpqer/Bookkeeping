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
    // 仅在 CSV 合并导入时有用：同一文件可能被多次导入，OnConflict.IGNORE 会让
    // id 已存在的记录返回 -1L 自动跳过。普通单文件导入由于自增 id 不会冲突，-1 永不命中。

    @Query("SELECT * FROM transactions WHERE confirmed = 1 AND deletedAt = 0 ORDER BY occurredAt DESC")
    suspend fun getAll(): List<Transaction>

    /** Flow 变体：数据库变化自动重发，供首页/明细页 collectAsState 实时刷新 */
    @Query("SELECT * FROM transactions WHERE confirmed = 1 AND deletedAt = 0 ORDER BY occurredAt DESC")
    fun observeAll(): Flow<List<Transaction>>

    @Query("SELECT * FROM transactions WHERE ledgerId = :ledgerId AND confirmed = 1 AND deletedAt = 0 ORDER BY occurredAt DESC")
    fun observeByLedger(ledgerId: Long): Flow<List<Transaction>>

    /** 全量导出（含待确认，不含回收站）：CSV 导出用 */
    @Query("SELECT * FROM transactions WHERE deletedAt = 0 ORDER BY occurredAt DESC")
    suspend fun getAllIncludingUnconfirmed(): List<Transaction>

    @Query("SELECT * FROM transactions WHERE confirmed = 1 AND deletedAt = 0 ORDER BY occurredAt DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<Transaction>

    /** 按账本汇总：预算超支检查与报表专用（避免跨账本累加） */
    @Query("""
        SELECT COALESCE(SUM(amount), 0.0) FROM transactions
        WHERE ledgerId = :ledgerId AND type = :type AND confirmed = 1 AND deletedAt = 0
          AND occurredAt BETWEEN :start AND :end
    """)
    suspend fun sumByLedger(ledgerId: Long, type: String, start: Long, end: Long): Double

    /** 待确认队列：confirmed = false（Flow 订阅，入库/确认/删除由 Room 自动推送，无需手动刷新） */
    @Query("SELECT * FROM transactions WHERE confirmed = 0 AND deletedAt = 0 ORDER BY occurredAt DESC")
    fun observePending(): Flow<List<Transaction>>

    @Query("SELECT COUNT(*) FROM transactions WHERE confirmed = 0 AND deletedAt = 0")
    fun observePendingCount(): Flow<Int>

    /** 确认入账。加 deletedAt = 0 与 observePending 保持一致：回收站中的记录不该被确认 */
    @Query("UPDATE transactions SET confirmed = 1 WHERE id = :id AND deletedAt = 0")
    suspend fun confirm(id: Long)

    /** 批量确认。同样排除回收站，否则一键确认会把回收站里的记录一起翻正 */
    @Query("UPDATE transactions SET confirmed = 1 WHERE confirmed = 0 AND deletedAt = 0")
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

    /**
     * 重复检测：同金额 / 同类型 / 同商户 / 时间窗内。
     *
     * 金额用「分」的整数区间比较（`amount * 100` 加减 1 分），
     * 避免原先 `ABS(amount - :amount) < 0.01` 两种问题：
     *  1. 函数包裹列导致索引用不上
     *  2. 浮点误差下两笔真实不同的交易（相差不足 1 分）会被误判为重复而丢弃
     */
    @Query("""
        SELECT * FROM transactions
        WHERE amount * 100 BETWEEN :amountCents - 1 AND :amountCents + 1
          AND type = :type
          AND merchant = :merchant
          AND occurredAt BETWEEN :since AND :until
          AND deletedAt = 0
    """)
    suspend fun findDuplicate(
        amountCents: Long,
        type: String,
        merchant: String,
        since: Long,
        until: Long
    ): List<Transaction>

    @Query("""
        SELECT COALESCE(SUM(amount), 0.0) FROM transactions 
        WHERE type = :type AND confirmed = 1 AND deletedAt = 0 AND occurredAt BETWEEN :start AND :end
    """)
    suspend fun sumAmount(type: String, start: Long, end: Long): Double

    /**
     * 某账户「现存交易」对余额的净影响（收入 +、支出 -、转账 0），口径同
     * [com.bookkeeping.app.balanceDelta]。用于按交易重算账户余额。
     *
     * 只统计 deletedAt = 0（回收站里的不算），含待确认 —— 与 applyBalance 在写入时
     * 不区分 confirmed 的行为保持一致，否则重算会把待确认那部分又抹掉。
     */
    @Query("""
        SELECT COALESCE(SUM(
            CASE type WHEN 'INCOME' THEN amount WHEN 'EXPENSE' THEN -amount ELSE 0 END
        ), 0.0)
        FROM transactions WHERE accountId = :accountId AND deletedAt = 0
    """)
    suspend fun sumBalanceDelta(accountId: Long): Double

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
