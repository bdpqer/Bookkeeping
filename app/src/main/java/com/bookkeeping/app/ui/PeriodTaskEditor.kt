package com.bookkeeping.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.AddCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bookkeeping.app.EmptyState
import com.bookkeeping.app.localDayToUtc
import com.bookkeeping.app.utcToLocalDayStart
import com.bookkeeping.app.data.AppDatabase
import com.bookkeeping.app.data.entity.Account
import com.bookkeeping.app.data.entity.InstallmentPlan
import com.bookkeeping.app.data.entity.RecurringItem
import com.bookkeeping.app.data.entity.installmentDueDate
import com.bookkeeping.app.embeddedImePadding
import com.bookkeeping.app.data.entity.installmentPeriodAmounts
import com.bookkeeping.app.worker.RecurringWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import com.bookkeeping.app.formatAmount


// ─── 周期任务编辑器 ───────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PeriodTaskEditor(
    item: RecurringItem?,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.getInstance(context) }
    val editing = item != null
    var name by remember { mutableStateOf(item?.name ?: "") }
    var mode by remember { mutableStateOf(item?.mode ?: RecurringItem.Mode.REMIND) }
    var period by remember { mutableStateOf(item?.period ?: RecurringItem.Period.MONTHLY) }
    var dayOfMonth by remember {
        mutableStateOf(item?.dayOfMonth?.toString() ?: (Calendar.getInstance().get(Calendar.DAY_OF_MONTH)).toString())
    }
    var dayOfWeek by remember { mutableStateOf(item?.dayOfWeek ?: 1) }
    var firstDate by remember { mutableStateOf(item?.startDate ?: System.currentTimeMillis()) }
    var amountText by remember { mutableStateOf(item?.amount?.toString() ?: "") }
    var txType by remember { mutableStateOf(item?.txType ?: RecurringItem.TxType.EXPENSE) }
    var category by remember { mutableStateOf(item?.category ?: "其他") }
    var note by remember { mutableStateOf(item?.note ?: "") }
    var endMode by remember { mutableStateOf(item?.endMode ?: RecurringItem.EndMode.NEVER) }
    var endCountText by remember { mutableStateOf(item?.endAfterCount?.toString() ?: "12") }
    var endDate by remember { mutableStateOf(item?.endDate ?: System.currentTimeMillis()) }
    var detailExpanded by remember { mutableStateOf(editing) }
    var showFirstDatePicker by remember { mutableStateOf(false) }
    var showEndDatePicker by remember { mutableStateOf(false) }
    var catExpanded by remember { mutableStateOf(false) }
    var showEntryCapture by remember { mutableStateOf(false) }

    val cats = if (txType == RecurringItem.TxType.EXPENSE)
        listOf("餐饮/外卖", "餐饮/饮品", "交通", "购物", "居住", "娱乐", "医疗", "人情", "红包", "借出", "理财", "其他")
    else
        listOf("工资", "红包", "人情", "还款", "报销", "理财", "其他")

    fun doSave() {
        if (name.isBlank()) {
            android.widget.Toast.makeText(context, "请先设置明细名称", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val amt = amountText.toDoubleOrNull()
        if (mode == RecurringItem.Mode.AUTO_TX && (amt == null || amt <= 0)) {
            android.widget.Toast.makeText(context, "请输入有效金额", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val dom = dayOfMonth.toIntOrNull()?.coerceIn(1, 31)
        // 首笔日期：当天 9 点
        val cal = Calendar.getInstance().apply {
            timeInMillis = firstDate
            set(Calendar.HOUR_OF_DAY, 9); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val isoWeekday = ((cal.get(Calendar.DAY_OF_WEEK) + 5) % 7) + 1
        val base = (item ?: RecurringItem(
            name = "", mode = mode, period = period,
            startDate = cal.timeInMillis, nextRunAt = cal.timeInMillis
        )).copy(
            name = name.trim(),
            mode = mode,
            period = period,
            dayOfMonth = if (period == RecurringItem.Period.MONTHLY || period == RecurringItem.Period.YEARLY) dom else null,
            dayOfWeek = if (period == RecurringItem.Period.WEEKLY) isoWeekday else null,
            startDate = cal.timeInMillis,
            amount = if (mode == RecurringItem.Mode.AUTO_TX) amt else null,
            txType = if (mode == RecurringItem.Mode.AUTO_TX) txType else null,
            category = if (mode == RecurringItem.Mode.AUTO_TX) category else null,
            note = note,
            nextRunAt = cal.timeInMillis,
            isEnabled = item?.isEnabled ?: true,
            endMode = endMode,
            endAfterCount = if (endMode == RecurringItem.EndMode.AFTER_COUNT) endCountText.toIntOrNull() else null,
            endDate = if (endMode == RecurringItem.EndMode.ON_DATE) endDate else null
        )
        scope.launch {
            withContext(Dispatchers.IO) {
                if (editing) db.recurringDao().update(base)
                else db.recurringDao().insert(base)
            }
            RecurringWorker.triggerNow(context)
            onBack()
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                title = {
                    Text(
                        "周期任务",
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                },
                actions = {
                    TextButton(onClick = { doSave() }) {
                        Text(if (editing) "保存" else "创建")
                    }
                },
                windowInsets = WindowInsets(0, 0, 0, 0)
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .embeddedImePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            if (editing) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) { db.recurringDao().delete(item!!.id) }
                            RecurringWorker.triggerNow(context)
                            onBack()
                        }
                    }) { Text("🗑 删除", color = Color(0xFFE53935)) }
                }
            }
            Spacer(Modifier.height(12.dp))

            // ── 明细卡片（可展开） ──
            Card(
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { if (!detailExpanded) detailExpanded = true }
            ) {
                if (!detailExpanded) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 18.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "点击设置明细（金额/用途）",
                            fontSize = 15.sp,
                            color = BlueColor,
                            modifier = Modifier.clickable { showEntryCapture = true }
                        )
                    }
                } else {
                    Column(Modifier.padding(14.dp)) {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text("名称（如：工资 / 房贷 / 会员续费）") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(10.dp))
                        Text("类型", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = mode == RecurringItem.Mode.REMIND,
                                onClick = { mode = RecurringItem.Mode.REMIND },
                                label = { Text("⏰ 账单提醒") }
                            )
                            FilterChip(
                                selected = mode == RecurringItem.Mode.AUTO_TX,
                                onClick = { mode = RecurringItem.Mode.AUTO_TX },
                                label = { Text("🔁 自动记账") }
                            )
                        }
                        if (mode == RecurringItem.Mode.AUTO_TX) {
                            Spacer(Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(
                                    selected = txType == RecurringItem.TxType.EXPENSE,
                                    onClick = { txType = RecurringItem.TxType.EXPENSE },
                                    label = { Text("支出") }
                                )
                                FilterChip(
                                    selected = txType == RecurringItem.TxType.INCOME,
                                    onClick = { txType = RecurringItem.TxType.INCOME },
                                    label = { Text("收入") }
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = amountText,
                                onValueChange = { v ->
                                    v.filter { it.isDigit() || it == '.' }
                                        .let { if (it.count { c -> c == '.' } <= 1) amountText = it }
                                },
                                label = { Text("金额") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(8.dp))
                            Box {
                                OutlinedTextField(
                                    value = category,
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text("分类") },
                                    trailingIcon = {
                                        Text("▾", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { catExpanded = true }
                                )
                                DropdownMenu(
                                    expanded = catExpanded,
                                    onDismissRequest = { catExpanded = false }
                                ) {
                                    cats.forEach { c ->
                                        DropdownMenuItem(
                                            text = { Text(c) },
                                            onClick = { category = c; catExpanded = false }
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = note,
                            onValueChange = { note = it },
                            label = { Text("备注（可选）") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("周期设置", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = BlueColor)
            Spacer(Modifier.height(8.dp))

            // 首笔入账日期
            SettingRow(
                "首笔入账日期",
                SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(firstDate))
            ) {
                showFirstDatePicker = true
            }
            Spacer(Modifier.height(10.dp))
            Text("重复频率", fontSize = 14.sp)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    RecurringItem.Period.DAILY to "每日",
                    RecurringItem.Period.WEEKLY to "每周",
                    RecurringItem.Period.MONTHLY to "每月",
                    RecurringItem.Period.YEARLY to "每年"
                ).forEach { (p, lbl) ->
                    FilterChip(
                        selected = period == p,
                        onClick = { period = p },
                        label = { Text(lbl) }
                    )
                }
            }
            when (period) {
                RecurringItem.Period.WEEKLY -> {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            1 to "一", 2 to "二", 3 to "三", 4 to "四",
                            5 to "五", 6 to "六", 7 to "日"
                        ).forEach { (d, lbl) ->
                            FilterChip(
                                selected = dayOfWeek == d,
                                onClick = { dayOfWeek = d },
                                label = { Text(lbl) }
                            )
                        }
                    }
                }
                RecurringItem.Period.MONTHLY, RecurringItem.Period.YEARLY -> {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = dayOfMonth,
                        onValueChange = { dayOfMonth = it.filter { c -> c.isDigit() }.take(2) },
                        label = {
                            Text(if (period == RecurringItem.Period.YEARLY) "每年第几天（1-31）" else "每月第几天（1-31）")
                        },
                        singleLine = true,
                        modifier = Modifier.width(200.dp)
                    )
                }
                else -> {}
            }

            Spacer(Modifier.height(14.dp))
            Text("结束条件", fontSize = 14.sp)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = endMode == RecurringItem.EndMode.NEVER,
                    onClick = { endMode = RecurringItem.EndMode.NEVER },
                    label = { Text("永不结束") }
                )
                FilterChip(
                    selected = endMode == RecurringItem.EndMode.AFTER_COUNT,
                    onClick = { endMode = RecurringItem.EndMode.AFTER_COUNT },
                    label = { Text("按次数") }
                )
                FilterChip(
                    selected = endMode == RecurringItem.EndMode.ON_DATE,
                    onClick = { endMode = RecurringItem.EndMode.ON_DATE },
                    label = { Text("按日期") }
                )
            }
            when (endMode) {
                RecurringItem.EndMode.AFTER_COUNT -> {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = endCountText,
                        onValueChange = { endCountText = it.filter { c -> c.isDigit() }.take(4) },
                        label = { Text("运行多少次后结束") },
                        singleLine = true,
                        modifier = Modifier.width(200.dp)
                    )
                }
                RecurringItem.EndMode.ON_DATE -> {
                    Spacer(Modifier.height(8.dp))
                    SettingRow(
                        "结束日期",
                        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(endDate))
                    ) {
                        showEndDatePicker = true
                    }
                }
                else -> {}
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showFirstDatePicker) {
        val dpState = rememberDatePickerState(initialSelectedDateMillis = localDayToUtc(firstDate))
        DatePickerDialog(
            onDismissRequest = { showFirstDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    dpState.selectedDateMillis?.let { firstDate = utcToLocalDayStart(it) }
                    showFirstDatePicker = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showFirstDatePicker = false }) { Text("取消") }
            }
        ) { DatePicker(state = dpState) }
    }
    if (showEndDatePicker) {
        val dpState = rememberDatePickerState(initialSelectedDateMillis = localDayToUtc(endDate))
        DatePickerDialog(
            onDismissRequest = { showEndDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    dpState.selectedDateMillis?.let { endDate = utcToLocalDayStart(it) }
                    showEndDatePicker = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showEndDatePicker = false }) { Text("取消") }
            }
        ) { DatePicker(state = dpState) }
    }

    // 捕获模式记一笔弹窗：输入金额/用途 → 回填到周期任务字段
    if (showEntryCapture) {
        val initType = when (txType) {
            RecurringItem.TxType.EXPENSE -> com.bookkeeping.app.data.entity.Transaction.Type.EXPENSE
            RecurringItem.TxType.INCOME -> com.bookkeeping.app.data.entity.Transaction.Type.INCOME
        }
        com.bookkeeping.app.ManualAddDialog(
            onDismiss = { showEntryCapture = false },
            initialType = initType,
            onCapture = { data ->
                mode = RecurringItem.Mode.AUTO_TX
                amountText = data.amount.formatAmount()
                category = data.category
                note = data.note
                txType = when (data.type) {
                    com.bookkeeping.app.data.entity.Transaction.Type.INCOME -> RecurringItem.TxType.INCOME
                    else -> RecurringItem.TxType.EXPENSE
                }
                if (name.isBlank()) name = data.category
                detailExpanded = true
            },
            embedded = true
        )
    }
}

// ─── 通用小组件 / 工具 ─────────────────────────────
@Composable
internal fun SettingRow(
    label: String,
    value: String,
    onClick: () -> Unit
) {
    Card(shape = RoundedCornerShape(14.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { onClick() }
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Text(value, fontSize = 14.sp, color = BlueColor)
            Text("  ›", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
