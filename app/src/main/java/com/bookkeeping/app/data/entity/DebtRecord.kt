package com.bookkeeping.app.data.entity

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 借贷记录。借出 = 别人欠我；借入 = 我欠别人。
 * 还款通过 repaid 累加冲抵，可多次部分还款，还清自动标记结清。
 *
 * 索引（v13 补）：借贷页全部查询都按 ledgerId 过滤并按 direction 汇总，
 * 原先无索引导致每次打开页面都全表扫描。
 */
@Entity(
    tableName = "debts",
    indices = [
        Index(value = ["ledgerId"]),
        Index(value = ["direction"])
    ]
)
data class DebtRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** LENT_OUT = 借出（别人欠我）/ BORROWED = 借入（我欠别人） */
    val direction: String,

    /** 对方姓名 */
    val person: String,

    /** 借款金额 */
    val amount: Double,

    /** 已收/已还金额 */
    val repaid: Double = 0.0,

    /** 备注 */
    val note: String = "",

    /** 借款时间 */
    val occurredAt: Long,

    /** 所属账本 */
    @ColumnInfo(defaultValue = "1")
    val ledgerId: Long = 1,

    val createdAt: Long = System.currentTimeMillis()
) {
    val remaining: Double get() = (amount - repaid).coerceAtLeast(0.0)
    val settled: Boolean get() = repaid >= amount - 0.005

    companion object {
        const val LENT_OUT = "LENT_OUT"
        const val BORROWED = "BORROWED"
    }
}
