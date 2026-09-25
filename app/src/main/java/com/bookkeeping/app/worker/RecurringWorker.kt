package com.bookkeeping.app.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.bookkeeping.app.BookkeepingApp
import com.bookkeeping.app.MainActivity
import com.bookkeeping.app.R
import com.bookkeeping.app.checkBudgetAndNotify
import com.bookkeeping.app.data.AppDatabase
import com.bookkeeping.app.data.entity.InstallmentPlan
import com.bookkeeping.app.data.entity.RecurringItem
import com.bookkeeping.app.data.entity.Transaction
import com.bookkeeping.app.data.entity.installmentDueDate
import com.bookkeeping.app.data.entity.installmentPeriodAmounts
import java.util.Calendar
import com.bookkeeping.app.formatAmount

/**
 * 周期调度 Worker：
 *  - REMIND → 推一条提醒通知
 *  - AUTO_TX → 自动 insert 一条 Transaction
 * 然后计算 nextRunAt 更新回 DB。
 *
 * 调度方式：每次执行完后，根据 nextRunAt 算 InitialDelay，用 OneTimeWorkRequest 排下一次。
 */
class RecurringWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val db = AppDatabase.getInstance(applicationContext)
        val now = System.currentTimeMillis()

        // 1. 找出所有到期的
        val due = db.recurringDao().getDue(now)
        if (due.isEmpty()) {
            scheduleNext(applicationContext, db)
            return Result.success()
        }

        Log.i(BookkeepingApp.TAG, "🔁 RecurringWorker: ${due.size} item(s) due")

        for (item in due) {
            try {
                when (item.mode) {
                    RecurringItem.Mode.REMIND -> sendReminder(item)
                    RecurringItem.Mode.AUTO_TX -> autoInsertTx(db, item)
                }
                // 已运行次数 +1，计算下一次运行时间，并按结束条件决定是否停用
                val newRunCount = item.runCount + 1
                val nextRun = computeNextRun(item, now)
                val shouldStop = when (item.endMode) {
                    RecurringItem.EndMode.NEVER -> false
                    RecurringItem.EndMode.AFTER_COUNT ->
                        item.endAfterCount != null && newRunCount >= item.endAfterCount
                    RecurringItem.EndMode.ON_DATE ->
                        item.endDate != null && nextRun > item.endDate
                }
                db.recurringDao().update(
                    item.copy(
                        runCount = newRunCount,
                        nextRunAt = nextRun,
                        isEnabled = if (shouldStop) false else item.isEnabled
                    )
                )
                if (shouldStop) {
                    Log.i(BookkeepingApp.TAG, "🏁 ${item.name} 达到结束条件，已停用")
                }
            } catch (e: Exception) {
                Log.e(BookkeepingApp.TAG, "RecurringWorker error for ${item.name}", e)
            }
        }

        // 2. 处理信用卡账单分期
        processInstallments(db, now)

        // 3. 预算超支检查（每自然月最多提醒一次）
        checkBudgetAndNotify(applicationContext)

        scheduleNext(applicationContext, db)
        return Result.success()
    }

    /** 分期计划：把所有已到期但未入账的期数补记为支出交易 */
    private suspend fun processInstallments(db: AppDatabase, now: Long) {
        val plans = try { db.installmentDao().getActive() } catch (_: Exception) { return }
        for (plan in plans) {
            try {
                var updated = plan
                var changed = false
                while (updated.paidPeriods < updated.installments) {
                    val periodNo = updated.paidPeriods + 1
                    val due = installmentDueDate(updated.firstDate, periodNo)
                    if (due > now) break

                    val (principal, fee) = installmentPeriodAmounts(updated, periodNo)
                    val account = db.accountDao().getById(updated.accountId)
                    val tx = Transaction(
                        amount = principal + fee,
                        type = Transaction.Type.EXPENSE,
                        category = updated.category,
                        merchant = account?.name ?: "信用卡",
                        source = "账单分期",
                        accountId = updated.accountId,
                        note = buildString {
                            append("账单分期 第${periodNo}/${updated.installments}期")
                            if (fee > 0) append("（本金${principal.formatAmount()} 手续费${fee.formatAmount()}）")
                        },
                        rawText = "[分期] ${account?.name ?: ""} 第$periodNo 期",
                        isManual = false,
                        confirmed = true,
                        confidence = Transaction.Confidence.HIGH,
                        occurredAt = System.currentTimeMillis()
                    )
                    db.transactionDao().insert(tx)
                    updated = updated.copy(
                        paidPeriods = periodNo,
                        status = if (periodNo >= updated.installments) InstallmentPlan.Status.DONE
                                 else InstallmentPlan.Status.ACTIVE
                    )
                    changed = true
                    Log.i(BookkeepingApp.TAG,
                        "💳 分期入账 plan=${updated.id} 第${periodNo}/${updated.installments}期 ¥${principal + fee}")
                }
                if (changed) db.installmentDao().update(updated)
            } catch (e: Exception) {
                Log.e(BookkeepingApp.TAG, "Installment process error plan=${plan.id}", e)
            }
        }
    }

    private fun sendReminder(item: RecurringItem) {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID_RECURRING,
                "账单提醒",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            nm.createNotificationChannel(channel)
        }

        val pendingIntent = PendingIntent.getActivity(
            applicationContext, item.id.toInt(),
            Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val body = buildString {
            append(item.name)
            item.amount?.let { append(" ¥${it.formatAmount()}") }
            if (item.note.isNotBlank()) append(" — ${item.note}")
        }

        val notif = NotificationCompat.Builder(applicationContext, CHANNEL_ID_RECURRING)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("⏰ 账单提醒：${item.name}")
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        nm.notify("${NOTIF_TAG}${item.id}", item.id.toInt(), notif)
    }

    private suspend fun autoInsertTx(db: AppDatabase, item: RecurringItem) {
        if (item.amount == null || item.txType == null) return

        val tx = Transaction(
            amount = item.amount,
            type = when (item.txType) {
                RecurringItem.TxType.EXPENSE -> Transaction.Type.EXPENSE
                RecurringItem.TxType.INCOME -> Transaction.Type.INCOME
            },
            category = item.category ?: "其他",
            merchant = item.name,
            source = "周期自动",
            note = item.note,
            rawText = "[周期] ${item.name} ${item.amount}",
            isManual = false,
            confirmed = true,
            confidence = Transaction.Confidence.HIGH,
            occurredAt = System.currentTimeMillis()
        )
        db.transactionDao().insert(tx)
        Log.i(BookkeepingApp.TAG, "✅ Auto-inserted recurring tx: ${item.name} amt=${item.amount}")
    }

    companion object {
        const val CHANNEL_ID_RECURRING = "recurring_reminder"
        const val NOTIF_TAG = "recurring_"

        /** 计算下一次运行时间 */
        fun computeNextRun(item: RecurringItem, from: Long): Long {
            val cal = Calendar.getInstance().apply { timeInMillis = from }
            when (item.period) {
                RecurringItem.Period.DAILY -> cal.add(Calendar.DAY_OF_MONTH, 1)
                RecurringItem.Period.WEEKLY -> cal.add(Calendar.WEEK_OF_YEAR, 1)
                RecurringItem.Period.MONTHLY -> {
                    cal.add(Calendar.MONTH, 1)
                    item.dayOfMonth?.let { dom ->
                        cal.set(Calendar.DAY_OF_MONTH, dom.coerceIn(1, cal.getActualMaximum(Calendar.DAY_OF_MONTH)))
                    }
                }
                RecurringItem.Period.YEARLY -> {
                    cal.add(Calendar.YEAR, 1)
                    item.dayOfMonth?.let { dom ->
                        cal.set(Calendar.DAY_OF_MONTH, dom.coerceIn(1, cal.getActualMaximum(Calendar.DAY_OF_MONTH)))
                    }
                }
            }
            return cal.timeInMillis
        }

        /** 排下一次 OneTimeWorkRequest */
        fun scheduleNext(context: Context, db: AppDatabase) {
            val enabled = runBlockingIO { db.recurringDao().getAllEnabled() }
            val soonest = enabled.minOfOrNull { it.nextRunAt } ?: return
            val delayMs = (soonest - System.currentTimeMillis()).coerceAtLeast(60_000L) // 至少 1 分钟后
            enqueue(context, delayMs)
        }

        private fun enqueue(context: Context, delayMs: Long) {
            val request = androidx.work.OneTimeWorkRequestBuilder<RecurringWorker>()
                .setInitialDelay(delayMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                .build()
            androidx.work.WorkManager.getInstance(context)
                .enqueueUniqueWork("recurring_next",
                    androidx.work.ExistingWorkPolicy.REPLACE, request)
            Log.d(BookkeepingApp.TAG, "⏳ RecurringWorker scheduled in ${delayMs / 60000} min")
        }

        /** 立即触发一次（用于开机 + 首次启动） */
        fun triggerNow(context: Context) {
            val request = androidx.work.OneTimeWorkRequestBuilder<RecurringWorker>().build()
            androidx.work.WorkManager.getInstance(context)
                .enqueueUniqueWork("recurring_next",
                    androidx.work.ExistingWorkPolicy.REPLACE, request)
        }

        private fun <T> runBlockingIO(block: suspend () -> T): T {
            return kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) { block() }
        }
    }
}
