package com.bookkeeping.app

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.provider.Settings
import com.bookkeeping.app.data.AppDatabase
import com.bookkeeping.app.data.entity.Budget
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

// ─── 按账本预算管理（v11） ─────────────────────────────────

private const val CHANNEL_BUDGET_ALERT = "budget_alert"
/** 通知 ID：勿用 1001，那是通知监听服务的前台通知 ID，撞号会顶掉前台通知 */
private const val BUDGET_NOTIF_ID = 1002

/** 读取某账本预算；无记录返回 0（= 未设置预算） */
suspend fun getBudgetForLedger(context: Context, ledgerId: Long): Double {
    val db = AppDatabase.getInstance(context)
    return db.budgetDao().getByLedger(ledgerId)?.monthlyAmount ?: 0.0
}

/** 设置某账本预算；amount <= 0 表示取消预算（删除记录） */
suspend fun setBudgetForLedger(context: Context, ledgerId: Long, amount: Double) {
    val db = AppDatabase.getInstance(context)
    if (amount <= 0) {
        db.budgetDao().deleteByLedger(ledgerId)
        return
    }
    if (db.budgetDao().getByLedger(ledgerId) == null) {
        db.budgetDao().insert(Budget(ledgerId = ledgerId, monthlyAmount = amount))
    } else {
        db.budgetDao().updateAmount(ledgerId, amount, System.currentTimeMillis())
    }
}

/**
 * 预算超支检查：只检查【指定账本】的预算，不再有全账本汇总分支。
 * ledgerId 为 null（手动记账未选账本）时按默认账本检查，
 * 与自动记账 withDefaultAssociation 归默认账本语义对齐。
 * 每个账本每月独立提醒一次（notifiedMonth 存于预算行）。
 */
suspend fun checkBudgetAndNotify(context: Context, ledgerId: Long?) {
    try {
        val db = AppDatabase.getInstance(context)
        val target = ledgerId
            ?: db.ledgerDao().getDefault()?.id
            ?: db.ledgerDao().getAll().firstOrNull()?.id
            ?: return
        checkBudgetAndNotifyForLedger(context, target)
    } catch (_: Exception) {
    }
}

private suspend fun checkBudgetAndNotifyForLedger(context: Context, ledgerId: Long) {
    try {
        val db = AppDatabase.getInstance(context)
        val budgetRow = db.budgetDao().getByLedger(ledgerId) ?: return // 该账本未设预算
        val budget = budgetRow.monthlyAmount
        if (budget <= 0) return
        val monthKey = SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(Date())
        if (budgetRow.notifiedMonth == monthKey) return // 该账本本月已提醒

        val cal = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val spent = withContext(Dispatchers.IO) {
            db.transactionDao().sumByLedger(ledgerId, "EXPENSE", cal.timeInMillis, System.currentTimeMillis())
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
            .setContentText("本月已支出 ¥${spent.formatAmount()}，超出预算 ¥${(spent - budget).formatAmount()}")
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        nm.notify(BUDGET_NOTIF_ID, notif)
        db.budgetDao().markNotified(ledgerId, monthKey)
    } catch (_: Exception) {
    }
}
