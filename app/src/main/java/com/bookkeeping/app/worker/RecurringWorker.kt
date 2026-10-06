package com.bookkeeping.app.worker
import com.bookkeeping.app.applyBalance

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
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
import com.bookkeeping.app.formatTime
import java.util.Calendar
import java.util.concurrent.TimeUnit
import com.bookkeeping.app.formatAmount
import com.bookkeeping.app.withDefaultAssociation
import androidx.room.withTransaction
import com.bookkeeping.app.round2

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
        var failed = false

        try {
            // 1. 处理信用卡账单分期：独立于周期任务执行，不因周期任务无到期项而跳过
            //    （否则只有分期计划时，到期期数永远不会入账/提醒）
            processInstallments(db, now)

            // 2. 找出所有到期的周期任务
            val due = db.recurringDao().getDue(now)
            Log.i(BookkeepingApp.TAG, "🔁 RecurringWorker: ${due.size} item(s) due")

            for (item in due) {
                try {
                    // ⚠️ 「入账 + 推进 nextRunAt」必须在同一事务里。
                    // 早先两者分离：insert 成功但 update 抛异常时落入 catch → failed=true →
                    // Result.retry() 让 WorkManager 重跑整个 doWork，而该项仍在 getDue 里，
                    // 于是同一笔被重复入账。加了事务后即使 retry，nextRunAt 已推进不会二次命中。
                    var stopped = false
                    db.withTransaction {
                        when (item.mode) {
                            RecurringItem.Mode.REMIND -> sendReminder(item)
                            RecurringItem.Mode.AUTO_TX -> autoInsertTx(db, item)
                        }
                        // 已运行次数 +1，计算下一次运行时间，并按结束条件决定是否停用
                        val newRunCount = item.runCount + 1
                        // 以「本期计划时间」而非当前时间为基准推算下一期：
                        // 用 now 的话，执行每延迟一次，计划日就永久漂移一次（10 号 → 18 号 → …）
                        val base = if (item.nextRunAt > 0L) item.nextRunAt else now
                        val nextRun = computeNextRun(item, base)
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
                        stopped = shouldStop
                    }
                    if (stopped) {
                        Log.i(BookkeepingApp.TAG, "🏁 ${item.name} 达到结束条件，已停用")
                    }
                } catch (e: Exception) {
                    // 单项失败：也要推进 nextRunAt，否则该项会被 getDue 反复命中形成活锁
                    Log.e(BookkeepingApp.TAG, "RecurringWorker error for ${item.name}", e)
                    failed = true
                    runCatching {
                        db.recurringDao().update(
                            item.copy(
                                runCount = item.runCount + 1,
                                // 这里同样要以本期计划时间为基准，避免失败后计划日漂移
                                nextRunAt = computeNextRun(
                                    item,
                                    if (item.nextRunAt > 0L) item.nextRunAt else now
                                )
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(BookkeepingApp.TAG, "RecurringWorker doWork failed", e)
            failed = true
        } finally {
            // 预算超支检查已下沉到 autoInsertTx / processInstallments 中
            // （按每笔交易实际归属账本独立检查，不再全账本汇总）
            // 无论成败都必须排下一次，否则一次 DB 异常就会让整条调度链永久停摆
            runCatching { scheduleNext(applicationContext, db) }
                .onFailure { Log.e(BookkeepingApp.TAG, "scheduleNext failed", it) }
        }

        return if (failed) Result.retry() else Result.success()
    }

    /**
     * 分期计划：把所有已到期但未入账的期数处理掉（自动入账或仅提醒）。
     *
     * 每个计划包在一个数据库事务里：若不这样做，第 3 期 insert 成功后进程崩溃、
     * paidPeriods 尚未推进，下次 worker 会把同一期再入账一次（该路径不走 findDuplicate 去重）。
     */
    private suspend fun processInstallments(db: AppDatabase, now: Long) {
        val plans = try {
            db.installmentDao().getActive()
        } catch (e: Exception) {
            Log.e(BookkeepingApp.TAG, "processInstallments: getActive failed", e)
            return
        }
        for (plan in plans) {
            // 账户名在整个循环中不变，查一次即可（原实现每期都查一次，12 期 = 12 次 N+1）
            val accountName = try {
                db.accountDao().getById(plan.accountId)?.name ?: "信用卡"
            } catch (e: Exception) {
                Log.e(BookkeepingApp.TAG, "processInstallments: getById failed plan=${plan.id}", e)
                "信用卡"
            }

            // 事务内只做 DB 写；通知与预算检查放事务外，避免长事务拖住 IO
            val pending = try {
                db.withTransaction {
                    var updated = plan
                    val due = mutableListOf<Triple<Int, Double, Long>>() // 期数, 金额, 到期日
                    val ledgerIds = mutableSetOf<Long?>()
                    while (updated.paidPeriods < updated.installments) {
                        val periodNo = updated.paidPeriods + 1
                        val dueAt = installmentDueDate(updated.firstDate, periodNo)
                        if (dueAt > now) break

                        val (principal, fee) = installmentPeriodAmounts(updated, periodNo)
                        val amount = (principal + fee).round2()

                        if (updated.mode == InstallmentPlan.Mode.REMIND) {
                            // 仅提醒：推进期数，不生成交易
                            due += Triple(periodNo, amount, dueAt)
                        } else {
                            val tx = Transaction(
                                amount = amount,
                                type = Transaction.Type.EXPENSE,
                                category = updated.category,
                                merchant = accountName,
                                source = "账单分期",
                                accountId = updated.accountId,
                                ledgerId = updated.ledgerId, // 显式记账账本（null = 跟随账户/默认账本）
                                note = buildString {
                                    append("账单分期 第$periodNo/${updated.installments}期")
                                    if (fee > 0) append("（本金${principal.formatAmount()} 手续费${fee.formatAmount()}）")
                                },
                                rawText = "[分期] $accountName 第$periodNo 期",
                                isManual = false,
                                confirmed = true,
                                confidence = Transaction.Confidence.HIGH,
                                // 记在本期到期日：久未打开 App 补跑时多期一次性入账，
                                // 用当天时间会让 12 期全挤在今天，当月账单/预算口径全错
                                occurredAt = dueAt
                            )
                            val savedTx = tx.withDefaultAssociation(db)
                            db.transactionDao().insert(savedTx)
                            applyBalance(db, savedTx)
                            ledgerIds += savedTx.ledgerId
                            Log.i(BookkeepingApp.TAG,
                                "💳 分期入账 plan=${updated.id} 第$periodNo/${updated.installments}期 ¥$amount")
                        }

                        updated = updated.copy(
                            paidPeriods = periodNo,
                            status = if (periodNo >= updated.installments) InstallmentPlan.Status.DONE
                                     else InstallmentPlan.Status.ACTIVE
                        )
                    }
                    if (updated != plan) db.installmentDao().update(updated)
                    due to ledgerIds
                }
            } catch (e: Exception) {
                // 事务回滚：已插入的交易与期数推进一起撤销，不会出现半截数据
                Log.e(BookkeepingApp.TAG, "Installment process error plan=${plan.id}", e)
                continue
            }

            val (reminders, ledgerIds) = pending
            reminders.forEach { (periodNo, amount, dueAt) ->
                sendInstallmentReminder(plan, periodNo, amount, accountName, dueAt)
            }
            // 预算超支检查：按该笔实际归属账本（每账本每月最多提醒一次）
            ledgerIds.forEach { checkBudgetAndNotify(applicationContext, ledgerId = it) }
        }
    }

    /** 分期提醒通知（mode=REMIND 时到期推送，内容含期数与金额） */
    private fun sendInstallmentReminder(
        plan: InstallmentPlan,
        periodNo: Int,
        amount: Double,
        accountName: String,
        due: Long
    ) {
        val requestCode = (plan.id * 31 + 7).toInt()
        val body = "$accountName 第$periodNo/${plan.installments}期 ¥${amount.formatAmount()}" +
            "（还款日 ${formatTime(due, "yyyy-MM-dd")}）"

        postNotification(
            tag = "${NOTIF_TAG}installment_${plan.id}",
            requestCode = requestCode,
            title = "💳 账单分期提醒",
            body = body,
            bigText = true
        )
        Log.i(BookkeepingApp.TAG, "⏰ 分期提醒 plan=${plan.id} 第$periodNo/${plan.installments}期 ¥$amount")
    }

    private fun sendReminder(item: RecurringItem) {
        val body = buildString {
            append(item.name)
            item.amount?.let { append(" ¥${it.formatAmount()}") }
            if (item.note.isNotBlank()) append(" — ${item.note}")
        }
        postNotification(
            tag = "${NOTIF_TAG}${item.id}",
            requestCode = item.id.toInt(),
            title = "⏰ 账单提醒：${item.name}",
            body = body,
            bigText = false
        )
    }

    /** 分期提醒与周期提醒共用的通知构建（原先两份 PendingIntent + Builder 逐字重复） */
    private fun postNotification(
        tag: String,
        requestCode: Int,
        title: String,
        body: String,
        bigText: Boolean
    ) {
        val ctx = applicationContext
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val pendingIntent = PendingIntent.getActivity(
            ctx, requestCode,
            Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(ctx, CHANNEL_ID_RECURRING)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
        if (bigText) builder.setStyle(NotificationCompat.BigTextStyle().bigText(body))
        nm.notify(tag, requestCode, builder.build())
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
            // 用本期计划时间：worker 延迟执行（隔月才补跑）时，钱应该记在它本来该发生的那天，
            // 而不是全部挤到执行当天，否则当月支出/预算/报表口径全错
            occurredAt = if (item.nextRunAt > 0L && item.nextRunAt <= System.currentTimeMillis())
                item.nextRunAt else System.currentTimeMillis()
        )
        val saved = tx.withDefaultAssociation(db)
        db.transactionDao().insert(saved)
        applyBalance(db, saved)
        // 预算超支检查：按该笔实际归属账本（每账本每月最多提醒一次）
        checkBudgetAndNotify(applicationContext, ledgerId = saved.ledgerId)
        Log.i(BookkeepingApp.TAG, "✅ Auto-inserted recurring tx: ${item.name} amt=${item.amount}")
    }

    companion object {
        const val CHANNEL_ID_RECURRING = "recurring_reminder"
        const val NOTIF_TAG = "recurring_"

        /** Calendar 的 DAY_OF_WEEK（周日=1）→ ISO 星期（周一=1..周日=7） */
        private fun toIsoWeekday(cal: Calendar): Int =
            ((cal.get(Calendar.DAY_OF_WEEK) + 5) % 7) + 1

        /** 计算下一次运行时间 */
        fun computeNextRun(item: RecurringItem, from: Long): Long {
            val cal = Calendar.getInstance().apply { timeInMillis = from }
            when (item.period) {
                RecurringItem.Period.DAILY -> cal.add(Calendar.DAY_OF_MONTH, 1)
                RecurringItem.Period.WEEKLY -> {
                    // 先加一周，再对齐到用户指定的星期。
                    // 不对齐会让「每周三」的任务按创建日逐周平移，界面显示与实际执行永久错位。
                    cal.add(Calendar.WEEK_OF_YEAR, 1)
                    val target = item.dayOfWeek
                    if (target != null) {
                        cal.add(Calendar.DAY_OF_MONTH, target - toIsoWeekday(cal))
                    }
                }
                RecurringItem.Period.MONTHLY -> {
                    // 先把"日"置为 1，再加月份：避免 31 号在 add(MONTH) 时溢出到下下个月，
                    // 之后再钳制到目标月的实际最大天数。
                    val dom = item.dayOfMonth
                    cal.set(Calendar.DAY_OF_MONTH, 1)
                    cal.add(Calendar.MONTH, 1)
                    if (dom != null) {
                        cal.set(Calendar.DAY_OF_MONTH, dom.coerceIn(1, cal.getActualMaximum(Calendar.DAY_OF_MONTH)))
                    }
                }
                RecurringItem.Period.YEARLY -> {
                    // 同理：先置 1 再加年，再钳制。
                    val dom = item.dayOfMonth
                    cal.set(Calendar.DAY_OF_MONTH, 1)
                    cal.add(Calendar.YEAR, 1)
                    if (dom != null) {
                        cal.set(Calendar.DAY_OF_MONTH, dom.coerceIn(1, cal.getActualMaximum(Calendar.DAY_OF_MONTH)))
                    }
                }
            }
            return cal.timeInMillis
        }

        /** 排下一次 OneTimeWorkRequest（suspend：从协程上下文直接 await DB，不再 runBlocking）。
         *  触发源 = 周期任务下次运行 ∪ 分期计划下一期到期，保证只有分期时也能按期触发。 */
        suspend fun scheduleNext(context: Context, db: AppDatabase) {
            val nextRecurring = db.recurringDao().nextEnabledRunAt()
            val nextPlanDue = try {
                db.installmentDao().getActive()
                    .mapNotNull { plan ->
                        if (plan.paidPeriods < plan.installments)
                            installmentDueDate(plan.firstDate, plan.paidPeriods + 1) else null
                    }
                    .minOrNull()
            } catch (e: Exception) {
                Log.e(BookkeepingApp.TAG, "scheduleNext: installment due query failed", e)
                null
            }
            val soonest = minOf(nextRecurring ?: Long.MAX_VALUE, nextPlanDue ?: Long.MAX_VALUE)
            if (soonest == Long.MAX_VALUE) return
            val delayMs = (soonest - System.currentTimeMillis()).coerceAtLeast(60_000L) // 至少 1 分钟后
            enqueue(context, delayMs)
        }

        private fun enqueue(context: Context, delayMs: Long) {
            val request = OneTimeWorkRequestBuilder<RecurringWorker>()
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork("recurring_next", ExistingWorkPolicy.REPLACE, request)
            Log.d(BookkeepingApp.TAG, "⏳ RecurringWorker scheduled in ${delayMs / 60000} min")
        }

        /** 立即触发一次（用于开机 + 首次启动） */
        fun triggerNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<RecurringWorker>().build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork("recurring_next", ExistingWorkPolicy.REPLACE, request)
        }
    }
}
