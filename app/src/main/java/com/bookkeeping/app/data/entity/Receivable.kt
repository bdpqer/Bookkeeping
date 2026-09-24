package com.bookkeeping.app.data.entity

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 应收/应付款记录 —— 预期资金流（还没发生的钱）
 * 和借贷的区别：借贷有实际资金流动（借出/借入），应收应付是"将要发生"的。
 */
@Entity(tableName = "receivables", indices = [
    Index(value = ["direction"]),
    Index(value = ["status"]),
    Index(value = ["dueDate"]),
])
data class Receivable(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** 方向：RECEIVABLE=应收款（别人要给我钱）, PAYABLE=应付款（我要给别人钱） */
    val direction: Direction,

    /** 对方：公司/房东/银行/客户... */
    val counterparty: String,

    /** 金额（正数） */
    val amount: Double,

    /** 到期日期（毫秒时间戳） */
    val dueDate: Long,

    /** 描述/备注 */
    val description: String,

    /** 可选分类：房租/信用卡/物业费/报销待回款... */
    val category: String = "",

    /** 状态 */
    val status: Status = Status.PENDING,

    /** 所属账本 */
    @ColumnInfo(defaultValue = "1")
    val ledgerId: Long = 1,

    val createdAt: Long = System.currentTimeMillis()
) {
    enum class Direction { RECEIVABLE, PAYABLE }
    enum class Status { PENDING, DONE }
}
