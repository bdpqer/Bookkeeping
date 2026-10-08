package com.bookkeeping.app.service

import android.content.Context
import androidx.room.withTransaction
import com.bookkeeping.app.FileLog
import com.bookkeeping.app.checkBudgetAndNotify
import com.bookkeeping.app.applyBalance
import com.bookkeeping.app.data.AppDatabase
import com.bookkeeping.app.data.entity.Transaction
import com.bookkeeping.app.parser.ParseEngine
import com.bookkeeping.app.withDefaultAssociation

/**
 * 抓取入库管线的统一入口。
 *
 * 短信（[com.bookkeeping.app.receiver.SmsReceiver]）与通知
 * （NotificationCaptureService）两条捕获路径此前各写了一遍
 * 「解析 → 默认账本/账户关联 → 去重 → 入库 → 预算检查」，此处统一收敛。
 */
object CaptureIngestor {

    /** 去重窗口：同金额、同类型、同商户，±3 分钟内视为同一笔交易 */
    private const val DUP_WINDOW_MS = 3 * 60 * 1000L

    /**
     * @param context      上下文（用于预算超支提醒）
     * @param db           数据库实例
     * @param engine       解析引擎
     * @param rawText      待解析原文
     * @param channel      来源渠道（银行名 / App 渠道名）
     * @param occurredAt   交易发生时间
     * @param logTag       日志标签，如 "SMS" / "通知"
     * @param sourcePackage 来源包名（通知路径）
     * @param sourceSender  来源号码（短信路径）
     * @param onInserted   入库成功回调，参数为交易实体与新记录 id
     * @return 入库 id；解析失败或命中重复时返回 null
     */
    suspend fun ingest(
        context: Context,
        db: AppDatabase,
        engine: ParseEngine,
        rawText: String,
        channel: String,
        occurredAt: Long,
        logTag: String,
        sourcePackage: String? = null,
        sourceSender: String? = null,
        onInserted: suspend (Transaction, Long) -> Unit = { _, _ -> }
    ): Long? {
        val parsed = engine.parse(
            rawText = rawText,
            sourcePackage = sourcePackage,
            sourceSender = sourceSender,
            sourceChannel = channel,
            occurredAt = occurredAt
        )
        if (parsed == null) {
            FileLog.append(context, "service.log", "❌ [$logTag] 解析失败（无法提取金额或文本为空）")
            return null
        }

        val tx = parsed.withDefaultAssociation(db)

        // 去重 + 入库放在同一事务里：否则并发的两条捕获（通知与短信同时到）
        // 可能双双通过去重检查，然后各自 insert，同一笔交易入账两次
        val id = db.withTransaction {
            val dupes = db.transactionDao().findDuplicate(
                amountCents = Math.round(tx.amount * 100),
                type = tx.type.name,
                merchant = tx.merchant,
                since = tx.occurredAt - DUP_WINDOW_MS,
                until = tx.occurredAt + DUP_WINDOW_MS
            )
            if (dupes.isNotEmpty()) {
                FileLog.append(context, "service.log", "⏭️ [$logTag] 重复交易跳过 amt=${tx.amount} type=${tx.type}")
                return@withTransaction null
            }
            db.transactionDao().insert(tx)
        } ?: run {
            FileLog.append(
                context, "service.log",
                "❌ [$logTag] 入库失败或命中重复 amt=${tx.amount} type=${tx.type}"
            )
            return null
        }

        FileLog.append(
            context, "service.log",
            "✅ [$logTag] 解析成功 → 入库 id=$id amt=${tx.amount} type=${tx.type} cat=${tx.category} conf=${tx.confidence}"
        )
        onInserted(tx, id)

        // ⚠️ 自动记账也必须过 applyBalance：早先这里只 insert 不动余额，
        // 于是自动捕获的每一笔都对余额「隐形」，而在编辑/删除时又会被
        // reapplyBalance/revertBalance 冲回一次（那笔从未计入过）→ 余额凭空反向漂移。
        applyBalance(db, tx)

        // 预算超支检查（每自然月最多提醒一次）
        checkBudgetAndNotify(context, ledgerId = tx.ledgerId)
        return id
    }
}
