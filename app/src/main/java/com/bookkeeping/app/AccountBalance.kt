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
}

/** 删除/移入回收站后，把该笔交易此前对余额的影响反向冲回 */
internal suspend fun revertBalance(db: AppDatabase, tx: Transaction) {
    val id = tx.accountId ?: return
    val delta = -balanceDelta(tx.type, tx.amount)
    if (delta != 0.0) db.accountDao().adjustBalance(id, delta)
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
