package com.bookkeeping.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.provider.Telephony
import android.util.Log
import com.bookkeeping.app.BookkeepingApp
import com.bookkeeping.app.FileLog
import com.bookkeeping.app.service.CaptureIngestor
import com.bookkeeping.app.service.CaptureLogBus
import com.bookkeeping.app.service.RuleEngineCache
import com.bookkeeping.app.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 短信监听：双保险机制
 *   1. BroadcastReceiver（SMS_RECEIVED）——短信到达的第一时间
 *   2. ContentObserver（content://sms）——某些 ROM 可能延迟或丢弃广播
 *
 * 两者互补，确保任何方式到达的短信都能被捕获。
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        fileLog("📬 BroadcastReceiver 收到短信 ${messages.size} 条")

        messages.forEach { msg ->
            val sender = msg.displayOriginatingAddress ?: msg.originatingAddress ?: "未知"
            val body = msg.messageBody ?: ""
            val time = msg.timestampMillis
            // BroadcastReceiver 里短信可能还没持久化，用时间戳+发件人做临时 ID
            val tempId = (time * 31 + sender.hashCode()).toLong()

            handleSms(context, sender, body, time, tempId, "BroadcastReceiver")
        }
    }

    // ─── 统一处理入口 ──────────────────────────────────────────

    companion object {
        /** 进程内去重集合的容量上限：超出后淘汰最早的记录，避免长期运行无限增长 */
        private const val MAX_PROCESSED_IDS = 500

        /** 已处理的短信去重键（进程内，按插入顺序淘汰） */
        private val processedIds = LinkedHashSet<String>()

        /**
         * 解析/入库用的常驻协程作用域。
         * 原实现每条短信 `CoroutineScope(Dispatchers.IO).launch {}` 新建一个无 Job 的裸 scope，
         * 协程无法被取消，Receiver 生命周期结束后仍在跑。
         */
        private val ingestScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /**
         * 登记去重键：已存在返回 false（应跳过），否则写入并返回 true。
         * 超过 [MAX_PROCESSED_IDS] 时淘汰最早加入的键。
         */
        private fun markProcessed(key: String): Boolean {
            synchronized(processedIds) {
                if (!processedIds.add(key)) return false
                while (processedIds.size > MAX_PROCESSED_IDS) {
                    processedIds.remove(processedIds.first())
                }
                return true
            }
        }

        fun handleSms(
            context: Context,
            sender: String,
            body: String,
            time: Long,
            smsId: Long?,
            source: String
        ) {
            // 自动记账总开关：关闭时直接忽略短信
            if (!context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                    .getBoolean("auto_capture_enabled", true)) return

            // 关键词过滤（只记录疑似支付/银行的短信）。
            // 必须放在去重登记之前：否则一条验证码短信也会占用去重槽位，把真正的支付短信挤掉。
            val relevant = containsPaymentKeyword(body) || senderLooksLikeBank(sender)
            if (!relevant) {
                fileLog("📭 跳过非支付短信 [$sender] ${body.take(60)}")
                return
            }

            // 进程内去重：同一条短信可能被 BroadcastReceiver 和 ContentObserver 同时捕获。
            // 两条路径的 ID 不同（临时 ID vs 数据库 ID），改用内容键（发件人+正文+3分钟桶）统一去重；
            // 极少数跨桶边界漏网的由 DB 层 findDuplicate（同金额 ±3 分钟）兜底。
            // 用完整键字符串而非 hashCode()：hashCode 是 32 位，强制转 Long 会丢高位，碰撞率远高于预期。
            val dedupKey = "$sender|$body|${time / (3 * 60 * 1000)}"
            if (!markProcessed(dedupKey)) {
                fileLog("⏭️ 跳过重复短信 id=$smsId sender=$sender")
                return
            }

            fileLog("📨 [$source] 捕获短信: [$sender] ${body.take(200)}")

            val entry = CaptureLogBus.CaptureEntry(
                time = time,
                source = "SMS",
                packageName = null,
                sender = sender,
                title = null,
                text = body,
                fullDump = buildString {
                    appendLine("来源: $source")
                    appendLine("发件人: $sender")
                    appendLine("短信ID: $smsId")
                    appendLine("时间戳: $time")
                    appendLine("正文: $body")
                },
                rawText = body
            )
            CaptureLogBus.add(entry)

            // ─── 解析 + 入库 ────────────────────────────────────────
            ingestScope.launch {
                CaptureIngestor.ingest(
                    context = context,
                    db = AppDatabase.getInstance(context),
                    engine = RuleEngineCache.get(context),
                    rawText = body,
                    channel = senderToChannel(sender),
                    occurredAt = time,
                    logTag = "SMS",
                    sourceSender = sender
                )
            }
        }

        private val PAYMENT_KEYWORDS = listOf(
            "¥", "￥", "元", "人民币", "消费", "收入", "支出", "转账",
            "入账", "还款", "还账", "扣款", "交易", "余额", "支付成功",
            "微信支付", "支付宝", "财付通", "云闪付",
            "尾号", "账户", "卡于", "汇入", "汇出"
        )

        fun containsPaymentKeyword(text: String): Boolean {
            return PAYMENT_KEYWORDS.any { text.contains(it) }
        }

        fun senderLooksLikeBank(sender: String): Boolean {
            val clean = sender.replace("+86", "").replace(" ", "")
            return clean.matches(Regex("^955\\d{2,4}$")) ||      // 95588, 95599
                   clean.matches(Regex("^1069\\d{6,}$")) ||      // 10690xxxxxx
                   clean.matches(Regex("^106\\d{8,}$"))          // 106xxxxx
        }

        private fun senderToChannel(sender: String): String {
            val clean = sender.replace("+86", "").replace(" ", "")
            return when {
                clean.startsWith("95588") -> "工商银行"
                clean.startsWith("95599") -> "农业银行"
                clean.startsWith("95533") -> "建设银行"
                clean.startsWith("95555") -> "招商银行"
                clean.startsWith("95566") -> "中国银行"
                clean.startsWith("95559") -> "交通银行"
                clean.startsWith("95528") -> "浦发银行"
                clean.startsWith("95516") -> "银联"
                clean.startsWith("10690") || clean.startsWith("10691") -> "支付渠道"
                else -> "短信"
            }
        }

        private fun fileLog(msg: String) = FileLog.append(BookkeepingApp.instance, "service.log", msg)
    }
}

