package com.bookkeeping.app.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 交易记录。所有自动/手动记账都存在这里。
 *
 * 索引设计（v13 起）：原先是 8 个单列索引，每次 insert 要维护 8 棵 B-tree，
 * 而最高频的查询 `WHERE confirmed = 1 AND deletedAt = 0 ORDER BY occurredAt`
 * 只能吃到其中一个，第二个条件退化为过滤。改为按实际查询模式建复合索引：
 *  - [ACTIVE_TIME]：覆盖「已确认 + 未删除 + 按时间排序」，可同时用于过滤与排序，无需临时排序
 *  - [PENDING_TIME]：待确认队列
 *  - [DELETED_TIME]：回收站
 *  - [LEDGER_TIME]：按账本筛选
 *  - [REIMBURSE]：[reimburseStatus] 过滤（报销页高频，原先无索引导致全表扫描）
 * 删掉了从未单独用于过滤的 category / source / accountId 索引（source 索引完全无查询使用）。
 */
@Entity(tableName = "transactions", indices = [
    Index(value = ["confirmed", "deletedAt", "occurredAt"]),
    Index(value = ["confirmed", "deletedAt", "occurredAt", "ledgerId"]),
    Index(value = ["deletedAt", "occurredAt"]),
    Index(value = ["reimburseStatus"]),
])
data class Transaction(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** 金额（正数，type 决定收支） */
    val amount: Double,

    /** 交易类型 */
    val type: Type,

    /** 分类：餐饮/交通/工资...（系统内置或用户自定义） */
    val category: String,

    /** 商户/对方名称（可能为空） */
    val merchant: String = "",

    /** 来源渠道：微信支付/浦发银行/手动... */
    val source: String = "",

    /** 账户 ID（null = 未分配） */
    val accountId: Long? = null,

    /** 账本 ID（null = 默认账本） */
    val ledgerId: Long? = null,

    /** 备注 */
    val note: String = "",

    /** 原始文本（短信正文 / 通知 text+title 拼接）—— 用于调试和后续规则更新 */
    val rawText: String,

    /** 来源：自动还是手动 */
    val isManual: Boolean = false,

    /** 是否已确认入账：解析置信度 HIGH 默认 true；LOW/MEDIUM 自动解析的设为 false → 待确认队列 */
    val confirmed: Boolean = true,

    /** 解析置信度：高=自动入账，低=进入待确认 */
    val confidence: Confidence = Confidence.HIGH,

    /** 交易发生时间（用户感知的时间，不是入库时间） */
    val occurredAt: Long,

    /** 入库时间 */
    val createdAt: Long = System.currentTimeMillis(),

    /** 报销状态：null=非报销项，"PENDING"=待报销，"DONE"=已报销 */
    val reimburseStatus: String? = null,

    /** 回收站：0=正常，>0=被删除的时间戳（30 天后自动清除） */
    @ColumnInfo(defaultValue = "0") val deletedAt: Long = 0
) {
    enum class Type { EXPENSE, INCOME, TRANSFER }
    enum class Confidence { HIGH, MEDIUM, LOW }
}
