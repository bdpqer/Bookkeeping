package com.bookkeeping.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.util.Log
import com.bookkeeping.app.data.AppDatabase
import com.bookkeeping.app.data.entity.Account
import com.bookkeeping.app.data.entity.Ledger
import com.bookkeeping.app.data.entity.MerchantRule
import com.bookkeeping.app.parser.ParseEngine
import com.bookkeeping.app.receiver.SmsContentObserver
import com.bookkeeping.app.worker.AutoBackupWorker
import com.bookkeeping.app.worker.RecurringWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.FileWriter
import java.io.PrintWriter

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
        RecurringWorker.triggerNow(this)
        AutoBackupWorker.ensureScheduled(this)
    }

    /** 首次启动初始化默认商家分类规则 */
    private fun seedMerchantRulesIfNeeded() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AppDatabase.getInstance(this@BookkeepingApp)
                if (db.merchantRuleDao().count() == 0) {
                    val defaults = listOf(
                    MerchantRule(keyword = "星巴克", category = "餐饮/饮品", note = "咖啡连锁"),
                    MerchantRule(keyword = "瑞幸", category = "餐饮/饮品", note = "咖啡连锁"),
                    MerchantRule(keyword = "喜茶", category = "餐饮/饮品", note = "奶茶连锁"),
                    MerchantRule(keyword = "蜜雪冰城", category = "餐饮/饮品", note = "奶茶连锁"),
                    MerchantRule(keyword = "奶茶", category = "餐饮/饮品"),
                    MerchantRule(keyword = "咖啡", category = "餐饮/饮品"),
                    MerchantRule(keyword = "美团", category = "餐饮/外卖"),
                    MerchantRule(keyword = "饿了么", category = "餐饮/外卖"),
                    MerchantRule(keyword = "外卖", category = "餐饮/外卖"),
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
    }

    /** 首次启动初始化默认账本和账户 */
    private fun seedDefaultsIfNeeded() {
        CoroutineScope(Dispatchers.IO).launch {
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
    }

    /** 首次启动时把硬编码规则写入 DB，后续 Service 全部从 DB 加载 */
    private fun seedParseRulesIfNeeded() {
        CoroutineScope(Dispatchers.IO).launch {
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
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID_CAPTURE,
                getString(R.string.capture_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.capture_notification_text)
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
            Log.d(TAG, "Notification channel created")
        }
    }

    private fun registerSmsContentObserver() {
        smsContentObserver = SmsContentObserver(Handler(Looper.getMainLooper()))
        contentResolver.registerContentObserver(
            Telephony.Sms.Inbox.CONTENT_URI,
            true,  // notifyForDescendants=true，子 URI 变化也触发
            smsContentObserver
        )
        writeDebug("✅ SmsContentObserver registered")
    }

    private fun writeDebug(msg: String) {
        try {
            val f = java.io.File(filesDir, "debug.log")
            val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                .format(java.util.Date())
            PrintWriter(FileWriter(f, true)).use { it.appendLine("$ts  $msg") }
        } catch (e: Exception) {
            Log.e(TAG, "writeDebug failed", e)
        }
    }

    companion object {
        lateinit var instance: BookkeepingApp
            private set

        const val CHANNEL_ID_CAPTURE = "capture_service"
        const val TAG = "Bookkeeping"
    }
}
