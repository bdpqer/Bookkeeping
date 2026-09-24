package com.bookkeeping.app.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 账户：现金/银行卡/微信/支付宝/信用卡 等。
 * 每个账户独立追踪余额。
 */
@Entity(tableName = "accounts", indices = [
    Index(value = ["name"], unique = true)
])
data class Account(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** 账户名称，如"浦发银行卡"、"微信零钱" */
    val name: String,

    /** 类型：现金/银行卡/微信/支付宝/信用卡/其他 */
    val type: AccountType,

    /** 当前余额 */
    val balance: Double = 0.0,

    /** 信用卡账单日（1-31），仅信用卡有效 */
    val billDay: Int? = null,

    /** 信用卡还款日（1-31），仅信用卡有效 */
    val repayDay: Int? = null,

    /** Emoji 图标 */
    val icon: String = "💰",

    /** 是否启用 */
    val isEnabled: Boolean = true
) {
    enum class AccountType { CASH, BANK, WECHAT, ALIPAY, CREDIT_CARD, OTHER }
}
