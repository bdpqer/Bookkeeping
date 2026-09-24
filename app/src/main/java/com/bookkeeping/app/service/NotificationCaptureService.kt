package com.bookkeeping.app.service

import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.glance.appwidget.updateAll
import com.bookkeeping.app.BookkeepingApp
import com.bookkeeping.app.MainActivity
import com.bookkeeping.app.R
import com.bookkeeping.app.checkBudgetAndNotify
import com.bookkeeping.app.data.AppDatabase
import com.bookkeeping.app.parser.ParseEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.FileWriter
import java.io.PrintWriter

/**
 * 核心服务：监听所有支付相关 App 的通知。
 *
 * 注意：不要 override onBind()！NotificationListenerService 父类的 onBind()
 * 会返回正确的 Binder 给系统。如果返回 null，系统认为 Service 不可绑定。
 */
class NotificationCaptureService : NotificationListenerService() {

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.IO)
    private val db by lazy { AppDatabase.getInstance(this) }
    @Volatile private var cachedParseEngine: ParseEngine = ParseEngine()
    private var isForegroundStarted = false

    /** 从 DB 刷新规则缓存（用户改规则后调） */
    fun refreshRules() {
        scope.launch {
            val rules = db.parseRuleDao().getEnabled()
            val merchantRules = db.merchantRuleDao().getEnabled()
            cachedParseEngine = ParseEngine(rules, merchantRules)
            fileLog("🔄 规则已刷新，ParseRule=${rules.size} MerchantRule=${merchantRules.size}")
        }
    }

    override fun onCreate() {
        super.onCreate()
        fileLog("🔧 NotificationCaptureService.onCreate()")
        // 首次从 DB 加载规则
        refreshRules()
        // Android 8+ 要求 startForegroundService 在 5s 内调 startForeground
        startForegroundSafe()
        // Android 16 上没有 requestBind，只有 requestRebind(ComponentName)
        try {
            val cls = NotificationListenerService::class.java
            val allMethods = cls.declaredMethods + cls.methods
            // 找 requestRebind 方法（单参数 ComponentName）
            val rebindMethod = allMethods.firstOrNull {
                it.name == "requestRebind" && it.parameterCount == 1
            }
            if (rebindMethod != null) {
                rebindMethod.isAccessible = true
                val r = rebindMethod.invoke(this, ComponentName(this, this.javaClass.name))
                fileLog("🔁 requestRebind result: $r")
            } else {
                fileLog("❌ requestRebind also not found!")
            }
        } catch (e: Throwable) {
            fileLog("❌ requestRebind err: ${e.message}")
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        fileLog("✅ onListenerConnected called!")
        Log.i(BookkeepingApp.TAG, "✅ NotificationListenerService connected")
        android.widget.Toast.makeText(this, "📡 通知监听服务已连接", android.widget.Toast.LENGTH_SHORT).show()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "com.bookkeeping.app.REFRESH_RULES") {
            refreshRules()
        }
        return START_STICKY
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        fileLog("⚠️ onListenerDisconnected, scheduling rebind...")
        Log.w(BookkeepingApp.TAG, "⚠️ NotificationListenerService disconnected")
        handler.postDelayed({
            try {
                requestRebind(ComponentName(this, NotificationCaptureService::class.java))
                fileLog("🔁 requestRebind called")
            } catch (e: Exception) {
                fileLog("❌ requestRebind failed: ${e.message}")
            }
        }, 2000)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        sbn ?: return

        val pkg = sbn.packageName

        // 自通知过滤：不要监听自己发出的通知
        if (pkg == packageName) return

        // 自动记账总开关：关闭时直接忽略通知（不解析不入库）
        if (!getSharedPreferences("settings", MODE_PRIVATE).getBoolean("auto_capture_enabled", true)) return

        val extras = sbn.notification.extras

        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val subText = extras?.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
        val bigText = extras?.getCharSequence("android.bigText")?.toString()
        val inboxLines = extras?.getStringArray("android.inbox.lines")
        val tickerText = sbn.notification.tickerText?.toString()

        val rawText = buildString {
            append(title ?: "")
            if (!text.isNullOrEmpty()) append(" ").append(text)
            if (!subText.isNullOrEmpty()) append(" ").append(subText)
            if (!bigText.isNullOrEmpty() && bigText != text) append(" ").append(bigText)
            if (!inboxLines.isNullOrEmpty()) append(" ").append(inboxLines.joinToString(" | "))
            if (!tickerText.isNullOrEmpty() && tickerText != title && tickerText != text) {
                append(" [ticker: ").append(tickerText).append("]")
            }
        }.trim()

        // 空通知过滤：没有文本内容的直接跳过（浦发 App 连发的空通知）
        if (rawText.length < 3) return

        val fullDump = dumpAllExtras(extras, pkg)

        fileLog("🎯 onNotificationPosted pkg=$pkg text=${rawText.take(200)}")
        fileLog("━━━ EXTRAS DUMP ━━━")
        fileLog(fullDump)
        fileLog("━━━ END DUMP ━━━")

        // ─── 解析 + 入库 ────────────────────────────────────────
        scope.launch {
            val tx = cachedParseEngine.parse(
                rawText = rawText,
                sourcePackage = pkg,
                sourceChannel = pkgToChannel(pkg),
                occurredAt = sbn.postTime
            )
            if (tx != null) {
                // 去重：同 5 分钟内、同金额、同类型
                val since = tx.occurredAt - 5 * 60 * 1000
                val until = tx.occurredAt + 5 * 60 * 1000
                val dupes = db.transactionDao().findDuplicate(tx.amount, tx.type.name, tx.merchant, since, until)
                if (dupes.isEmpty()) {
                    val id = db.transactionDao().insert(tx)
                    fileLog("✅ 解析成功 → 入库 id=$id amt=${tx.amount} type=${tx.type} cat=${tx.category} conf=${tx.confidence}")
                    // 刷新桌面 Widget
                    com.bookkeeping.app.widget.BookkeepingWidget().updateAll(this@NotificationCaptureService)
                    // 预算超支检查（每自然月最多提醒一次）
                    checkBudgetAndNotify(this@NotificationCaptureService)
                } else {
                    fileLog("⏭️ 重复交易跳过 amt=${tx.amount} type=${tx.type}")
                }
            } else {
                fileLog("❌ 解析失败（无法提取金额或文本为空）")
            }
        }

        handler.post {
            android.widget.Toast.makeText(
                this,
                "🎯 [$pkg] ${rawText.take(50)}",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }

        val entry = CaptureLogBus.CaptureEntry(
            time = sbn.postTime,
            source = "NOTIFICATION",
            packageName = pkg,
            sender = null,
            title = title,
            text = text,
            fullDump = fullDump,
            rawText = rawText.ifEmpty { "(无文本内容)" }
        )
        CaptureLogBus.add(entry)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
    }

    // ─── 前台服务 ──────────────────────────────────────────────

    private fun startForegroundSafe() {
        if (isForegroundStarted) return
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, BookkeepingApp.CHANNEL_ID_CAPTURE)
            .setContentTitle(getString(R.string.capture_notification_text))
            .setContentText("点击打开记账助手")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()

        try {
            startForeground(FOREGROUND_ID, notification)
            isForegroundStarted = true
            fileLog("🔄 startForeground ok")
        } catch (e: Exception) {
            fileLog("❌ startForeground failed: ${e.message}")
        }
    }

    // ─── 调试：写文件日志 ──────────────────────────────────────

    private fun fileLog(msg: String) {
        val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        val line = "$ts  $msg\n"
        try {
            // 用应用私有目录，不用 sdcard 权限
            val f = java.io.File(filesDir, "service.log")
            PrintWriter(FileWriter(f, true)).use { it.append(line) }
            Log.d(BookkeepingApp.TAG, msg)
        } catch (e: Exception) {
            Log.e(BookkeepingApp.TAG, "fileLog failed: ${e.message}")
        }
    }

    // ─── 工具 ──────────────────────────────────────────────────

    private fun pkgToChannel(pkg: String): String = when (pkg) {
        "com.tencent.mm" -> "微信"
        "com.eg.android.AlipayGphone" -> "支付宝"
        "com.unionpay" -> "云闪付"
        "com.icbc" -> "工商银行"
        "cmb.pb" -> "招商银行"
        "com.chinamworld.main" -> "中国银行"
        "com.android.bankabc" -> "农业银行"
        "com.bocom.mbank" -> "交通银行"
        "cn.com.spdb.mobilebank.per" -> "浦发银行"
        else -> pkg
    }

    private fun dumpAllExtras(extras: android.os.Bundle?, pkg: String): String {
        if (extras == null) return "(no extras)"
        return buildString {
            appendLine("包名: $pkg")
            extras.keySet().sorted().forEach { key ->
                val value = runCatching {
                    val v = extras.get(key)
                    when (v) {
                        is CharSequence -> v.toString()
                        is Array<*> -> v.joinToString(", ", "[", "]")
                        else -> v?.toString() ?: "null"
                    }
                }.getOrDefault("(error)")
                appendLine("  $key = $value")
            }
        }
    }

    companion object {
        private const val FOREGROUND_ID = 1001

        val INTERESTING_PACKAGES = setOf(
            "com.tencent.mm", "com.eg.android.AlipayGphone", "com.unionpay",
            "com.icbc", "cmb.pb", "com.chinamworld.main",
            "com.android.bankabc", "com.bankofchina.mbank", "com.bocom.mbank",
            "com.sankuai.meituan", "me.ele"
        )
    }
}
