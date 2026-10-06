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
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.bookkeeping.app.theme.ExpenseRed
import com.bookkeeping.app.theme.IncomeGreen
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Calendar

// ─── 记账日历（月历视图） ─────────────────────────────────

/** 本地时区下 timestamp 对应的 epoch day（避免 UTC 切天把 00:00–08:00 算进前一天） */
private fun localDayKeyOf(ts: Long): Long =
    Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()

/** 本地 epoch day 对应当地 00:00:00 的 UTC timestamp */
private fun localStartOfDayTs(dayEpoch: Long): Long =
    LocalDate.ofEpochDay(dayEpoch).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

/** 本地 epoch day 的次日 00:00:00 减 1 ms，作为当日 [start, end] 闭区间右端 */
private fun localEndOfDayTs(dayEpoch: Long): Long =
    LocalDate.ofEpochDay(dayEpoch).plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() - 1

@Composable
internal fun CalendarScreen() {
    val context = LocalContext.current
    val db = remember { AppDatabase.getInstance(context) }

    val cal = Calendar.getInstance()
    var viewYearMonth by remember { mutableStateOf(cal.clone() as Calendar) }
    var selectedDayKey by remember { mutableStateOf<Long?>(null) }
    // 当月交易明细（已确认未删除）。Flow 订阅：新增/确认/删除自动刷新。
    // 将来接入账本筛选时，改为 remember(ledgerId) { dao.observeByLedger(ledgerId) } 即可。
    val monthTxs by remember { db.transactionDao().observeAll() }.collectAsStateWithLifecycle(initialValue = emptyList())
    // 当月按天汇总：依赖 monthTxs + viewYearMonth，remember 避免每次重组重算
    val daySums = remember(monthTxs, viewYearMonth) {
        val y = viewYearMonth.get(Calendar.YEAR)
        val m = viewYearMonth.get(Calendar.MONTH)
        val firstDay = Calendar.getInstance().apply { set(y, m, 1, 0, 0, 0); set(Calendar.MILLISECOND, 0) }
        val lastDay = Calendar.getInstance().apply { set(y, m, firstDay.getActualMaximum(Calendar.DAY_OF_MONTH), 23, 59, 59) }
        val start = firstDay.timeInMillis
        val end = lastDay.timeInMillis
        val result = mutableMapOf<Long, Pair<Double, Double>>()
        for (tx in monthTxs) {
            if (tx.occurredAt !in start..end) continue
            val dayKey = localDayKeyOf(tx.occurredAt)
            val (exp, inc) = result[dayKey] ?: 0.0 to 0.0
            result[dayKey] = when (tx.type) {
                Transaction.Type.EXPENSE -> exp + tx.amount to inc
                Transaction.Type.INCOME -> exp to inc + tx.amount
                else -> exp to inc
            }
        }
        result
    }
    // 选中日期的交易列表（来自 monthTxs 当天过滤）
    val dayTransactions = remember(selectedDayKey, monthTxs) {
        if (selectedDayKey == null) emptyList()
        else {
            val start = localStartOfDayTs(selectedDayKey!!)
            val end = localEndOfDayTs(selectedDayKey!!)
            monthTxs.filter { it.occurredAt in start..end }
        }
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
            }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
            Text(
                formatTime(viewYearMonth.timeInMillis, "yyyy年 M月"),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            IconButton(onClick = {
                viewYearMonth = (viewYearMonth.clone() as Calendar).apply { add(Calendar.MONTH, 1) }
            }) { Icon(Icons.AutoMirrored.Filled.ArrowForward, null) }
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
        // ⚠️ viewYearMonth 必须先把「日」置为 1 才能取 DAY_OF_WEEK：它默认是「今天」的克隆，
        // 停在今天的日号上。早先直接取星期，拿到的是「今天几号」的星期
        // （今天 10/6 是周二，而 10/1 实际是周四）→ 整月日期横向平移
        val firstCal = (viewYearMonth.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, 1) }
        val daysInMonth = viewYearMonth.getActualMaximum(Calendar.DAY_OF_MONTH)
        val firstDayOfWeek = (firstCal.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY) // 0..6
        // 网格只随月份变化，缓存住避免每次重组重建 31+ 个元素的可变列表
        val gridCells = remember(viewYearMonth) {
            val totalCells = firstDayOfWeek + daysInMonth
            (0 until totalCells).map { idx ->
                if (idx < firstDayOfWeek) null else idx - firstDayOfWeek + 1
            }
        }

        // 每次重组重算（计算极便宜），跨零点后"今天"高亮才准确
        val todayKey = localDayKeyOf(Calendar.getInstance().timeInMillis)

        LazyVerticalGrid(
            columns = GridCells.Fixed(7),
            modifier = Modifier.fillMaxWidth().height(280.dp).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            itemsIndexed(
                gridCells,
                // 月初补齐的空白占位有多格，不能共用同一个 key，
                // 否则 LazyGrid 抛 "Key ... was already used"（月份 1 号不是周日时必崩）
                key = { idx, day -> if (day == null) Long.MIN_VALUE + idx else day.toLong() }
            ) { _, dayNum ->
                if (dayNum == null) {
                    Box(Modifier.aspectRatio(1f))
                } else {
                    val dayCal = (viewYearMonth.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, dayNum) }
                    val dayKey = LocalDate.of(
                        dayCal.get(Calendar.YEAR),
                        dayCal.get(Calendar.MONTH) + 1,
                        dayNum
                    ).toEpochDay()
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
                            .clickable { selectedDayKey = dayKey },
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
            val selDate = LocalDate.ofEpochDay(selKey)
            val selSums = daySums[selKey] ?: (0.0 to 0.0)
            val selExp = selSums.first
            val selInc = selSums.second

            Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "${selDate.year}年${selDate.monthValue}月${selDate.dayOfMonth}日",
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
                        items(dayTransactions, key = { it.id }) { tx ->
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
