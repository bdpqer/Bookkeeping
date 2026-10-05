package com.bookkeeping.app.service

import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import com.bookkeeping.app.BookkeepingApp
import com.bookkeeping.app.FileLog
import com.bookkeeping.app.MainActivity
import com.bookkeeping.app.R
import com.bookkeeping.app.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import androidx.glance.appwidget.updateAll
import com.bookkeeping.app.formatAmount

/**
 * 核心服务：监听所有支付相关 App 的通知。
 *
 * 注意：不要 override onBind()！NotificationListenerService 父类的 onBind()
 * 会返回正确的 Binder 给系统。如果返回 null，系统认为 Service 不可绑定。
 */
class NotificationCaptureService : NotificationListenerService() {

    private val handler = Handler(Looper.getMainLooper())
    // 裸 scope 无 Job 持有者且未在 onDestroy 取消：Service 销毁后协程仍在跑并持有已销毁的 Service。
    // 用 SupervisorJob 并在 onDestroy 取消。
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val db by lazy { AppDatabase.getInstance(this) }

    /** 通知侧内存去重：key=来源包|原文，3 分钟内同 key 视为同一通知重投，跳过入库 */
    private val lastInsertedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * 自动记账总开关缓存。原先每次 onNotificationPosted（主线程）都读 SharedPreferences，
     * 首次访问要读磁盘 XML；用户改开关时由 [refreshRules] 一并刷新。
     */
    @Volatile private var autoCaptureEnabled = true

    /** 从 DB 刷新规则缓存（用户改规则后调） */
    fun refreshRules() {
        scope.launch {
            RuleEngineCache.refresh(this@NotificationCaptureService)
            autoCaptureEnabled = readAutoCaptureEnabled()
            fileLog("🔄 规则与开关状态已刷新，自动记账=${if (autoCaptureEnabled) "开" else "关"}")
        }
    }

    override fun onCreate() {
        super.onCreate()
        fileLog("🔧 NotificationCaptureService.onCreate()")
        autoCaptureEnabled = readAutoCaptureEnabled()
        // 注册进程内规则刷新钩子：Service 声明了 android:permission（系统签名权限），
        // 同进程 startService 会 SecurityException，外部组件须经此钩子触发热刷新
        NotificationCaptureService.rulesRefresher = { refreshRules() }
        // 首次从 DB 加载规则
        refreshRules()
        // Android 8+ 要求 startForegroundService 在 5s 内调 startForeground
        startForegroundSafe()
        // Android 16 上没有 requestBind，只有 requestRebind(ComponentName)
        requestRebind()
    }

    /**
     * 通过反射调 `NotificationListenerService.requestRebind(ComponentName)`。
     * 该方法是 API 27 才加入的，minSdk=26 直接调用会 NoSuchMethodError，故用反射兜底。
     * onCreate 与 onListenerDisconnected 两处共用同一实现。
     */
    private fun requestRebind() {
        try {
            val cls = NotificationListenerService::class.java
            val rebindMethod = (cls.declaredMethods + cls.methods).firstOrNull {
                it.name == "requestRebind" && it.parameterCount == 1
            }
            if (rebindMethod != null) {
                rebindMethod.isAccessible = true
                val r = rebindMethod.invoke(this, ComponentName(this, this.javaClass))
                fileLog("🔁 requestRebind result: $r")
            } else {
                fileLog("❌ requestRebind not available on this API level")
            }
        } catch (e: Throwable) {
            fileLog("❌ requestRebind err: ${e.message}")
        }
    }

    private fun readAutoCaptureEnabled(): Boolean =
        getSharedPreferences("settings", MODE_PRIVATE).getBoolean("auto_capture_enabled", true)

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        fileLog("✅ onListenerConnected called!")
        Log.i(BookkeepingApp.TAG, "✅ NotificationListenerService connected")
        android.widget.Toast.makeText(this, "通知监听服务已连接", android.widget.Toast.LENGTH_SHORT).show()
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
        handler.postDelayed({ requestRebind() }, 2000)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        sbn ?: return

        val pkg = sbn.packageName

        // 自通知过滤：不要监听自己发出的通知
        if (pkg == packageName) return

        // 自动记账总开关：关闭时直接忽略通知（不解析不入库）
        if (!autoCaptureEnabled) return

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

        // 文件日志只留一行摘要防膨胀；完整 extras dump 仍进 CaptureLogBus 供调试面板查看
        fileLog("🎯 onNotificationPosted pkg=$pkg text=${rawText.take(200)}")

        // ─── 解析 + 入库 ────────────────────────────────────────
        scope.launch {
            // 通知侧内存去重：同一通知常被系统重投/多实例下发（不同 postTime），
            // 解析出的 merchant 可能不一致导致 SQL 去重漏放，
            // 这里按「来源包 + 全文」再拦一道（3 分钟窗口）。
            val dupKey = "$pkg|$rawText"
            val now = System.currentTimeMillis()
            val lastTs = lastInsertedAt[dupKey]
            if (lastTs != null && now - lastTs < 3 * 60 * 1000L) {
                fileLog("⏭️ 3分钟内同来源同内容重复跳过 pkg=$pkg")
                return@launch
            }
            val id = CaptureIngestor.ingest(
                context = this@NotificationCaptureService,
                db = db,
                engine = RuleEngineCache.get(this@NotificationCaptureService),
                rawText = rawText,
                channel = pkgToChannel(pkg),
                occurredAt = sbn.postTime,
                logTag = "通知",
                sourcePackage = pkg
            ) { tx, _ ->
                handler.post {
                    android.widget.Toast.makeText(
                        this@NotificationCaptureService,
                        "🎯 [${pkgToChannel(pkg)}] ¥${tx.amount.formatAmount()}",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
                // 刷新桌面 Widget
                com.bookkeeping.app.widget.BookkeepingWidget().updateAll(this@NotificationCaptureService)
            }
            if (id != null) {
                lastInsertedAt[dupKey] = now
                // 容量保护：清掉 3 分钟窗口外的记录。
                // 原来只在「清理后仍 > 200」时才做，3 分钟内涌入超过 200 条时上限形同虚设，
                // 改为每次插入后都检查一次。
                if (lastInsertedAt.size > 200) {
                    val it = lastInsertedAt.entries.iterator()
                    while (it.hasNext()) {
                        if (now - it.next().value > 3 * 60 * 1000L) it.remove()
                    }
                }
            }
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

    // ─── 前台服务 ──────────────────────────────────────────────

    private fun startForegroundSafe() {
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
            fileLog("🔄 startForeground ok")
        } catch (e: Exception) {
            // 失败后 Service 不是前台服务，Android 8+ 会在几分钟内被系统回收，
            // 且通知监听会静默失效——不如直接停掉，让用户看到服务已断开。
            fileLog("❌ startForeground failed, stopSelf: ${e.message}")
            stopSelf()
        }
    }

    // ─── 调试：写文件日志 ──────────────────────────────────────

    /** 统一走 FileLog（带 1MB 轮转防膨胀） */
    private fun fileLog(msg: String) = FileLog.append(this, "service.log", msg)

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

    /** extras 遍历是调试面板快照用途，Bundle.get 在 Java 侧被标 deprecated（无直接替代，保持） */
    @Suppress("DEPRECATION")
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

        /** 规则热刷新钩子：onCreate 时由实例注册，保存解析规则后同进程直接调用 */
        @Volatile
        internal var rulesRefresher: (() -> Unit)? = null
    }
}
