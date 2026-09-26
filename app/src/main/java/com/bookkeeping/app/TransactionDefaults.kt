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
