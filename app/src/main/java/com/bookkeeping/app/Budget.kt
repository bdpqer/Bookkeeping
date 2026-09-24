package com.bookkeeping.app

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.provider.Settings
import com.bookkeeping.app.data.AppDatabase
import com.bookkeeping.app.service.NotificationCaptureService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// ─── 工具方法 ─────────────────────────────────────────────

fun isNotificationListenerEnabled(context: Context): Boolean {
    val cn = ComponentName(context, NotificationCaptureService::class.java)
    val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: ""
    return flat.contains(cn.flattenToString())
}

// ─── 预算管理 ────────────────────────────────────────────────

private const val PREFS_BUDGET = "budget_prefs"
private const val KEY_MONTHLY_BUDGET = "monthly_budget"

fun getMonthlyBudget(context: Context): Double {
    val prefs = context.getSharedPreferences(PREFS_BUDGET, Context.MODE_PRIVATE)
    return prefs.getFloat(KEY_MONTHLY_BUDGET, 0f).toDouble()
}

fun setMonthlyBudget(context: Context, amount: Double) {
    context.getSharedPreferences(PREFS_BUDGET, Context.MODE_PRIVATE)
        .edit().putFloat(KEY_MONTHLY_BUDGET, amount.toFloat()).apply()
}

private const val KEY_BUDGET_NOTIFIED_MONTH = "budget_notified_month"
private const val CHANNEL_BUDGET_ALERT = "budget_alert"

/**
 * 预算超支检查：本月支出超过预算时发系统通知。
 * 挂钩点：手动记一笔入库后、通知/短信自动记账入库后、周期任务 Worker 执行后。
 * 每个自然月最多提醒一次（记录已提醒月份）。
 */
suspend fun checkBudgetAndNotify(context: Context) {
    try {
        val budget = getMonthlyBudget(context)
        if (budget <= 0) return
        val prefs = context.getSharedPreferences(PREFS_BUDGET, Context.MODE_PRIVATE)
        val monthKey = SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(Date())
        if (prefs.getString(KEY_BUDGET_NOTIFIED_MONTH, null) == monthKey) return

        val cal = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val monthStart = cal.timeInMillis
        val spent = withContext(Dispatchers.IO) {
            AppDatabase.getInstance(context).transactionDao()
                .sumAmount("EXPENSE", monthStart, System.currentTimeMillis())
        }.round2()
        if (spent <= budget) return

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                android.app.NotificationChannel(
                    CHANNEL_BUDGET_ALERT, "预算超支提醒",
                    android.app.NotificationManager.IMPORTANCE_DEFAULT
                )
            )
        }
        val notif = androidx.core.app.NotificationCompat.Builder(context, CHANNEL_BUDGET_ALERT)
            .setSmallIcon(com.bookkeeping.app.R.drawable.ic_launcher_foreground)
            .setContentTitle("⚠️ 本月预算已超支")
            .setContentText("本月已支出 ¥${"%.2f".format(spent)}，超出预算 ¥${"%.2f".format(spent - budget)}")
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        nm.notify(1001, notif)
        prefs.edit().putString(KEY_BUDGET_NOTIFIED_MONTH, monthKey).apply()
    } catch (_: Exception) {
    }
}
