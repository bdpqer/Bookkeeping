package com.bookkeeping.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import com.bookkeeping.app.data.AppDatabase
import com.bookkeeping.app.data.entity.Ledger
import com.bookkeeping.app.data.entity.Transaction
import com.bookkeeping.app.service.CaptureLogBus
import com.bookkeeping.app.theme.ExpenseRed
import com.bookkeeping.app.theme.IncomeGreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ─── 待确认队列 ─────────────────────────────────────────────

@Composable
internal fun PendingScreen(onResolved: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val db = remember { AppDatabase.getInstance(context) }
    var pending by remember { mutableStateOf<List<Transaction>>(emptyList()) }
    var editingTx by remember { mutableStateOf<Transaction?>(null) }

    fun refresh() {
        scope.launch {
            pending = withContext(Dispatchers.IO) { db.transactionDao().getPending() }
            onResolved()
        }
    }

    LaunchedEffect(Unit) { refresh() }
    DisposableEffect(Unit) {
        val unsub = CaptureLogBus.subscribe { refresh() }
        onDispose { unsub() }
    }

    Column(Modifier.fillMaxSize()) {
        // 一键全部确认
        if (pending.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = {
                    scope.launch {
                    db.transactionDao().confirmAll()
                    refresh()
                }
                }) { Text("✓ 全部确认") }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (pending.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("🎉", fontSize = 48.sp)
                            Text("没有待确认的交易", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            } else {
                items(pending) { tx ->
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { editingTx = tx },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        )
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(categoryEmoji(tx.category), fontSize = 20.sp)
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        tx.category,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                                Text(
                                    "${if (tx.type == Transaction.Type.EXPENSE) "-" else "+"}¥${tx.amount.formatAmount()}",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = when (tx.type) {
                                        Transaction.Type.EXPENSE -> ExpenseRed
                                        Transaction.Type.INCOME -> IncomeGreen
                                        Transaction.Type.TRANSFER -> Color(0xFFFF9800)
                                    }
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                tx.rawText.take(100),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2
                            )
                            Row(
                                Modifier.fillMaxWidth().padding(top = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                AssistChip(
                                    onClick = {
                                        scope.launch {
                                            db.transactionDao().confirm(tx.id)
                                            refresh()
                                        }
                                    },
                                    label = { Text("✓ 确认入账") }
                                )
                                AssistChip(
                                    onClick = { editingTx = tx },
                                    label = { Text("✎ 编辑后确认") }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (editingTx != null) {
        TransactionEditDialog(
            tx = editingTx!!.copy(confirmed = true),
            onDismiss = { editingTx = null },
            onSaved = { editingTx = null; refresh() },
            onDeleted = { editingTx = null; refresh() }
        )
    }
}

// ─── 交易列表 ─────────────────────────────────────────────

@Composable
internal fun TransactionListScreen() {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val db = remember { AppDatabase.getInstance(context) }
    var editingTx by remember { mutableStateOf<Transaction?>(null) }
    var keyword by remember { mutableStateOf("") }
    var ledgers by remember { mutableStateOf<List<Ledger>>(emptyList()) }
    var selectedLedgerId by remember { mutableStateOf<Long?>(null) }
    // 时间筛选：null = 全部时间；默认显示当月
    var timeMode by remember { mutableStateOf<RangeMode?>(RangeMode.MONTH_PICK) }
    var monthAnchor by remember { mutableStateOf(System.currentTimeMillis()) }
    var showMonthPicker by remember { mutableStateOf(false) }
    // 自定义日期范围
    var customStart by remember { mutableStateOf<Long?>(null) }
    var customEnd by remember { mutableStateOf<Long?>(null) }
    var customPicker by remember { mutableIntStateOf(0) } // 0 无；1 选开始；2 选结束

    val timeRange = remember(timeMode, monthAnchor, customStart, customEnd) {
        timeMode?.let { computeRange(it, monthAnchor, customStart, customEnd) }
    }

    // 交易数据改为 Flow 订阅：数据库变化自动刷新（入库/编辑/删除均无需手动 refresh）
    val baseList by remember(selectedLedgerId, keyword) {
        val kw = keyword.trim()
        when {
            selectedLedgerId != null && kw.isNotBlank() ->
                db.transactionDao().observeSearchByLedger(selectedLedgerId!!, kw)
            selectedLedgerId != null ->
                db.transactionDao().observeByLedger(selectedLedgerId!!)
            kw.isBlank() -> db.transactionDao().observeAll()
            else -> db.transactionDao().observeSearch(kw)
        }
    }.collectAsState(initial = emptyList<Transaction>())
    val transactions =
        if (timeRange != null) baseList.filter { it.occurredAt in timeRange.first..timeRange.second }
        else baseList

    LaunchedEffect(Unit) {
        ledgers = withContext(Dispatchers.IO) { db.ledgerDao().getAll() }
    }

    Column(Modifier.fillMaxSize()) {
        // 当前筛选结果的汇总：支出 / 收入 / 结余
        val sumExpense = transactions.filter { it.type == Transaction.Type.EXPENSE }.sumOf { it.amount }.round2()
        val sumIncome = transactions.filter { it.type == Transaction.Type.INCOME }.sumOf { it.amount }.round2()
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
        ) {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                ReportSummary("支出", sumExpense, ExpenseRed)
                ReportSummary("收入", sumIncome, IncomeGreen)
                ReportSummary("结余", sumIncome - sumExpense, Color(0xFF2E5AAC))
            }
        }

        // 标题 + 账本下拉筛选
        var ledgerMenu by remember { mutableStateOf(false) }
        val ledgerName =
            if (selectedLedgerId == null) "全部账本"
            else ledgers.firstOrNull { it.id == selectedLedgerId }?.name ?: "全部账本"
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(Modifier.width(6.dp))
            Box {
                Row(
                    Modifier.clickable { ledgerMenu = true },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(ledgerName, fontSize = 13.sp, color = Color(0xFF2E5AAC))
                    Text(" ▾", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(expanded = ledgerMenu, onDismissRequest = { ledgerMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("📚 全部账本") },
                        onClick = { selectedLedgerId = null; ledgerMenu = false }
                    )
                    ledgers.forEach { led ->
                        DropdownMenuItem(
                            text = { Text("${led.icon} ${led.name}") },
                            onClick = { selectedLedgerId = led.id; ledgerMenu = false }
                        )
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            // 时间下拉筛选
            Box {
                var timeMenu by remember { mutableStateOf(false) }
                val timeLabel = when (timeMode) {
                    null -> "全部时间"
                    RangeMode.TODAY -> "今天"
                    RangeMode.WEEK -> "本周"
                    RangeMode.MONTH_PICK ->
                        "📅 " + SimpleDateFormat("yyyy年M月", Locale.getDefault()).format(Date(monthAnchor))
                    RangeMode.YEAR_PICK ->
                        "📅 " + SimpleDateFormat("yyyy年", Locale.getDefault()).format(Date(monthAnchor))
                    RangeMode.CUSTOM -> {
                        val s = customStart; val e = customEnd
                        if (s != null && e != null)
                            "📅 " + SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date(s)) +
                                " ~ " + SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date(e))
                        else "📅 自定义"
                    }
                }
                Row(
                    Modifier.clickable { timeMenu = true },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(timeLabel, fontSize = 13.sp, color = Color(0xFF2E5AAC))
                    Text(" ▾", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(expanded = timeMenu, onDismissRequest = { timeMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("今天") },
                        onClick = { timeMode = RangeMode.TODAY; timeMenu = false }
                    )
                    DropdownMenuItem(
                        text = { Text("本周") },
                        onClick = { timeMode = RangeMode.WEEK; timeMenu = false }
                    )
                    DropdownMenuItem(
                        text = { Text("本月") },
                        onClick = {
                            timeMode = RangeMode.MONTH_PICK
                            monthAnchor = System.currentTimeMillis()
                            timeMenu = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("选择月份…") },
                        onClick = { timeMode = RangeMode.MONTH_PICK; timeMenu = false; showMonthPicker = true }
                    )
                    DropdownMenuItem(
                        text = { Text("今年") },
                        onClick = {
                            timeMode = RangeMode.YEAR_PICK
                            monthAnchor = System.currentTimeMillis()
                            timeMenu = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("自定义日期范围") },
                        onClick = { timeMode = RangeMode.CUSTOM; timeMenu = false; customPicker = 1 }
                    )
                    DropdownMenuItem(
                        text = { Text("全部时间") },
                        onClick = { timeMode = null; timeMenu = false }
                    )
                }
            }
        }

        // 搜索框
        OutlinedTextField(
            value = keyword,
            onValueChange = { keyword = it },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            label = { Text("搜索商户/备注/关键词") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp)
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            if (transactions.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                        Text(
                            when {
                                keyword.isNotBlank() -> "没找到匹配的交易"
                                timeRange != null -> "该时段暂无交易记录"
                                else -> "暂无交易记录"
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                items(transactions) { tx ->
                    TransactionItem(
                        tx = tx,
                        onClick = { editingTx = tx }
                    )
                }
            }
        }
    }

    if (editingTx != null) {
        TransactionEditDialog(
            tx = editingTx!!,
            onDismiss = { editingTx = null },
            onSaved = { editingTx = null },
            onDeleted = { editingTx = null }
        )
    }

    if (showMonthPicker) {
        ReportDatePicker(
            initial = monthAnchor,
            title = "选择月份（任意日期即可）",
            onDismiss = { showMonthPicker = false },
            onConfirm = { dayStart ->
                monthAnchor = dayStart
                showMonthPicker = false
            }
        )
    }

    // 自定义日期范围：先选开始，再选结束
    if (customPicker in 1..2) {
        ReportDatePicker(
            initial = if (customPicker == 1) customStart ?: System.currentTimeMillis()
                      else customEnd?.let { it - 86_399_999 } ?: System.currentTimeMillis(),
            title = if (customPicker == 1) "选择开始日期" else "选择结束日期",
            onDismiss = { customPicker = 0 },
            onConfirm = { dayStart ->
                if (customPicker == 1) {
                    customStart = dayStart
                    customPicker = 2
                } else {
                    var s = customStart
                    if (s != null && dayStart < s) s = dayStart
                    customStart = s
                    customEnd = dayStart + 86_399_999
                    customPicker = 0
                }
            }
        )
    }
}
