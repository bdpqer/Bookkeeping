package com.bookkeeping.app.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 解析规则：可配置化的正则匹配模板。
 * 用户可以在设置里开关/调整优先级。
 */
@Entity(tableName = "parse_rules")
data class ParseRule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** 渠道标识：wechat_pay / alipay / cn.com.spdb.mobilebank.per（包名也行） */
    val channel: String,

    /** 显示名：微信支付 / 支付宝 / 浦发银行 */
    val channelName: String,

    /** 匹配该渠道的关键词（用于先筛选） */
    val matchKeyword: String,

    /** 金额正则（必须捕获金额数字部分） */
    val patternAmount: String,

    /** 类型判断正则：匹配到 = 支出/收入/转账 */
    val patternExpense: String = "支出|消费|扣款|支付|汇出|转出",
    val patternIncome: String = "收入|入账|汇款转入|转入|到账|退款",
    val patternTransfer: String = "转账|互联汇出|互联汇入",

    /** 时间提取正则（可选，匹配后提取第一个捕获组作为时间字符串） */
    val patternTime: String? = null,

    /** 商户/对方提取正则（可选） */
    val patternMerchant: String? = null,

    /** 是否启用 */
    val isEnabled: Boolean = true,

    /** 优先级：数字越大越先匹配 */
    val priority: Int = 10
)
