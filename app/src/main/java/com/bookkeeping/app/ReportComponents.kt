package com.bookkeeping.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bookkeeping.app.data.TransactionDao
import com.bookkeeping.app.data.entity.Transaction
import com.bookkeeping.app.theme.ExpenseRed
import com.bookkeeping.app.theme.IncomeGreen
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// ─── 账单报表组件（时间筛选 + 圆环图 + 趋势图） ─────────────────

internal enum class RangeMode(val label: String) {
    TODAY("今天"), WEEK("本周"),
    MONTH_PICK("月度"), YEAR_PICK("年度"), CUSTOM("自选")
}

private data class ReportData(
    val expense: Double,
    val income: Double,
    val expSlices: List<TransactionDao.CategorySum>,
    val incSlices: List<TransactionDao.CategorySum>
)

private val ReportPalette = listOf(
    Color(0xFF4E79A7), Color(0xFFF28E2B), Color(0xFFE15759), Color(0xFF76B7B2),
    Color(0xFF59A14F), Color(0xFFEDC948), Color(0xFFB07AA1), Color(0xFFFF9DA7),
    Color(0xFF9C755F), Color(0xFFBAB0AC)
)

private fun reportColor(i: Int): Color = ReportPalette[i % ReportPalette.size]

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReportStatsCard(all: List<Transaction>) {
    var mode by remember { mutableStateOf(RangeMode.MONTH_PICK) }
    var pickedAnchor by remember { mutableStateOf(System.currentTimeMillis()) }
    var customStart by remember { mutableStateOf<Long?>(null) }
    var customEnd by remember { mutableStateOf<Long?>(null) }
    var showExpense by remember { mutableStateOf(true) }
    var selectedCat by remember { mutableStateOf<String?>(null) }

    // 日期弹窗：0 无；1 选月度；2 选年度；3 自定义-起；4 自定义-止
    var picker by remember { mutableIntStateOf(0) }

    val range = remember(mode, pickedAnchor, customStart, customEnd) {
        computeRange(mode, pickedAnchor, customStart, customEnd)
    }

    val data = remember(all, range.first, range.second) {
        val inRange = all.filter { it.occurredAt in range.first..range.second }
        ReportData(
            expense = inRange.filter { it.type == Transaction.Type.EXPENSE }.sumOf { it.amount }.round2(),
            income = inRange.filter { it.type == Transaction.Type.INCOME }.sumOf { it.amount }.round2(),
            expSlices = buildSlices(inRange, Transaction.Type.EXPENSE),
            incSlices = buildSlices(inRange, Transaction.Type.INCOME)
        )
    }

    val slices = if (showExpense) data.expSlices else data.incSlices
    val total = if (showExpense) data.expense else data.income
    LaunchedEffect(mode) { selectedCat = null }

    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("账单报表", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))

            // 时间模式 chips（横向可滑动）
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(7.dp) // 间距
            ) {
                RangeMode.entries.forEach { m ->
                    FilterChip(
                        selected = mode == m,
                        onClick = { mode = m },
                        label = { Text(m.label, fontSize = 12.sp) }
                    )
                }
            }

            // 月度/年度/自选 的具体选择行
            if (mode != RangeMode.TODAY && mode != RangeMode.WEEK) {
                Spacer(Modifier.height(8.dp))
                val subText = when (mode) {
                    RangeMode.MONTH_PICK ->
                        "📅 " + SimpleDateFormat("yyyy年M月", Locale.getDefault()).format(Date(pickedAnchor))
                    RangeMode.YEAR_PICK ->
                        "📅 " + SimpleDateFormat("yyyy年", Locale.getDefault()).format(Date(pickedAnchor))
                    else -> {
                        val s = customStart; val e = customEnd
                        if (s != null && e != null)
                            "📅 " + SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(s)) +
                            " ~ " + SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date(e))
                        else "📅 点击选择起止日期"
                    }
                }
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().clickable {
                        picker = when (mode) {
                            RangeMode.MONTH_PICK -> 1
                            RangeMode.YEAR_PICK -> 2
                            else -> 3
                        }
                    }
                ) {
                    Text(subText, fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                }
            }

            // 支出 / 收入 切换
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                MiniSegmented("支出", showExpense) { showExpense = true }
                Spacer(Modifier.width(12.dp))
                MiniSegmented("收入", !showExpense) { showExpense = false }
            }

            Spacer(Modifier.height(10.dp))
            if (total <= 0) {
                Box(Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
                    Text("该时段暂无${if (showExpense) "支出" else "收入"}记录",
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                // 圆环图 + 中心金额
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    DonutChart(slices = slices, modifier = Modifier.size(180.dp))
                    val sel = selectedCat?.let { c -> slices.firstOrNull { it.category == c } }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (showExpense) "支出" else "收入",
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("¥${(sel?.total ?: total).formatAmount()}",
                            fontSize = 19.sp, fontWeight = FontWeight.Bold,
                            color = if (showExpense) ExpenseRed else IncomeGreen)
                        if (sel != null) {
                            Text("${(sel.total / total * 100).toInt()}% · 再点取消",
                                fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                // 分类图例
                Column {
                    slices.forEachIndexed { i, s ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable {
                                    selectedCat = if (selectedCat == s.category) null else s.category
                                }
                                .padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(Modifier.size(10.dp).background(reportColor(i), CircleShape))
                            Spacer(Modifier.width(8.dp))
                            Text(categoryEmoji(s.category), fontSize = 13.sp)
                            Spacer(Modifier.width(4.dp))
                            Text(s.category, fontSize = 12.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                fontWeight = if (selectedCat == s.category) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier.weight(1f))
                            Text("¥${s.total.formatAmount()}", fontSize = 12.sp)
                            Spacer(Modifier.width(8.dp))
                            Text("${(s.total / total * 100).toInt()}%",
                                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(36.dp), textAlign = TextAlign.End)
                        }
                    }
                }
            }

            // 汇总：支出 / 收入 / 结余
            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth()) {
                ReportSummary("支出", data.expense, ExpenseRed)
                ReportSummary("收入", data.income, IncomeGreen)
                ReportSummary("结余", data.income - data.expense, Color(0xFF2E5AAC))
            }

            // 近 6 个月收支趋势
            Spacer(Modifier.height(12.dp))
            SixMonthTrend(all)
        }
    }

    // 日期选择弹窗
    if (picker in 1..4) {
        val initial = when (picker) {
            1 -> pickedAnchor
            2 -> pickedAnchor
            3 -> customStart ?: System.currentTimeMillis()
            else -> customEnd?.let { it - 86_399_999 } ?: System.currentTimeMillis()
        }
        ReportDatePicker(
            initial = initial,
            title = when (picker) {
                1 -> "选择月份（任意日期即可）"
                2 -> "选择年份（任意日期即可）"
                3 -> "选择开始日期"
                else -> "选择结束日期"
            },
            onDismiss = { picker = 0 },
            onConfirm = { dayStart ->
                when (picker) {
                    1, 2 -> { pickedAnchor = dayStart; picker = 0 }
                    3 -> { customStart = dayStart; picker = 4 }
                    else -> {
                        var s = customStart
                        if (s != null && dayStart < s) s = dayStart
                        customStart = s
                        customEnd = dayStart + 86_399_999
                        picker = 0
                    }
                }
            }
        )
    }
}

