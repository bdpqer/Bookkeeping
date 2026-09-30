package com.bookkeeping.app.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.bookkeeping.app.MainActivity
import com.bookkeeping.app.data.AppDatabase
import com.bookkeeping.app.data.entity.Transaction
import com.bookkeeping.app.formatNow
import com.bookkeeping.app.formatTime
import com.bookkeeping.app.theme.WidgetAccent
import com.bookkeeping.app.theme.WidgetDivider
import com.bookkeeping.app.theme.WidgetExpense
import com.bookkeeping.app.theme.WidgetIncome
import com.bookkeeping.app.theme.WidgetText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Locale

/**
 * 桌面 Widget：今日支出/收入 + 本月支出/收入（2×2 网格）+ 最近 1 笔。
 *
 * 视觉要点：
 * - **背景全透明**，直接透出壁纸（不铺任何卡片底色）。
 * - 文字一律固定白色，不看壁纸明暗——刻意不做自适应：壁纸主色亮度判定在复杂
 *   照片壁纸上并不可靠（深绿植物壁纸曾被误判成亮壁纸，于是给出深色字，压在深绿
 *   上几乎看不清）。代价是浅色壁纸上白字偏淡，这是透明底方案的固有限制。
 * - 金额按「支出红/收入绿」着色，层级靠字号字重区分而非堆颜色。
 * - 不使用 emoji——部分机型桌面渲染环境缺少 emoji 字体回退，会把 emoji 画成色块。
 *
 * ⚠️ Glance 硬约束：**任意 Row/Column 的直接子项最多 10 个**。
 * Glance 底层是静态生成的 RemoteViews 布局，每种容器只预置了 10 个子项槽位，
 * 第 11 个子项不会报错、不会崩溃，而是**被静默丢弃**（曾导致「最近一笔」整行不显示：
 * 根 Column 恰好 11 个子项，前 10 个正常、最后 1 个凭空消失，编译和运行日志都没有异常）。
 * 排查手法：`adb shell uiautomator dump` 导出视图树，看子项 index 是否缺号。
 * 当前根 Column 为 7 个子项；需要更多区块时用嵌套 Column/Row 分组，不要平铺。
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

// ─── 配色：透明底，固定一套 ──────────────────────────────────
// 文字统一白色；金额用提亮过的红/绿，保证在中深色壁纸上清晰。改色请到 theme/Color.kt。

private val TextColor = ColorProvider(WidgetText)
private val ExpenseColor = ColorProvider(WidgetExpense)
private val IncomeColor = ColorProvider(WidgetIncome)
private val AccentColor = ColorProvider(WidgetAccent)
private val DividerColor = ColorProvider(WidgetDivider)

// ─── 格式化 ───────────────────────────────────────────────────

/** 小部件金额：带千分位 + 固定两位小数（大额更易读） */
private fun widgetAmount(v: Double): String = String.format(Locale.getDefault(), "%,.2f", v)

private fun isToday(ts: Long): Boolean = formatTime(ts, "yyyyMMdd") == formatNow("yyyyMMdd")

// ─── UI ──────────────────────────────────────────────────────

@Composable
private fun WidgetContent(d: WidgetData) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            // 全透明：不铺任何底色，直接透出壁纸
            .background(Color.Transparent)
            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)
            .clickable(actionStartActivity(MainActivity::class.java)),
        // 内容垂直居中：桌面给的格子高度略大于内容时，上下留白均分而不是全堆在底部
        verticalAlignment = Alignment.Vertical.CenterVertically
    ) {
        Header()
        Spacer(GlanceModifier.height(6.dp))

        // 今日：支出 / 收入双列，数字做主视觉（字号最大）
        Row(modifier = GlanceModifier.fillMaxWidth()) {
            StatColumn(
                label = "今日支出",
                value = "-¥${widgetAmount(d.todayExp)}",
                valueColor = ExpenseColor,
                amountSp = 15.sp,
                modifier = GlanceModifier.defaultWeight()
            )
            StatColumn(
                label = "今日收入",
                value = "+¥${widgetAmount(d.todayInc)}",
                valueColor = IncomeColor,
                amountSp = 15.sp,
                modifier = GlanceModifier.defaultWeight()
            )
        }

        // 分隔线自带上下留白：留白和线合成 1 个节点，见 Divider 的注释
        Divider(top = 6.dp, bottom = 5.dp)

        // 本月：同结构双列，字号降一档做次级信息
        Row(modifier = GlanceModifier.fillMaxWidth()) {
            StatColumn(
                label = "本月支出",
                value = "-¥${widgetAmount(d.monthExp)}",
                valueColor = ExpenseColor,
                amountSp = 13.sp,
                modifier = GlanceModifier.defaultWeight()
            )
            StatColumn(
                label = "本月收入",
                value = "+¥${widgetAmount(d.monthInc)}",
                valueColor = IncomeColor,
                amountSp = 13.sp,
                modifier = GlanceModifier.defaultWeight()
            )
        }

        Divider(top = 6.dp, bottom = 5.dp)

        // 无条件渲染，无数据时给占位：行数恒定，Widget 高度不跳动
        LatestRow(d.latest)
    }
}

