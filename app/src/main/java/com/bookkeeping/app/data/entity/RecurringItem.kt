package com.bookkeeping.app.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 周期规则 = 账单提醒 + 周期记账。
 *
 * 示例：
 *  - 每月 15 号发工资（周期记账，自动生成一笔收入）
 *  - 每月 10 号房贷还款提醒（账单提醒，推送通知但不自动记账）
 *  - 信用卡每月 25 号还款日提醒
 */
@Entity(tableName = "recurring_items", indices = [
    Index(value = ["nextRunAt"]),
    Index(value = ["isEnabled"])
])
data class RecurringItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** 名称："工资"、"房贷"、"信用卡还款" */
    val name: String,

    /** 类型：REMIND（只提醒）或 AUTO_TX（自动生成交易） */
    val mode: Mode,

    /** 周期类型 */
    val period: Period,

    /** 每月第几天（1-31），period=MONTHLY 时有效 */
    val dayOfMonth: Int? = null,

    /** 每周几（1=周一..7=周日），period=WEEKLY 时有效 */
    val dayOfWeek: Int? = null,

    /** 起始日期（首次运行日期的毫秒时间戳） */
    val startDate: Long,

    /** 金额（AUTO_TX 时必填，REMIND 时可选显示） */
    val amount: Double? = null,

    /** 类型（收入/支出），AUTO_TX 时必填 */
    val txType: TxType? = null,

    /** 分类，AUTO_TX 时必填 */
    val category: String? = null,

    /** 备注/描述 */
    val note: String = "",

    /** 下次应该运行的时间（毫秒）—— Worker 扫这个字段调度 */
    val nextRunAt: Long,

    /** 是否已启用 */
    val isEnabled: Boolean = true,

    /** 已运行次数（v7 新增） */
    @ColumnInfo(defaultValue = "0") val runCount: Int = 0,

    /** 结束条件（v7 新增） */
    @ColumnInfo(defaultValue = "'NEVER'") val endMode: EndMode = EndMode.NEVER,

    /** 运行多少次后结束，endMode=AFTER_COUNT 时有效 */
    val endAfterCount: Int? = null,

    /** 结束日期，endMode=ON_DATE 时有效（下次运行超过此日期则停用） */
    val endDate: Long? = null
) {
    enum class Mode { REMIND, AUTO_TX }
    enum class Period { DAILY, WEEKLY, MONTHLY, YEARLY }
    enum class TxType { EXPENSE, INCOME }
    enum class EndMode { NEVER, AFTER_COUNT, ON_DATE }
}
