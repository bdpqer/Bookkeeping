package com.bookkeeping.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bookkeeping.app.data.AppDatabase
import com.bookkeeping.app.data.entity.Transaction
import com.bookkeeping.app.service.CaptureLogBus
import com.bookkeeping.app.theme.ExpenseRed
import com.bookkeeping.app.theme.IncomeGreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

// ─── 记账日历（月历视图） ─────────────────────────────────

@Composable
internal fun CalendarScreen() {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val db = remember { AppDatabase.getInstance(context) }

    val cal = Calendar.getInstance()
    var viewYearMonth by remember { mutableStateOf(cal.clone() as Calendar) }
    var daySums by remember { mutableStateOf<Map<Long, Pair<Double, Double>>>(emptyMap()) } // dayKey → (expense, income)
    var selectedDayKey by remember { mutableStateOf<Long?>(null) }
    var dayTransactions by remember { mutableStateOf<List<Transaction>>(emptyList()) }

    // 计算当月所有天的收支
    fun loadMonthData() {
        scope.launch {
            val y = viewYearMonth.get(Calendar.YEAR)
            val m = viewYearMonth.get(Calendar.MONTH)
            val firstDay = Calendar.getInstance().apply { set(y, m, 1, 0, 0, 0); set(Calendar.MILLISECOND, 0) }
            val lastDay = Calendar.getInstance().apply { set(y, m, firstDay.getActualMaximum(Calendar.DAY_OF_MONTH), 23, 59, 59) }

            val sums = withContext(Dispatchers.IO) {
                val result = mutableMapOf<Long, Pair<Double, Double>>()
                val all = db.transactionDao().getByTimeRange(firstDay.timeInMillis, lastDay.timeInMillis)
                all.forEach { tx ->
                    val dayKey = tx.occurredAt / 86_400_000L
                    val (exp, inc) = result[dayKey] ?: 0.0 to 0.0
                    result[dayKey] = when (tx.type) {
                        Transaction.Type.EXPENSE -> exp + tx.amount to inc
                        Transaction.Type.INCOME -> exp to inc + tx.amount
                        else -> exp to inc
                    }
                }
                result
            }
            daySums = sums
        }
    }

    fun loadSelectedDay(key: Long) {
        scope.launch {
            selectedDayKey = key
            val start = key * 86_400_000L
            val end = start + 86_400_000L - 1
            dayTransactions = withContext(Dispatchers.IO) {
                db.transactionDao().getByTimeRange(start, end)
            }
        }
    }

    LaunchedEffect(viewYearMonth) { loadMonthData() }
    DisposableEffect(Unit) {
        val unsub = CaptureLogBus.subscribe { loadMonthData() }
        onDispose { unsub() }
    }

    Column(Modifier.fillMaxSize()) {
        // 月份切换 + 标题
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = {
                viewYearMonth = (viewYearMonth.clone() as Calendar).apply { add(Calendar.MONTH, -1) }
            }) { Icon(Icons.Default.ArrowBack, null) }
            val sdf = java.text.SimpleDateFormat("yyyy年 M月", java.util.Locale.CHINA)
            Text(sdf.format(viewYearMonth.time), fontSize = 18.sp, fontWeight = FontWeight.Bold)
            IconButton(onClick = {
                viewYearMonth = (viewYearMonth.clone() as Calendar).apply { add(Calendar.MONTH, 1) }
            }) { Icon(Icons.Default.ArrowForward, null) }
        }

        // 周标题行
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            listOf("日", "一", "二", "三", "四", "五", "六").forEach { w ->
                Text(w, modifier = Modifier.weight(1f), fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center)
            }
        }

        Spacer(Modifier.height(4.dp))

        // 月历网格
        val daysInMonth = viewYearMonth.getActualMaximum(Calendar.DAY_OF_MONTH)
        val firstDayOfWeek = (viewYearMonth.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY) // 0..6
        val totalCells = firstDayOfWeek + daysInMonth
        val gridCells = (0 until totalCells).map { idx ->
            val dayNum = if (idx < firstDayOfWeek) null else idx - firstDayOfWeek + 1
            dayNum
        }

        val today = Calendar.getInstance()
        val todayKey = today.timeInMillis / 86_400_000L

        LazyVerticalGrid(
            columns = GridCells.Fixed(7),
            modifier = Modifier.fillMaxWidth().height(280.dp).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            items(gridCells) { dayNum ->
                if (dayNum == null) {
                    Box(Modifier.aspectRatio(1f))
                } else {
                    val dayCal = (viewYearMonth.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, dayNum) }
                    val dayKey = dayCal.timeInMillis / 86_400_000L
                    val sums = daySums[dayKey]
                    val isToday = dayKey == todayKey
                    val isSelected = dayKey == selectedDayKey

                    Box(
                        modifier = Modifier
                            .aspectRatio(1f)
                            .background(
                                when {
                                    isSelected -> MaterialTheme.colorScheme.primaryContainer
                                    isToday -> MaterialTheme.colorScheme.surfaceVariant
                                    else -> Color.Transparent
                                },
                                RoundedCornerShape(8.dp)
                            )
                            .clickable { loadSelectedDay(dayKey) },
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                dayNum.toString(),
                                fontSize = 14.sp,
                                fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                                color = when {
                                    isSelected -> MaterialTheme.colorScheme.onPrimaryContainer
                                    isToday -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.onSurface
                                }
                            )
                            // 收支小点（两列显示）
                            if (sums != null && (sums.first > 0.01 || sums.second > 0.01)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                    if (sums.first > 0.01) Box(Modifier.size(5.dp).background(ExpenseRed, CircleShape))
                                    if (sums.second > 0.01) Box(Modifier.size(5.dp).background(IncomeGreen, CircleShape))
                                }
                            } else if (sums != null) {
                                // 只有极小金额也显示一个小点
                                Box(Modifier.size(4.dp).background(
                                    MaterialTheme.colorScheme.onSurfaceVariant, CircleShape))
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // 图例
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).background(ExpenseRed, CircleShape))
                Spacer(Modifier.width(4.dp))
                Text("支出", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(12.dp))
                Box(Modifier.size(6.dp).background(IncomeGreen, CircleShape))
                Spacer(Modifier.width(4.dp))
                Text("收入", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Spacer(Modifier.height(8.dp))
        HorizontalDivider()

        // 选中日期详情
        val selKey = selectedDayKey
        if (selKey != null) {
            val selCal = Calendar.getInstance().apply { timeInMillis = selKey * 86_400_000L }
            val selSums = daySums[selKey] ?: (0.0 to 0.0)
            val selExp = selSums.first
            val selInc = selSums.second

            Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "${selCal.get(Calendar.YEAR)}年${selCal.get(Calendar.MONTH) + 1}月${selCal.get(Calendar.DAY_OF_MONTH)}日",
                        fontSize = 15.sp, fontWeight = FontWeight.Medium
                    )
                    Text(
                        buildString {
                            if (selExp > 0.01) append("-¥${selExp.formatAmount()}")
                            if (selInc > 0.01) append(" +¥${selInc.formatAmount()}")
                            if (selExp < 0.01 && selInc < 0.01) append("无交易")
                        },
                        fontSize = 14.sp, fontWeight = FontWeight.Medium,
                        color = when {
                            selExp > 0.01 && selInc > 0.01 -> MaterialTheme.colorScheme.onSurface
                            selExp > 0.01 -> ExpenseRed
                            selInc > 0.01 -> IncomeGreen
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }

                if (dayTransactions.isEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                        Text("这一天没有交易", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        items(dayTransactions) { tx ->
                            TransactionItem(tx = tx)
                        }
                    }
                }
            }
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("点选日期查看当日明细", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
