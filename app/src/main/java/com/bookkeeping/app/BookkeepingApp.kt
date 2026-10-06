package com.bookkeeping.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.provider.Telephony
import android.util.Log
import com.bookkeeping.app.data.AppDatabase
import com.bookkeeping.app.data.entity.Account
import com.bookkeeping.app.data.entity.Budget
import com.bookkeeping.app.data.entity.Ledger
import com.bookkeeping.app.data.entity.MerchantRule
import com.bookkeeping.app.parser.ParseEngine
import com.bookkeeping.app.receiver.SmsContentObserver
import com.bookkeeping.app.widget.BookkeepingWidgetProvider
import com.bookkeeping.app.worker.AutoBackupWorker
import com.bookkeeping.app.worker.RecurringWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

class BookkeepingApp : Application() {

    lateinit var smsContentObserver: SmsContentObserver
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        writeDebug("🔧 BookkeepingApp.onCreate() PID=${android.os.Process.myPid()}")
        createNotificationChannel()
        registerSmsContentObserver()
        seedParseRulesIfNeeded()
        seedDefaultsIfNeeded()
        seedMerchantRulesIfNeeded()
        fixLegacyAssociations()
        migrateLegacyBudgetPrefs()
        RecurringWorker.triggerNow(this)
        AutoBackupWorker.ensureScheduled(this)
        startWidgetAutoRefresh()
    }

    /**
     * 兜底刷新：观察 Room Flow，作为「显式即时刷新」(requestWidgetRefresh) 的补充，
     * 覆盖任何没走 applyBalance 的写入。走原生 RemoteViews 同步刷新（前台即时、后台时受系统
     * 节流，仅作最后兜底）。失败仅记日志、不致命。
     */
    @OptIn(FlowPreview::class)
    private fun startWidgetAutoRefresh() {
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            AppDatabase.getInstance(this@BookkeepingApp)
                .transactionDao().observeAll()
                .debounce(800)
                .collect { requestWidgetRefresh() }
        }
    }

    /**
     * 刷新作用域：与 App 生命周期同寿。每个刷新请求独立成协程，单点失败不影响后续。
     */
    private val widgetRefreshScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * 交易写入「那一刻」即时刷新 Widget。
     *
     * 根因：Glance 的 update()/updateAll() 是「事件入队即返回」，真正构图在 Glance 内部
     * session 的异步事件循环里稍后才执行，且 session 缓存 lastRemoteViews。App 退后台后事件
     * 循环被系统节流/暂停 → 事件不被消费 → provideGlance 不重跑 → 数据停在旧值。这是 Glance
     * 架构固有限制，任何「调用 update」都绕不过。
     *
     * 修法：改用原生 RemoteViews（BookkeepingWidgetProvider.onUpdate 里同步读库 +
     * AppWidgetManager.updateAppWidget()）。这是同步、立即生效的，前台调用完全不受后台节流影响。
     * 由 applyBalance / revertBalance / CSV 导入 / 报销 / 回收站在写入后调用。
     */
    fun requestWidgetRefresh() {
        widgetRefreshScope.launch {
            try {
                BookkeepingWidgetProvider.updateAll(this@BookkeepingApp)
            } catch (e: Exception) {
                Log.e(TAG, "Widget 刷新失败", e)
                writeDebug("❌ Widget 刷新失败: ${e.message}")
            }
        }
    }

    /**
     * v10 → v11 存量预算数据搬迁：旧全局预算（budget_prefs）→ 默认账本的预算行。
     * 幂等：跑完删除旧键；孤儿预算行一并清理。失败仅记日志，下次启动重试。
     */
    private fun migrateLegacyBudgetPrefs() = launchIo("migrateLegacyBudgetPrefs") {
        try {
            val prefs = getSharedPreferences("budget_prefs", MODE_PRIVATE)
            val oldAmount = prefs.getFloat("monthly_budget", 0f).toDouble()
            val oldMonth = prefs.getString("budget_notified_month", null)
            if (oldAmount <= 0 && oldMonth == null) return@launchIo

            val db = AppDatabase.getInstance(this@BookkeepingApp)
            val target = db.ledgerDao().getDefault() ?: db.ledgerDao().getAll().firstOrNull()
            val migrated = if (target == null) {
                // 默认账本还没就位（播种协程与这里并发跑），本次不迁移，保留旧键下次启动重试。
                // 早先这里无论成败都清 old key，会让来不及迁移的旧预算永久丢失
                Log.w(TAG, "⚠️ 旧预算迁移跳过：暂无可用账本，将在下次启动重试")
                false
            } else if (db.budgetDao().getByLedger(target.id) == null) {
                db.budgetDao().insert(
                    Budget(ledgerId = target.id, monthlyAmount = oldAmount, notifiedMonth = oldMonth)
                )
                Log.d(TAG, "✅ 旧全局预算已迁移到账本「${target.name}」：¥$oldAmount")
                true
            } else {
                true // 该账本已有预算行：旧值此前已迁移过，可以清键了
            }
            db.budgetDao().deleteOrphans()
            if (migrated) prefs.edit().remove("monthly_budget").remove("budget_notified_month").apply()
        } catch (e: Exception) {
            Log.e(TAG, "migrateLegacyBudgetPrefs failed", e)
        }
    }

    /** 首次启动初始化默认商家分类规则 */
    private fun seedMerchantRulesIfNeeded() = launchIo("seedMerchantRulesIfNeeded") {
        try {
            val db = AppDatabase.getInstance(this@BookkeepingApp)
            if (db.merchantRuleDao().count() == 0) {
                val defaults = listOf(
                MerchantRule(keyword = "星巴克", category = "餐饮", note = "咖啡连锁"),
                MerchantRule(keyword = "瑞幸", category = "餐饮", note = "咖啡连锁"),
                MerchantRule(keyword = "喜茶", category = "餐饮", note = "奶茶连锁"),
                MerchantRule(keyword = "蜜雪冰城", category = "餐饮", note = "奶茶连锁"),
                MerchantRule(keyword = "奶茶", category = "餐饮"),
                MerchantRule(keyword = "咖啡", category = "餐饮"),
                MerchantRule(keyword = "美团", category = "餐饮"),
                MerchantRule(keyword = "饿了么", category = "餐饮"),
                MerchantRule(keyword = "外卖", category = "餐饮"),
                MerchantRule(keyword = "滴滴", category = "交通", note = "打车"),
                MerchantRule(keyword = "打车", category = "交通"),
                MerchantRule(keyword = "地铁", category = "交通"),
                MerchantRule(keyword = "公交", category = "交通"),
                MerchantRule(keyword = "高铁", category = "交通"),
                MerchantRule(keyword = "机票", category = "交通"),
                MerchantRule(keyword = "拼多多", category = "购物"),
                MerchantRule(keyword = "淘宝", category = "购物"),
                MerchantRule(keyword = "京东", category = "购物"),
                MerchantRule(keyword = "工资", category = "工资"),
                MerchantRule(keyword = "红包", category = "红包"),
                MerchantRule(keyword = "信用卡还款", category = "还款"),
                MerchantRule(keyword = "信用卡", category = "还款"),
                MerchantRule(keyword = "理财", category = "理财"),
                MerchantRule(keyword = "基金", category = "理财"),
                MerchantRule(keyword = "水费", category = "居住"),
                MerchantRule(keyword = "电费", category = "居住"),
                MerchantRule(keyword = "燃气", category = "居住"),
                MerchantRule(keyword = "话费", category = "居住"),
                MerchantRule(keyword = "医院", category = "医疗"),
                MerchantRule(keyword = "挂号", category = "医疗")
            )
                db.merchantRuleDao().insertAll(defaults)
                Log.d(TAG, "✅ Seeded ${defaults.size} merchant rules")
            }
        } catch (e: Exception) {
            Log.e(TAG, "seedMerchantRulesIfNeeded failed", e)
        }
    }

    /** 历史数据修复：早期自动记账生成的交易无账本关联，启动时幂等归入默认账本 */
    private fun fixLegacyAssociations() = launchIo("fixLegacyAssociations") {
        try {
            fixNullLedgerTransactions(AppDatabase.getInstance(this@BookkeepingApp))
        } catch (e: Exception) {
            Log.e(TAG, "fixLegacyAssociations failed", e)
        }
    }

    /** 首次启动初始化默认账本和账户 */
    private fun seedDefaultsIfNeeded() = launchIo("seedDefaultsIfNeeded") {
        try {
            val db = AppDatabase.getInstance(this@BookkeepingApp)
            if (db.ledgerDao().getAll().isEmpty()) {
                db.ledgerDao().insert(Ledger(name = "日常账", icon = "📒", isDefault = true))
                db.ledgerDao().insert(Ledger(name = "工作账", icon = "💼"))
                db.ledgerDao().insert(Ledger(name = "旅行账", icon = "✈️"))
                Log.d(TAG, "✅ Seeded default ledgers")
            }
            if (db.accountDao().getAllIncludingDisabled().isEmpty()) {
                db.accountDao().insert(Account(name = "现金", type = Account.AccountType.CASH, icon = "💵"))
                db.accountDao().insert(Account(name = "银行卡", type = Account.AccountType.BANK, icon = "💳"))
                db.accountDao().insert(Account(name = "微信零钱", type = Account.AccountType.WECHAT, icon = "💬"))
                db.accountDao().insert(Account(name = "支付宝", type = Account.AccountType.ALIPAY, icon = "🅰️"))
                Log.d(TAG, "✅ Seeded default accounts")
            }
        } catch (e: Exception) {
            Log.e(TAG, "seedDefaultsIfNeeded failed", e)
        }
    }

    /** 首次启动时把硬编码规则写入 DB，后续 Service 全部从 DB 加载 */
    private fun seedParseRulesIfNeeded() = launchIo("seedParseRulesIfNeeded") {
        try {
            val db = AppDatabase.getInstance(this@BookkeepingApp)
            if (db.parseRuleDao().count() == 0) {
                db.parseRuleDao().insertAll(ParseEngine.DEFAULT_RULES)
                Log.d(TAG, "✅ Seeded ${ParseEngine.DEFAULT_RULES.size} parse rules to DB")
            }
        } catch (e: Exception) {
            Log.e(TAG, "seedParseRulesIfNeeded failed", e)
        }
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        // minSdk=26（Android 8.0）起 NotificationChannel 为必需，无版本分支。
        // 所有渠道都在这里建一次：重复调用是幂等的，但没必要在每次发通知时都建。
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID_CAPTURE,
                getString(R.string.capture_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.capture_notification_text)
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                RecurringWorker.CHANNEL_ID_RECURRING, "账单提醒", NotificationManager.IMPORTANCE_DEFAULT
            )
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID_BUDGET_ALERT, "预算超支提醒", NotificationManager.IMPORTANCE_DEFAULT)
        )
        Log.d(TAG, "Notification channels created")
    }

    private fun registerSmsContentObserver() {
        // ⚠️ 必须用后台线程的 Handler：SmsContentObserver.onChange 里要查 content://sms（磁盘读），
        // 传主 Looper 会触发 StrictMode 磁盘读违规，ANR 风险。
        val thread = HandlerThread("sms-observer").apply { start() }
        smsContentObserver = SmsContentObserver(Handler(thread.looper))
        contentResolver.registerContentObserver(
            Telephony.Sms.Inbox.CONTENT_URI,
            true,  // notifyForDescendants=true，子 URI 变化也触发
            smsContentObserver
        )
        // 关键：先把增量游标推到当前最大 _ID，否则首次 onChange 会把全部历史短信灌进来。
        // post 到同一个 HandlerThread，与 onChange 串行 → 保证在首次回调之前执行完
        Handler(thread.looper).post { smsContentObserver.primeToLatest() }
        writeDebug("✅ SmsContentObserver registered")
    }

    private fun writeDebug(msg: String) {
        FileLog.append(this, "debug.log", msg)
    }

    /** 启动期一次性后台任务的常驻作用域（复用，避免每次任务新建 CoroutineScope） */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 一次性后台任务：Application 启动时跑一次就结束，scope 随 App 生命周期存在。
     * 失败仅记录日志，不抛给系统（这些都不影响首发阶段 UI，但行为正确性依赖它们）。
     */
    private fun launchIo(tag: String, block: suspend () -> Unit) {
        appScope.launch {
            try { block() } catch (e: Exception) { Log.e(TAG, "$tag failed", e) }
        }
    }

    companion object {
        lateinit var instance: BookkeepingApp
            private set

        const val CHANNEL_ID_CAPTURE = "capture_service"
        const val CHANNEL_ID_BUDGET_ALERT = "budget_alert"
        const val TAG = "Bookkeeping"

        /**
         * 任意交易写入的统一通知入口（applyBalance / CSV 导入在写入那一刻调用）。
         * 仅当 App 已初始化（instance 就绪）才生效；写入不可能早于 App 启动，故安全。
         * 趁 App 在前台触发 widget 刷新，绕开后台 AppWidget 更新节流。
         */
        fun notifyTransactionChanged() {
            if (this::instance.isInitialized) instance.requestWidgetRefresh()
        }
    }
}

/** 统一文件日志：Service / 短信接收器 / App 三处共用；超 1MB 截断保留后半段，防无限膨胀 */
internal object FileLog {
    private const val MAX_BYTES = 1_000_000L
    // DateTimeFormatter 线程安全可复用（java.time 自 API 26 可用），替代每次 new SimpleDateFormat
    private val TIME_FMT = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")

    fun append(context: android.content.Context, fileName: String, msg: String) {
        try {
            // 轮转 + 追加在同一临界区，避免并发写交错
            synchronized(this) {
                val f = java.io.File(context.filesDir, fileName)
                // 轮转：超限时保留后半段（最新日志在尾部，不会丢最近记录）
                if (f.length() > MAX_BYTES) {
                    val bytes = f.readBytes()
                    f.writeBytes(bytes.copyOfRange(bytes.size / 2, bytes.size))
                }
                val ts = TIME_FMT.format(java.time.LocalTime.now())
                java.io.FileWriter(f, true).use { it.append("$ts  $msg\n") }
            }
            Log.d(BookkeepingApp.TAG, msg)
        } catch (e: Exception) {
            Log.e(BookkeepingApp.TAG, "FileLog($fileName) failed: ${e.message}")
        }
    }
}
