package com.bookkeeping.app.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.Calendar

/**
 * 信用卡账单分期计划。
 *
 * 创建后由 RecurringWorker 按月检查：到期的期数自动生成一笔支出交易
 * （本金 + 当期手续费），全部期数入账后状态变为 DONE。
 */
@Entity(tableName = "installment_plans", indices = [
    Index(value = ["status"]),
    Index(value = ["accountId"])
])
data class InstallmentPlan(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** 信用账户 id */
    val accountId: Long,

    /** 分期总金额 */
    val totalAmount: Double,

    /** 分期期数 */
    val installments: Int,

    /** 首笔入账日期（毫秒，日期零点） */
    val firstDate: Long,

    /** 手续费总额 */
    val totalFee: Double = 0.0,

    /** 手续费收取方式 */
    val feeMode: FeeMode = FeeMode.MONTHLY_AVG,

    /** 未入账的手续费计入总欠款（仅影响展示语义） */
    val feeIntoDebt: Boolean = false,

    /** 除不尽的余数（分）计入哪一期 */
    val remainderInto: RemainderTarget = RemainderTarget.FIRST,

    /** 交易分类 */
    val category: String = "信用卡还款",

    /** 已入账期数 */
    val paidPeriods: Int = 0,

    /** 状态 */
    val status: Status = Status.ACTIVE,

    /** 备注 */
    val note: String = "",

    val createdAt: Long = System.currentTimeMillis()
) {
    enum class FeeMode { MONTHLY_AVG, FIRST_PERIOD }
    enum class RemainderTarget { FIRST, LAST }
    enum class Status { ACTIVE, DONE }
}

/**
 * 第 periodNo 期（1-based）应入账的 (本金, 手续费)。
 * 金额按"分"拆分：每期均摊，余数（分）按 remainderInto 计入首期或末期。
 */
fun installmentPeriodAmounts(plan: InstallmentPlan, periodNo: Int): Pair<Double, Double> {
    val n = plan.installments.coerceAtLeast(1)

    val totalCents = Math.round(plan.totalAmount * 100)
    val base = totalCents / n
    val principalRem = totalCents - base * n
    var principal = base
    when (plan.remainderInto) {
        InstallmentPlan.RemainderTarget.FIRST -> if (periodNo == 1) principal += principalRem
        InstallmentPlan.RemainderTarget.LAST -> if (periodNo == n) principal += principalRem
    }

    var fee: Long = 0
    when (plan.feeMode) {
        InstallmentPlan.FeeMode.FIRST_PERIOD -> if (periodNo == 1) fee = Math.round(plan.totalFee * 100)
        InstallmentPlan.FeeMode.MONTHLY_AVG -> {
            val feeCents = Math.round(plan.totalFee * 100)
            val feeBase = feeCents / n
            val feeRem = feeCents - feeBase * n
            fee = feeBase
            when (plan.remainderInto) {
                InstallmentPlan.RemainderTarget.FIRST -> if (periodNo == 1) fee += feeRem
                InstallmentPlan.RemainderTarget.LAST -> if (periodNo == n) fee += feeRem
            }
        }
    }

    return principal / 100.0 to fee / 100.0
}

/** 第 periodNo 期（1-based）的入账日期：首笔日期顺延对应月数，月末自动钳制 */
fun installmentDueDate(firstDate: Long, periodNo: Int): Long {
    val cal = Calendar.getInstance().apply { timeInMillis = firstDate }
    val day = cal.get(Calendar.DAY_OF_MONTH)
    cal.add(Calendar.MONTH, periodNo - 1)
    cal.set(Calendar.DAY_OF_MONTH, day.coerceAtMost(cal.getActualMaximum(Calendar.DAY_OF_MONTH)))
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    return cal.timeInMillis
}
