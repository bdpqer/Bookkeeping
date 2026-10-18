package com.bookkeeping.app

import com.bookkeeping.app.data.AppDatabase
import com.bookkeeping.app.data.entity.Transaction

/**
 * 自动生成交易（通知/短信/周期/分期/借贷）的默认关联：
 * 账本 = 默认账本（isDefault 优先，无则第一个）；账户 = 第一个账户。
 * 已带关联的字段不覆盖。保证自动交易能出现在默认账本的列表/首页/报表中。
 */
internal suspend fun Transaction.withDefaultAssociation(db: AppDatabase): Transaction {
    if (ledgerId != null && accountId != null) return this
    val ledgers = db.ledgerDao().getAll()
    val accounts = db.accountDao().getAll()
    return copy(
        accountId = accountId ?: accounts.firstOrNull()?.id,
        ledgerId = ledgerId ?: ledgers.firstOrNull { it.isDefault }?.id ?: ledgers.firstOrNull()?.id
    )
}

/** 历史数据修复：早期自动记账生成的交易无账本关联，启动时幂等归入默认账本 */
internal suspend fun fixNullLedgerTransactions(db: AppDatabase) {
    val ledgers = db.ledgerDao().getAll()
    val defaultLedgerId = ledgers.firstOrNull { it.isDefault }?.id ?: ledgers.firstOrNull()?.id ?: return
    val accounts = db.accountDao().getAll()
    db.transactionDao().fixNullAssociations(defaultLedgerId, accounts.firstOrNull()?.id)
}

/**
 * 历史数据修复：账本已被删除、交易还挂着那个 ledgerId 的孤儿记录，启动时幂等归入默认账本。
 *
 * 旧版本删除账本只删了 ledgers 一行，没动 transactions，于是这些交易在明细列表里照常显示，
 * 但按账本筛选永远查不到、报表也归属不明 —— 就是「删了账本明细还在」的成因。
 * 现在删除路径已改为级联（见 LedgerOps.deleteLedgerCascade），这里只收拾存量。
 */
internal suspend fun fixOrphanLedgerTransactions(db: AppDatabase): Int {
    val ledgers = db.ledgerDao().getAll()
    val defaultLedgerId = ledgers.firstOrNull { it.isDefault }?.id ?: ledgers.firstOrNull()?.id ?: return 0
    return db.transactionDao().fixOrphanAssociations(defaultLedgerId)
}