/** 标题行：品牌色装饰竖条 + 名称，右侧为当天日期 */
@Composable
private fun Header() {
    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.Vertical.CenterVertically
    ) {
        Box(
            modifier = GlanceModifier
                .width(3.dp)
                .height(12.dp)
                .background(AccentColor)
                .cornerRadius(2.dp)
        ) {}
        Spacer(GlanceModifier.width(6.dp))
        Text(
            text = "记账助手",
            style = TextStyle(color = TextColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        )
        Spacer(GlanceModifier.defaultWeight())
        Text(
            text = formatNow("M月d日"),
            style = TextStyle(color = TextColor, fontSize = 10.sp)
        )
    }
}

/** 单列统计：上标签、下金额。今日 15sp、本月 13sp，靠字号拉开层级 */
@Composable
private fun StatColumn(
    label: String,
    value: String,
    valueColor: ColorProvider,
    amountSp: TextUnit,
    modifier: GlanceModifier = GlanceModifier
) {
    Column(modifier = modifier) {
        Text(label, style = TextStyle(color = TextColor, fontSize = 9.sp), maxLines = 1)
        Spacer(GlanceModifier.height(1.dp))
        Text(
            text = value,
            style = TextStyle(color = valueColor, fontSize = amountSp, fontWeight = FontWeight.Bold),
            maxLines = 1
        )
    }
}

/**
 * 最近一笔：左「分类 · 商家」+ 时间，右金额。
 *
 * 无数据时给占位文案，而不是让调用方条件渲染——布局行数恒定，Widget 高度不会
 * 因为有无记录而跳动，同时规避上面提到的条件分支丢失问题。
 */
@Composable
private fun LatestRow(tx: Transaction?) {
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
    val color = when (tx?.type) {
        Transaction.Type.INCOME -> IncomeColor
        Transaction.Type.TRANSFER -> AccentColor
        else -> ExpenseColor
    }
    val amount = tx?.let {
        val sign = if (it.type == Transaction.Type.EXPENSE) "-" else "+"
        "$sign¥${widgetAmount(it.amount)}"
    } ?: "—"

    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.Vertical.CenterVertically
    ) {
        Column(modifier = GlanceModifier.defaultWeight().padding(end = 6.dp)) {
            Text(
                text = name,
                style = TextStyle(color = TextColor, fontSize = 11.sp),
                maxLines = 1
            )
            Text(
                text = time,
                style = TextStyle(color = TextColor, fontSize = 9.sp),
                maxLines = 1
            )
        }
        Text(
            text = amount,
            style = TextStyle(color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold),
            maxLines = 1
        )
    }
}

/**
 * 1dp 分隔线，上下留白由参数控制。
 *
 * 留白和线合并进一个 Column 是有意为之：**Glance 的 Row/Column 最多只有 10 个子项
 * 槽位**（底层是静态生成的 RemoteViews 布局），超出的子项会被静默丢弃。之前根
 * Column 有 11 个子项，最后一项「最近一笔」就是这样整行消失的（编译、运行都不报错，
 * 前 10 项正常显示，第 11 项无影无踪）。
 *
 * 因此这里把「上方间距 + 1dp 线 + 下方间距」三个节点压成 1 个，既满足视觉，又把根
 * Column 的直接子项控制在安全范围内（当前 7 个）。后续如果要再加内容，务必留意这个上限，
 * 需要更多区块时用嵌套 Column/Row 分组。
 */
@Composable
private fun Divider(top: Dp = 6.dp, bottom: Dp = 5.dp) {
    Column(modifier = GlanceModifier.fillMaxWidth().padding(top = top, bottom = bottom)) {
        Box(
            modifier = GlanceModifier
                .fillMaxWidth()
                .height(1.dp)
                .background(DividerColor)
        ) {}
    }
}

class BookkeepingWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = BookkeepingWidget()
}
