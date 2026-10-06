package com.bookkeeping.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.widget.RemoteViews
import com.bookkeeping.app.FileLog
import com.bookkeeping.app.MainActivity
import com.bookkeeping.app.R
import com.bookkeeping.app.data.AppDatabase
import com.bookkeeping.app.data.entity.Transaction
import com.bookkeeping.app.formatNow
import com.bookkeeping.app.formatTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.Calendar
import java.util.Locale

/**
 * 桌面 Widget（原生 RemoteViews 实现，非 Glance）。
 *
 * 为什么不用 Glance：Glance 的 update()/updateAll() 是「事件入队即返回」——真正的
 * provideGlance + updateAppWidget 在 Glance 内部 session 的异步事件循环里稍后才执行，
 * 且 session 会缓存 lastRemoteViews。App 退后台后事件循环被系统节流/暂停，事件迟迟不被
 * 消费 → provideGlance 不重跑 → 数据（尤其「最近一笔」）停在旧值。这是 Glance 架构的固有
 * 限制，任何「调用 update」都绕不过。
 *
 * 原生 AppWidgetProvider.onUpdate 里同步读库 + AppWidgetManager.updateAppWidget() 是
 * 同步、立即生效的，在前台调用完全不受后台节流影响，彻底解决「手动增删后不即时刷新」。
 *
 * 视觉要点：背景全透明（透壁纸）；文字固定白色；金额支出红/收入绿；无 emoji。
 */
class BookkeepingWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        appWidgetIds.forEach { id ->
            runBlocking { refreshWidget(context, appWidgetManager, id) }
        }
    }

    companion object {

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** 供 App 内任意写入路径调用的即时刷新入口（同步读库 + updateAppWidget） */
        fun updateAll(context: Context) {
            scope.launch {
                val mgr = AppWidgetManager.getInstance(context)
                val ids = mgr.getAppWidgetIds(ComponentName(context, BookkeepingWidgetReceiver::class.java))
                if (ids.isEmpty()) {
                    FileLog.append(context, "debug.log", "⚠️ Widget 无实例，跳过刷新")
                    return@launch
                }
                ids.forEach { id -> refreshWidget(context, mgr, id) }
                FileLog.append(context, "debug.log", "✅ Widget 刷新成功 ids=${ids.size}")
            }
        }

        /**
         * 读库 + 构建 RemoteViews + updateAppWidget。数据一写入即反映到桌面。
         * 由 onUpdate / updateAll 调用；异常仅记日志，不抛给系统。
         */
        private suspend fun refreshWidget(context: Context, mgr: AppWidgetManager, widgetId: Int) {
            try {
                val data = loadData(context)
                FileLog.append(
                    context, "debug.log",
                    "🎯 Widget 取数: todayExp=${data.todayExp} todayInc=${data.todayInc} " +
                        "monthExp=${data.monthExp} monthInc=${data.monthInc} latest=${data.latest?.amount}"
                )
                val views = buildViews(context, data)
                mgr.updateAppWidget(widgetId, views)
            } catch (e: Exception) {
                FileLog.append(context, "debug.log", "❌ Widget 刷新失败: ${e.message}")
            }
        }

        private data class Data(
            val todayExp: Double,
            val todayInc: Double,
            val monthExp: Double,
            val monthInc: Double,
            val latest: Transaction?
        )

        private suspend fun loadData(context: Context): Data {
            val db = AppDatabase.getInstance(context)
            val cal = Calendar.getInstance()

            val todayStart = cal.clone() as Calendar
            todayStart.set(Calendar.HOUR_OF_DAY, 0); todayStart.set(Calendar.MINUTE, 0)
            todayStart.set(Calendar.SECOND, 0); todayStart.set(Calendar.MILLISECOND, 0)
            val todayEnd = (todayStart.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, 1) }

            val monthStart = cal.clone() as Calendar
            monthStart.set(Calendar.DAY_OF_MONTH, 1)
            monthStart.set(Calendar.HOUR_OF_DAY, 0); monthStart.set(Calendar.MINUTE, 0)
            monthStart.set(Calendar.SECOND, 0); monthStart.set(Calendar.MILLISECOND, 0)
            val monthEnd = (monthStart.clone() as Calendar).apply { add(Calendar.MONTH, 1) }

            return Data(
                todayExp = db.transactionDao().sumAmount(Transaction.Type.EXPENSE.name, todayStart.timeInMillis, todayEnd.timeInMillis),
                todayInc = db.transactionDao().sumAmount(Transaction.Type.INCOME.name, todayStart.timeInMillis, todayEnd.timeInMillis),
                monthExp = db.transactionDao().sumAmount(Transaction.Type.EXPENSE.name, monthStart.timeInMillis, monthEnd.timeInMillis),
                monthInc = db.transactionDao().sumAmount(Transaction.Type.INCOME.name, monthStart.timeInMillis, monthEnd.timeInMillis),
                latest = db.transactionDao().getRecent(1).firstOrNull()
            )
        }

        /** 金额格式化：千分位 + 固定两位小数 */
        private fun amount(v: Double): String = String.format(Locale.getDefault(), "%,.2f", v)

        private fun isToday(ts: Long): Boolean = formatTime(ts, "yyyyMMdd") == formatNow("yyyyMMdd")

        private fun buildViews(context: Context, d: Data): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_main)

            // 日期
            views.setTextViewText(R.id.widget_date, formatNow("M月d日"))

            // 今日 / 本月统计
            views.setTextViewText(R.id.widget_today_exp, "-¥${amount(d.todayExp)}")
            views.setTextViewText(R.id.widget_today_inc, "+¥${amount(d.todayInc)}")
            views.setTextViewText(R.id.widget_month_exp, "-¥${amount(d.monthExp)}")
            views.setTextViewText(R.id.widget_month_inc, "+¥${amount(d.monthInc)}")

            // 最近一笔
            val tx = d.latest
            val name = when {
                tx == null -> "暂无记录"
                tx.merchant.isBlank() -> tx.category
                else -> "${tx.category} · ${tx.merchant}"
            }
            val time = when {
                tx == null -> "点击卡片开始记账"
                isToday(tx.occurredAt) -> formatTime(tx.occurredAt, "HH:mm")
                else -> formatTime(tx.occurredAt, "MM-dd")
            }
            val amountText = tx?.let {
                val sign = if (it.type == Transaction.Type.EXPENSE) "-" else "+"
                "$sign¥${amount(it.amount)}"
            } ?: "—"
            val amountColor = when (tx?.type) {
                Transaction.Type.INCOME -> R.color.widget_income
                Transaction.Type.TRANSFER -> R.color.widget_accent
                else -> R.color.widget_expense
            }

            views.setTextViewText(R.id.widget_latest_name, name)
            views.setTextViewText(R.id.widget_latest_time, time)
            views.setTextViewText(R.id.widget_latest_amount, amountText)
            views.setTextColor(R.id.widget_latest_amount, context.getColor(amountColor))

            // 点击整卡回到 App
            val intent = Intent(context, MainActivity::class.java)
            val pi = PendingIntent.getActivity(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, pi)

            return views
        }
    }
}

/** 清单里引用的 receiver 名保持不变；改为继承原生 AppWidgetProvider */
class BookkeepingWidgetReceiver : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        BookkeepingWidgetProvider().onUpdate(context, appWidgetManager, appWidgetIds)
    }
}
