package com.bookkeeping.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.util.Log
import com.bookkeeping.app.BookkeepingApp
import com.bookkeeping.app.checkBudgetAndNotify
import com.bookkeeping.app.withDefaultAssociation
import com.bookkeeping.app.service.CaptureLogBus
import com.bookkeeping.app.data.AppDatabase
import com.bookkeeping.app.parser.ParseEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
        /** 已处理的短信 ID（进程内去重） */
        private val processedIds = mutableSetOf<Long>()

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

            // 进程内去重：同一条短信可能被 BroadcastReceiver 和 ContentObserver 同时捕获
            if (smsId != null) {
                synchronized(processedIds) {
                    if (processedIds.contains(smsId)) {
                        fileLog("⏭️ 跳过重复短信 id=$smsId sender=$sender")
                        return
                    }
                    processedIds.add(smsId)
                }
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
                val db = AppDatabase.getInstance(context)
                val engine = getEngine(context)
                val tx = engine.parse(
                    rawText = body,
                    sourceSender = sender,
                    sourceChannel = senderToChannel(sender),
                    occurredAt = time
                )
                if (tx != null) {
                    val tx = tx.withDefaultAssociation(db)
                    val since = tx.occurredAt - 5 * 60 * 1000
                    val until = tx.occurredAt + 5 * 60 * 1000
                    val dupes = db.transactionDao().findDuplicate(tx.amount, tx.type.name, tx.merchant, since, until)
                    if (dupes.isEmpty()) {
                        val id = db.transactionDao().insert(tx)
                        fileLog("✅ 短信解析成功 → 入库 id=$id amt=${tx.amount} type=${tx.type} cat=${tx.category}")
                        // 预算超支检查（每自然月最多提醒一次）
                        checkBudgetAndNotify(context)
                    } else {
                        fileLog("⏭️ 重复交易跳过 amt=${tx.amount} type=${tx.type}")
                    }
                } else {
                    fileLog("❌ 短信解析失败（无法提取金额）")
                }
            }
        }

        private val PAYMENT_KEYWORDS = listOf(
            "¥", "￥", "元", "人民币", "消费", "收入", "支出", "转账",
            "入账", "还款", "扣款", "交易", "余额", "支付成功",
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

        private fun fileLog(msg: String) {
            val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            val line = "$ts  $msg\n"
            try {
                val f = java.io.File(BookkeepingApp.instance.filesDir, "service.log")
                PrintWriter(FileWriter(f, true)).use { it.append(line) }
                Log.d(BookkeepingApp.TAG, msg)
            } catch (e: Exception) {
                Log.e(BookkeepingApp.TAG, "sms fileLog failed", e)
            }
        }
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
                    val date = cursor.getLong(3) * 1000L // Telephony.Sms.DATE 是秒

                    SmsReceiver.handleSms(ctx, address, body, date, id, "ContentObserver")
                }
            }
        } catch (e: Exception) {
            Log.e(BookkeepingApp.TAG, "SmsContentObserver query failed", e)
        }
    }
}