/**
 * ContentObserver 兜底：直接监听 content://sms 数据库变化。
 * 在 BookkeepingApp.onCreate 中注册。
 *
 * ⚠️ 必须传入 **IO 线程** 的 Handler：onChange 里要查 content://sms（磁盘读），
 * 用主 Looper 会触发 StrictMode 磁盘读违规。
 */
class SmsContentObserver(handler: Handler) : ContentObserver(handler) {

    /**
     * 已处理的最大短信 _ID。ContentObserver 可能因任何变化触发回调（含状态更新），
     * 用它做增量过滤，既避免重复处理，也避免只取最新一条而漏掉同批到达的多条短信。
     */
    @Volatile
    private var lastHandledId: Long = 0L

    override fun onChange(selfChange: Boolean, uri: Uri?) {
        super.onChange(selfChange, uri)
        // 只关注收件箱：其余 URI（sent/draft/thread）不产生待记账的入账短信
        if (uri != null && !uri.pathSegments.contains("inbox")) return
        queryNewAndHandle()
    }

    private fun queryNewAndHandle() {
        val ctx = BookkeepingApp.instance
        val from = lastHandledId
        try {
            ctx.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
                // 增量：只取比上次处理更新的，同批到达的多条都能覆盖到
                "${Telephony.Sms._ID} > ?",
                arrayOf(from.toString()),
                "${Telephony.Sms._ID} ASC"
            )?.use { cursor: Cursor ->
                var maxId = from
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    val address = cursor.getString(1) ?: ""
                    val body = cursor.getString(2) ?: ""
                    val date = cursor.getLong(3) // Telephony.Sms.DATE 本身就是毫秒，勿再 ×1000
                    if (id > maxId) maxId = id
                    SmsReceiver.handleSms(ctx, address, body, date, id, "ContentObserver")
                }
                // 无论是否有支付短信都要推进游标，否则同一批会被反复扫描
                if (maxId > from) lastHandledId = maxId
            }
        } catch (e: Exception) {
            Log.e(BookkeepingApp.TAG, "SmsContentObserver query failed", e)
        }
    }
}