@Composable
internal fun androidx.compose.foundation.layout.RowScope.ReportSummary(
    label: String, value: Double, color: Color
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.weight(1f)
    ) {
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("¥${value.formatAmount()}", fontSize = 14.sp,
            fontWeight = FontWeight.Bold, color = color)
    }
}

/** 近 6 个月收支趋势：每月支出/收入双柱迷你图 */
@Composable
private fun SixMonthTrend(all: List<Transaction>) {
    val months = remember(all) {
        val cal = Calendar.getInstance()
        (5 downTo 0).map { back ->
            val c = (cal.clone() as Calendar).apply {
                set(Calendar.DAY_OF_MONTH, 1)
                add(Calendar.MONTH, -back)
            }
            val start = c.timeInMillis
            c.add(Calendar.MONTH, 1)
            val end = c.timeInMillis - 1
            val inM = all.filter { it.occurredAt in start..end && it.confirmed }
            Triple(
                SimpleDateFormat("M月", Locale.getDefault()).format(Date(start)),
                inM.filter { it.type == Transaction.Type.EXPENSE }.sumOf { it.amount },
                inM.filter { it.type == Transaction.Type.INCOME }.sumOf { it.amount }
            )
        }
    }
    val maxV = months.maxOf { maxOf(it.second, it.third) }.coerceAtLeast(0.01)
    val currentLabel = SimpleDateFormat("M月", Locale.getDefault()).format(Date())

    Column {
        HorizontalDivider()
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("近 6 个月趋势", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(8.dp).background(ExpenseRed, CircleShape))
            Spacer(Modifier.width(4.dp))
            Text("支出", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(10.dp))
            Box(Modifier.size(8.dp).background(IncomeGreen, CircleShape))
            Spacer(Modifier.width(4.dp))
            Text("收入", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
        Canvas(Modifier.fillMaxWidth().height(96.dp)) {
            val groupW = size.width / months.size
            months.forEachIndexed { i, (_, exp, inc) ->
                val expH = (exp / maxV * size.height).toFloat().coerceAtLeast(if (exp > 0) 4f else 0f)
                val incH = (inc / maxV * size.height).toFloat().coerceAtLeast(if (inc > 0) 4f else 0f)
                val barW = groupW * 0.24f
                val x0 = groupW * i + groupW * 0.18f
                drawRoundRect(
                    color = ExpenseRed,
                    topLeft = androidx.compose.ui.geometry.Offset(x0, size.height - expH),
                    size = androidx.compose.ui.geometry.Size(barW, expH),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(barW / 3f)
                )
                drawRoundRect(
                    color = IncomeGreen,
                    topLeft = androidx.compose.ui.geometry.Offset(x0 + barW + groupW * 0.08f, size.height - incH),
                    size = androidx.compose.ui.geometry.Size(barW, incH),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(barW / 3f)
                )
            }
        }
        Row(Modifier.fillMaxWidth()) {
            months.forEach { (label, _, _) ->
                Text(
                    label,
                    fontSize = 10.sp,
                    textAlign = TextAlign.Center,
                    fontWeight = if (label == currentLabel) FontWeight.Bold else FontWeight.Normal,
                    color = if (label == currentLabel) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun MiniSegmented(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) Color(0xFF2E5AAC) else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 6.dp)
    ) {
        Text(label, fontSize = 13.sp,
            color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 圆环图：各分类按占比画弧，弧间留 3° 间隙 */
@Composable
private fun DonutChart(slices: List<TransactionDao.CategorySum>, modifier: Modifier = Modifier) {
    val total = slices.sumOf { it.total }.coerceAtLeast(0.01)
    Canvas(modifier) {
        val stroke = 34.dp.toPx()
        val gap = 3f
        val inset = stroke / 2f
        val arcSize = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke)
        var start = -90f
        slices.forEachIndexed { i, s ->
            val sweep = (s.total / total * 360f).toFloat() - gap
            drawArc(
                color = reportColor(i),
                startAngle = start,
                sweepAngle = sweep.coerceAtLeast(1f),
                useCenter = false,
                topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                size = arcSize,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke)
            )
            start += sweep + gap
        }
    }
}

/** 分类切片：Top8 之外合并为「其他」 */
private fun buildSlices(
    inRange: List<Transaction>,
    type: Transaction.Type
): List<TransactionDao.CategorySum> {
    val grouped = inRange.filter { it.type == type }
        .groupBy { it.category }
        .map { TransactionDao.CategorySum(it.key, it.value.sumOf { v -> v.amount }.round2()) }
        .sortedByDescending { it.total }
    if (grouped.size <= 9) return grouped
    val rest = grouped.drop(8).sumOf { it.total }
    return grouped.take(8) + TransactionDao.CategorySum("其他", rest)
}

/** 按模式计算 [开始, 结束] 毫秒区间（本地时间） */
internal fun computeRange(
    mode: RangeMode,
    anchor: Long,
    customStart: Long?,
    customEnd: Long?
): Pair<Long, Long> {
    val cal = Calendar.getInstance()
    fun floorDay(): Long {
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
    return when (mode) {
        RangeMode.TODAY -> {
            cal.timeInMillis = System.currentTimeMillis()
            val s = floorDay()
            s to s + 86_399_999
        }
        RangeMode.WEEK -> {
            cal.timeInMillis = System.currentTimeMillis()
            cal.firstDayOfWeek = Calendar.MONDAY
            cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            val s = floorDay()
            s to s + 7 * 86_400_000L - 1
        }
        RangeMode.MONTH_PICK -> {
            cal.timeInMillis = anchor
            cal.set(Calendar.DAY_OF_MONTH, 1)
            val s = floorDay()
            cal.add(Calendar.MONTH, 1)
            s to cal.timeInMillis - 1
        }
        RangeMode.YEAR_PICK -> {
            cal.timeInMillis = anchor
            cal.set(Calendar.MONTH, Calendar.JANUARY)
            cal.set(Calendar.DAY_OF_MONTH, 1)
            val s = floorDay()
            cal.add(Calendar.YEAR, 1)
            s to cal.timeInMillis - 1
        }
        RangeMode.CUSTOM -> {
            if (customStart != null && customEnd != null) customStart to customEnd
            else {
                cal.timeInMillis = System.currentTimeMillis()
                val s = floorDay()
                s to s + 86_399_999
            }
        }
    }
}

/** Material3 日期选择弹窗，确认时回传所选日期的本地零点 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReportDatePicker(
    initial: Long,
    title: String,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit
) {
    val state = rememberDatePickerState(initialSelectedDateMillis = localDayToUtc(initial))
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { onConfirm(utcToLocalDayStart(it)) }
            }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    ) {
        DatePicker(
            state = state,
            title = { Text(title, Modifier.padding(start = 24.dp, top = 16.dp)) }
        )
    }
}
