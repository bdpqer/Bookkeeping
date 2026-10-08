package com.bookkeeping.app

import com.bookkeeping.app.data.AppDatabase
import com.bookkeeping.app.data.entity.Transaction

/**
 * 账户余额的增减口径。
 *
 * 全项目所有「生成/修改/删除交易」的地方都必须经过这里，否则账户余额会单向漂移：
 * 早先只有手动新增（`ManualAddDialog`）调整过余额，编辑金额、软删除、彻底删除、
 * 回收站恢复、周期自动记账、账单分期、报销入账、借贷还款都不动余额。
 */

/** 某笔交易对账户余额的影响：支出减、收入加、转账不影响（只是账户间搬家，由具体转账逻辑处理） */
internal fun balanceDelta(type: Transaction.Type, amount: Double): Double = when (type) {
    Transaction.Type.EXPENSE -> -amount
    Transaction.Type.INCOME -> amount
    Transaction.Type.TRANSFER -> 0.0
}

/** 新增交易后计入余额 */
internal suspend fun applyBalance(db: AppDatabase, tx: Transaction) {
    val id = tx.accountId ?: return
    val delta = balanceDelta(tx.type, tx.amount)
    if (delta != 0.0) db.accountDao().adjustBalance(id, delta)
    // 写入那一刻（App 在前台）即通知 Widget 刷新，绕开后台 AppWidget 更新节流
    BookkeepingApp.notifyTransactionChanged()
}

/** 删除/移入回收站后，把该笔交易此前对余额的影响反向冲回 */
internal suspend fun revertBalance(db: AppDatabase, tx: Transaction) {
    val id = tx.accountId ?: return
    val delta = -balanceDelta(tx.type, tx.amount)
    if (delta != 0.0) db.accountDao().adjustBalance(id, delta)
    // 删除那一刻（App 在前台）即通知 Widget 刷新，绕开后台 AppWidget 更新节流
    BookkeepingApp.notifyTransactionChanged()
}

/**
 * 编辑交易：先按旧值反向冲回，再按新值计入。
 *
 * 两步分开而不是直接算差额，是因为账户本身可能被换掉
 * （旧账户要冲回、新账户要计入）。
 */
internal suspend fun reapplyBalance(db: AppDatabase, before: Transaction, after: Transaction) {
    revertBalance(db, before)
    applyBalance(db, after)
}

/**
 * 按「现存交易」重算单个账户余额，返回重算后的值。
 *
 * 账户余额是增量累加出来的存量字段：任何一条路径漏调 applyBalance/revertBalance，
 * 或者被重复调用（历史上出现过 -20 亿这种量级的漂移），差额都会永久留在库里，
 * 靠后续记再多的账也对不回来。所以提供一次整体重算来兜底。
 *
 * accountId 为 null 的交易不归属任何账户，天然不参与（与 applyBalance 一致）。
 */
internal suspend fun recalcBalance(db: AppDatabase, accountId: Long): Double {
    val correct = db.transactionDao().sumBalanceDelta(accountId)
    db.accountDao().setBalance(accountId, correct)
    return correct
}

/**
 * 重算所有账户（含已停用）。返回「账户名 → (旧值, 新值)」的对照，
 * 只含发生变化的那部分，供 UI 提示用户改了什么。
 */
internal suspend fun recalcAllBalances(db: AppDatabase): List<Triple<String, Double, Double>> {
    val changed = mutableListOf<Triple<String, Double, Double>>()
    db.accountDao().getAllIncludingDisabled().forEach { acc ->
        val correct = db.transactionDao().sumBalanceDelta(acc.id)
        // 浮点累加会有 1e-10 级误差，用 0.005（半分）作为「是否变化」的阈值
        if (kotlin.math.abs(correct - acc.balance) > 0.005) {
            db.accountDao().setBalance(acc.id, correct)
            changed += Triple(acc.name, acc.balance, correct)
        }
    }
    if (changed.isNotEmpty()) BookkeepingApp.notifyTransactionChanged()
    return changed
}
