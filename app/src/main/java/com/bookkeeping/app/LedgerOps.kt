package com.bookkeeping.app

import androidx.room.withTransaction
import com.bookkeeping.app.data.AppDatabase
import com.bookkeeping.app.data.entity.Ledger

/**
 * 账本级联操作（重命名 / 删除）。
 *
 * 放在这里而不是 SettingsScreen 里，是因为两者都必须和 DAO 层的口径保持一致：
 * 删除账本要连带处理 5 张按 ledgerId 归属的表，任何一处漏掉都会留下「查无此账」的悬空外键。
 */

/** 删除账本后的结果摘要，供 UI 提示用户实际影响了多少数据 */
data class LedgerDeleteResult(
    val deletedTxCount: Int,
    val newDefaultName: String?
)

/**
 * 账本重命名（可同时换图标）。
 *
 * `ledgers.name` 上有唯一索引，重名会直接抛 SQLiteConstraintException 把 App 崩掉，
 * 所以这里先查重再写。返回 false = 与其它账本重名，调用方应提示用户换个名字。
 */
internal suspend fun renameLedger(
    db: AppDatabase,
    ledger: Ledger,
    newName: String,
    newIcon: String
): Boolean {
    val name = newName.trim()
    if (name.isEmpty()) return false
    val duplicated = db.ledgerDao().getAll().any { it.id != ledger.id && it.name == name }
    if (duplicated) return false
    db.ledgerDao().update(ledger.copy(name = name, icon = newIcon))
    return true
}

/**
 * 删除账本，并级联清掉它名下的全部数据。
 *
 * 处理范围（都在同一个事务里，中途失败整体回滚，不会出现「账本没了交易还在」的中间态）：
 *  1. **交易**：整本软删进回收站（不是物理删除，跟编辑弹窗删除同一口径），
 *     同时把每笔对账户余额的影响反向冲回 —— 否则余额只减不增，会永久偏差。
 *  2. **预算 / 借贷 / 待报销待回款 / 账单分期**：这些表没有回收站，直接按 ledgerId 删除。
 *  3. **账本本身**。
 *  4. 删掉的是默认账本时，把剩余第一个提为默认，否则全 App 会找不到默认账本
 *     （新交易的默认账本、解析规则的落账都会受影响）。
 *
 * 回收站里若再恢复这些交易，RecycleBinScreen 会用 fixLedgerIfOrphan 把悬空账本归到默认账本。
 */
internal suspend fun deleteLedgerCascade(db: AppDatabase, ledgerId: Long): LedgerDeleteResult {
    val result = db.withTransaction {
        val ledger = db.ledgerDao().getById(ledgerId)
            ?: return@withTransaction LedgerDeleteResult(0, null)

        // 1) 交易：先取出全部现存记录（含待确认，与 applyBalance 的口径一致），
        //    软删的同时按账户聚合冲回余额 —— 逐笔调 revertBalance 会每笔都发一次
        //    Widget 刷新通知，几百笔就是几百次，这里改成按账户合并成一次。
        val txs = db.transactionDao().getLiveByLedger(ledgerId)
        if (txs.isNotEmpty()) {
            db.transactionDao().softDeleteByLedger(ledgerId, System.currentTimeMillis())
            val deltas = mutableMapOf<Long, Double>()
            txs.forEach { tx ->
                val accId = tx.accountId ?: return@forEach
                val delta = -balanceDelta(tx.type, tx.amount)
                if (delta != 0.0) deltas[accId] = (deltas[accId] ?: 0.0) + delta
            }
            deltas.forEach { (accId, delta) -> db.accountDao().adjustBalance(accId, delta) }
        }

        // 2) 其余按账本归属的表：无回收站概念，直接删
        db.budgetDao().deleteByLedger(ledgerId)
        db.debtDao().deleteByLedger(ledgerId)
        db.receivableDao().deleteByLedger(ledgerId)
        db.installmentDao().deleteByLedger(ledgerId)

        // 3) 账本本身
        db.ledgerDao().delete(ledgerId)

        // 4) 默认账本不能空缺
        var newDefaultName: String? = null
        if (ledger.isDefault) {
            db.ledgerDao().clearAllDefault()
            db.ledgerDao().getAll().firstOrNull()?.let {
                db.ledgerDao().setDefault(it.id)
                newDefaultName = it.name
            }
        }

        LedgerDeleteResult(txs.size, newDefaultName)
    }

    if (result.deletedTxCount > 0) BookkeepingApp.notifyTransactionChanged()
    return result
}
