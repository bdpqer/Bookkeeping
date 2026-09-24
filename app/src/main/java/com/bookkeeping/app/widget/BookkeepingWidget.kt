package com.bookkeeping.app.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.bookkeeping.app.MainActivity
import com.bookkeeping.app.data.AppDatabase
import com.bookkeeping.app.data.entity.Transaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * 桌面 Widget：今日支出/收入 + 本月支出 + 最近 1 笔
 */
class BookkeepingWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = withContext(Dispatchers.IO) {
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

            WidgetData(
                todayExp = db.transactionDao().sumAmount(Transaction.Type.EXPENSE.name, todayStart.timeInMillis, todayEnd.timeInMillis),
                todayInc = db.transactionDao().sumAmount(Transaction.Type.INCOME.name, todayStart.timeInMillis, todayEnd.timeInMillis),
                monthExp = db.transactionDao().sumAmount(Transaction.Type.EXPENSE.name, monthStart.timeInMillis, monthEnd.timeInMillis),
                monthInc = db.transactionDao().sumAmount(Transaction.Type.INCOME.name, monthStart.timeInMillis, monthEnd.timeInMillis),
                latest = db.transactionDao().getRecent(1).firstOrNull()
            )
        }

        provideContent {
            WidgetContent(data)
        }
    }
}

private data class WidgetData(
    val todayExp: Double,
    val todayInc: Double,
    val monthExp: Double,
    val monthInc: Double,
    val latest: Transaction?
)

/** 两端对齐行：左 label 右 value（Glance 没有 SpaceBetween，用 defaultWeight spacer） */
@Composable
private fun JustifiedRow(label: String, labelColor: Color, value: String, valueColor: Color, bold: Boolean = false) {
    Row(modifier = GlanceModifier.fillMaxWidth()) {
        Text(text = label, style = TextStyle(color = ColorProvider(labelColor), fontSize = 11.sp))
        Spacer(modifier = GlanceModifier.defaultWeight())
        Text(
            text = value,
            style = TextStyle(
                color = ColorProvider(valueColor),
                fontSize = 12.sp,
                fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal
            )
        )
    }
}

@Composable
private fun WidgetContent(d: WidgetData) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(Color(0xFF1C1B1F))
            .padding(12.dp)
            .clickable(actionStartActivity(MainActivity::class.java)),
        verticalAlignment = Alignment.Vertical.CenterVertically
    ) {
        Text(
            text = "📒 记账助手",
            style = TextStyle(color = ColorProvider(Color.White), fontSize = 13.sp, fontWeight = FontWeight.Bold)
        )
        Spacer(modifier = GlanceModifier.height(6.dp))

        JustifiedRow(
            label = "今日支出", labelColor = Color(0xFFAAAAAA),
            value = "-¥${String.format("%.2f", d.todayExp)}",
            valueColor = Color(0xFFE57373), bold = true
        )
        JustifiedRow(
            label = "今日收入", labelColor = Color(0xFFAAAAAA),
            value = "+¥${String.format("%.2f", d.todayInc)}",
            valueColor = Color(0xFF81C784), bold = true
        )

        Spacer(modifier = GlanceModifier.height(4.dp))
        Spacer(modifier = GlanceModifier.fillMaxWidth().height(1.dp).background(Color(0xFF333333)))
        Spacer(modifier = GlanceModifier.height(4.dp))

        JustifiedRow(
            label = "本月支出", labelColor = Color(0xFFAAAAAA),
            value = "-¥${String.format("%.2f", d.monthExp)}",
            valueColor = Color(0xFFFFAB91)
        )

        d.latest?.let { tx ->
            Spacer(modifier = GlanceModifier.height(6.dp))
            Spacer(modifier = GlanceModifier.fillMaxWidth().height(1.dp).background(Color(0xFF333333)))
            Spacer(modifier = GlanceModifier.height(4.dp))
            val sign = if (tx.type == Transaction.Type.EXPENSE) "-" else "+"
            val color = when (tx.type) {
                Transaction.Type.EXPENSE -> Color(0xFFE57373)
                Transaction.Type.INCOME -> Color(0xFF81C784)
                Transaction.Type.TRANSFER -> Color(0xFFFFB74D)
            }
            JustifiedRow(
                label = "${tx.category}${tx.merchant.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""}",
                labelColor = Color.White,
                value = "${sign}¥${String.format("%.2f", tx.amount)}",
                valueColor = color, bold = true
            )
        }
    }
}

class BookkeepingWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = BookkeepingWidget()
}
