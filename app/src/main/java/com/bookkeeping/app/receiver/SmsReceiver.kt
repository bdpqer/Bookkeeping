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
import com.bookkeeping.app.data.AppDatabase
import com.bookkeeping.app.parser.ParseEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
        private val processedIds = LinkedHashSet<Long>()

        /**
         * 登记去重键：已存在返回 false（应跳过），否则写入并返回 true。
         * 超过 [MAX_PROCESSED_IDS] 时淘汰最早加入的键。
         */
        private fun markProcessed(key: Long): Boolean {
            synchronized(processedIds) {
                if (!processedIds.add(key)) return false
                while (processedIds.size > MAX_PROCESSED_IDS) {
                    processedIds.remove(processedIds.first())
                }
                return true
            }
        }

        /** 规则缓存 */
        @Volatile private var cachedRules: List<com.bookkeeping.app.data.entity.ParseRule> = emptyList()

        /** 从 DB 刷新规则（每次解析前都会尝试，开销很小） */
        private suspend fun getEngine(context: Context): ParseEngine {
            return try {
                val db = AppDatabase.getInstance(context)
                val rules = db.parseRuleDao().getEnabled()
                val merchantRules = db.merchantRuleDao().getEnabled()
                ParseEngine(rules, merchantRules)
            } catch (_: Exception) {
                ParseEngine() // fallback 到默认规则
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

            // 进程内去重：同一条短信可能被 BroadcastReceiver 和 ContentObserver 同时捕获。
            // 两条路径的 ID 不同（临时 ID vs 数据库 ID），改用内容键（发件人+正文+5分钟桶）统一去重；
            // 极少数跨桶边界漏网的由 DB 层 findDuplicate（同金额 ±5 分钟）兜底。
            val dedupKey = (sender + "|" + body + "|" + time / (5 * 60 * 1000)).hashCode().toLong()
            if (!markProcessed(dedupKey)) {
                fileLog("⏭️ 跳过重复短信 id=$smsId sender=$sender")
                return
            }

            // 关键词过滤（只记录疑似支付/银行的短信）
            val relevant = containsPaymentKeyword(body) || senderLooksLikeBank(sender)
            if (!relevant) {
                fileLog("📭 跳过非支付短信 [$sender] ${body.take(60)}")
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
            CoroutineScope(Dispatchers.IO).launch {
                CaptureIngestor.ingest(
                    context = context,
                    db = AppDatabase.getInstance(context),
                    engine = getEngine(context),
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
 */
class SmsContentObserver(handler: Handler) : ContentObserver(handler) {

    override fun onChange(selfChange: Boolean, uri: Uri?) {
        super.onChange(selfChange, uri)
        // 只关注 inbox 的新增
        if (uri != null && uri.pathSegments.contains("inbox").not() && uri != Telephony.Sms.CONTENT_URI) {
            return
        }
        queryLatestAndHandle()
    }

    private fun queryLatestAndHandle() {
        val ctx = BookkeepingApp.instance
        try {
            ctx.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
                null, null,
                "${Telephony.Sms.DATE} DESC LIMIT 1"
            )?.use { cursor: Cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(0)
                    val address = cursor.getString(1) ?: ""
                    val body = cursor.getString(2) ?: ""
                    val date = cursor.getLong(3) // Telephony.Sms.DATE 本身就是毫秒，勿再 ×1000

                    SmsReceiver.handleSms(ctx, address, body, date, id, "ContentObserver")
                }
            }
        } catch (e: Exception) {
            Log.e(BookkeepingApp.TAG, "SmsContentObserver query failed", e)
        }
    }
}
