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
import java.util.TimeZone

private val BlueColor = Color(0xFF2E5AAC)

// ─── 入口页 ───────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecurringScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.getInstance(context) }
    var filter by remember { mutableStateOf(0) } // 0全部 1周期 2分期
    var recurring by remember { mutableStateOf<List<RecurringItem>>(emptyList()) }
    var plans by remember { mutableStateOf<List<InstallmentPlan>>(emptyList()) }
    var accounts by remember { mutableStateOf<List<Account>>(emptyList()) }
    var showCreateMenu by remember { mutableStateOf(false) }
    // 0=无 1=编辑周期任务 2=新建周期任务 3=新建分期；查看中的分期
    var editorMode by remember { mutableStateOf(0) }
    var editingRecurring by remember { mutableStateOf<RecurringItem?>(null) }
    var viewingPlan by remember { mutableStateOf<InstallmentPlan?>(null) }

    fun refresh() {
        scope.launch {
            withContext(Dispatchers.IO) {
                recurring = db.recurringDao().getAll()
                plans = db.installmentDao().getAll()
                accounts = db.accountDao().getAllIncludingDisabled()
            }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    when (editorMode) {
        1, 2 -> PeriodTaskEditor(
            item = editingRecurring,
            onBack = { editorMode = 0; editingRecurring = null; refresh() }
        )
        3 -> InstallmentEditor(
            onBack = { editorMode = 0; refresh() }
        )
        else -> {
            Scaffold(
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                topBar = {
                    TopAppBar(
                        navigationIcon = {
                            IconButton(onClick = onClose) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                            }
                        },
                        title = {
                            Text(
                                "周期记账",
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Center
                            )
                        },
                        actions = {
                            IconButton(onClick = { showCreateMenu = true }) {
                                Icon(Icons.Outlined.AddCircle, contentDescription = "新增")
                            }
                        },
                        windowInsets = WindowInsets(0, 0, 0, 0)
                    )
                }
            ) { padding ->
                Column(Modifier.fillMaxSize().padding(padding)) {
                    SegmentedFilter(
                        options = listOf("全部", "周期", "分期"),
                        selected = filter,
                        onSelect = { filter = it },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )

                    val isEmpty = (filter != 2 && recurring.isEmpty()) || (filter != 1 && plans.isEmpty())
                    if ((filter == 0 && recurring.isEmpty() && plans.isEmpty()) ||
                        (filter == 1 && recurring.isEmpty()) ||
                        (filter == 2 && plans.isEmpty())
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("🧩", fontSize = 52.sp)
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    "暂无任务",
                                    fontSize = 15.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "点右上角环形加号「新增」\n周期任务 / 信用卡账单分期",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (filter != 2) {
                                items(recurring) { item ->
                                    RecurringRow(
                                        item = item,
                                        onClick = { editingRecurring = item; editorMode = 1 },
                                        onToggle = {
                                            scope.launch {
                                                withContext(Dispatchers.IO) {
                                                    db.recurringDao().update(item.copy(isEnabled = !item.isEnabled))
                                                }
                                                refresh()
                                            }
                                        }
                                    )
                                }
                            }
                            if (filter != 1) {
                                items(plans) { plan ->
                                    InstallmentRow(
                                        plan = plan,
                                        accountName = accounts.firstOrNull { it.id == plan.accountId }?.name ?: "未知账户",
                                        onClick = { viewingPlan = plan }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreateMenu) {
        AlertDialog(
            onDismissRequest = { showCreateMenu = false },
            title = { Text("创建", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(
                        "周期任务",
                        fontSize = 17.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showCreateMenu = false
                                editingRecurring = null; editorMode = 2
                            }
                            .padding(vertical = 14.dp)
                    )
                    Text(
                        "信用卡账单分期",
                        fontSize = 17.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showCreateMenu = false
                                editorMode = 3
                            }
                            .padding(vertical = 14.dp)
                    )
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showCreateMenu = false }) { Text("取消") }
            }
        )
    }

    viewingPlan?.let { plan ->
        val accName = accounts.firstOrNull { it.id == plan.accountId }?.name ?: "未知账户"
        InstallmentDetailDialog(
            plan = plan,
            accountName = accName,
            onDismiss = { viewingPlan = null },
            onDeleted = {
                scope.launch {
                    withContext(Dispatchers.IO) { db.installmentDao().delete(plan.id) }
                    viewingPlan = null
                    refresh()
                }
            }
        )
    }
}

// ─── 分段筛选控件 ──────────────────────────────────
@Composable
private fun SegmentedFilter(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                RoundedCornerShape(22.dp)
            )
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        options.forEachIndexed { i, label ->
            val isSel = i == selected
            Box(
                Modifier
                    .weight(1f)
                    .background(
                        if (isSel) MaterialTheme.colorScheme.surface else Color.Transparent,
                        RoundedCornerShape(18.dp)
                    )
                    .clickable { onSelect(i) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    fontSize = 14.sp,
                    color = if (isSel) BlueColor else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (isSel) FontWeight.Medium else FontWeight.Normal
                )
            }
        }
    }
}

// ─── 列表行：周期任务 ──────────────────────────────
@Composable
private fun RecurringRow(
    item: RecurringItem,
    onClick: () -> Unit,
    onToggle: () -> Unit
) {
    val periodText = when (item.period) {
        RecurringItem.Period.DAILY -> "每天"
        RecurringItem.Period.WEEKLY -> "每周${"一二三四五六日"[(item.dayOfWeek ?: 1) - 1]}"
        RecurringItem.Period.MONTHLY -> "每月${item.dayOfMonth}号"
        RecurringItem.Period.YEARLY -> "每年${item.dayOfMonth}号"
    }
    val modeColor = if (item.mode == RecurringItem.Mode.AUTO_TX) BlueColor else Color(0xFFFF9800)
    val modeText = if (item.mode == RecurringItem.Mode.AUTO_TX) "自动记账" else "账单提醒"
    val nextRun = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(item.nextRunAt))
    val dim = !item.isEnabled

    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(38.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(19.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(if (item.mode == RecurringItem.Mode.AUTO_TX) "🔁" else "⏰", fontSize = 17.sp)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        item.name,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (dim) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurface
                    )
                    if (item.endMode == RecurringItem.EndMode.AFTER_COUNT && item.endAfterCount != null) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "${item.runCount + 1}/${item.endAfterCount}",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(modeText, fontSize = 11.sp, color = modeColor)
                    Text("·", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(periodText, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    if (dim) "已停用" else "下次：$nextRun",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = item.isEnabled, onCheckedChange = { onToggle() })
        }
    }
}

// ─── 列表行：账单分期 ──────────────────────────────
@Composable
private fun InstallmentRow(
    plan: InstallmentPlan,
    accountName: String,
    onClick: () -> Unit
) {
    val done = plan.status == InstallmentPlan.Status.DONE
    val nextDue = if (!done) installmentDueDate(plan.firstDate, plan.paidPeriods + 1) else 0L
    val isOverdue = !done && nextDue < System.currentTimeMillis()
    var remaining = 0.0
    for (p in (plan.paidPeriods + 1)..plan.installments) {
        val (principal, fee) = installmentPeriodAmounts(plan, p)
        remaining += principal + fee
    }
    val progress = plan.paidPeriods.toFloat() / plan.installments
    val remainingText = if (done) "" else "%.2f".format(remaining)
    val dueDateText = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(nextDue))
    val statusText = if (done) " · 已结清" else " · ${if (isOverdue) "已逾期，下期 " else "下期 "}$dueDateText"

    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(38.dp)
                        .background(Color(0xFFFFF3E0), RoundedCornerShape(19.dp)),
                    contentAlignment = Alignment.Center
                ) { Text("💳", fontSize = 17.sp) }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "$accountName · 账单分期",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (done) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "第${plan.paidPeriods}/${plan.installments}期$statusText",
                        fontSize = 11.sp,
                        color = if (isOverdue) Color(0xFFE53935)
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    "${if (done) "" else "剩 ¥"}$remainingText",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (done) MaterialTheme.colorScheme.onSurfaceVariant
                    else Color(0xFFE53935)
                )
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = progress,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = BlueColor,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        }
    }
}

// ─── 分期详情/删除弹窗 ─────────────────────────────
@Composable
private fun InstallmentDetailDialog(
    plan: InstallmentPlan,
    accountName: String,
    onDismiss: () -> Unit,
    onDeleted: () -> Unit
) {
    val summary = "总额 ¥%.2f · 手续费 ¥%.2f · 共${plan.installments}期，已入账${plan.paidPeriods}期"
        .format(plan.totalAmount, plan.totalFee)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("$accountName 账单分期", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(
                    summary,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Column(
                    Modifier
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    for (p in 1..plan.installments) {
                        val (principal, fee) = installmentPeriodAmounts(plan, p)
                        val paid = p <= plan.paidPeriods
                        val dateText = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                            .format(Date(installmentDueDate(plan.firstDate, p)))
                        val amountText = "%.2f".format(principal + fee)

                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "${if (paid) "✓ " else ""}第${p}期",
                                fontSize = 13.sp,
                                color = if (paid) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.width(70.dp)
                            )
                            Text(
                                dateText,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                amountText,
                                fontSize = 13.sp,
                                color = if (paid) MaterialTheme.colorScheme.onSurfaceVariant
                                else Color(0xFFE53935)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDeleted) {
                Text("删除计划", color = Color(0xFFE53935))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

// ─── 周期任务编辑器 ───────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PeriodTaskEditor(
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
        val dpState = rememberDatePickerState(initialSelectedDateMillis = localToUtcMillis(firstDate))
        DatePickerDialog(
            onDismissRequest = { showFirstDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    dpState.selectedDateMillis?.let { firstDate = utcToLocalDay(it) }
                    showFirstDatePicker = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showFirstDatePicker = false }) { Text("取消") }
            }
        ) { DatePicker(state = dpState) }
    }
    if (showEndDatePicker) {
        val dpState = rememberDatePickerState(initialSelectedDateMillis = localToUtcMillis(endDate))
        DatePickerDialog(
            onDismissRequest = { showEndDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    dpState.selectedDateMillis?.let { endDate = utcToLocalDay(it) }
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
                amountText = "%.2f".format(data.amount)
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

// ─── 账单分期编辑器 ───────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InstallmentEditor(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.getInstance(context) }
    var accounts by remember { mutableStateOf<List<Account>>(emptyList()) }

    LaunchedEffect(Unit) {
        accounts = withContext(Dispatchers.IO) { db.accountDao().getAllIncludingDisabled() }
    }
    // 信用卡排最前
    val sorted = remember(accounts) {
        accounts.sortedByDescending { it.type == Account.AccountType.CREDIT_CARD }
    }

    var accountId by remember { mutableStateOf<Long?>(null) }
    var amountText by remember { mutableStateOf("") }
    var firstDate by remember { mutableStateOf(dayStart(System.currentTimeMillis())) }
    var periodsText by remember { mutableStateOf("12") }
    var feeText by remember { mutableStateOf("") }
    var feeMode by remember { mutableStateOf(InstallmentPlan.FeeMode.MONTHLY_AVG) }
    var feeIntoDebt by remember { mutableStateOf(false) }
    var remainderInto by remember { mutableStateOf(InstallmentPlan.RemainderTarget.FIRST) }
    var showPreview by remember { mutableStateOf(false) }
    var showAccountPicker by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    val selectedAccount = sorted.firstOrNull { it.id == accountId }

    fun doNextOrCreate() {
        val amount = amountText.toDoubleOrNull()
        val n = periodsText.toIntOrNull()
        if (accountId == null) {
            android.widget.Toast.makeText(context, "请选择信用账户", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        if (amount == null || amount <= 0) {
            android.widget.Toast.makeText(context, "请输入有效分期金额", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        if (n == null || n <= 0) {
            android.widget.Toast.makeText(context, "请输入有效分期数", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        if (!showPreview) {
            showPreview = true
            return
        }
        val plan = InstallmentPlan(
            accountId = accountId!!,
            totalAmount = amount,
            installments = n,
            firstDate = firstDate,
            totalFee = feeText.toDoubleOrNull() ?: 0.0,
            feeMode = feeMode,
            feeIntoDebt = feeIntoDebt,
            remainderInto = remainderInto
        )
        scope.launch {
            withContext(Dispatchers.IO) { db.installmentDao().insert(plan) }
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
                        "账单分期",
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                },
                actions = {
                    TextButton(onClick = { doNextOrCreate() }) {
                        Text(if (showPreview) "确认创建" else "下一步")
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
            Spacer(Modifier.height(12.dp))

            if (!showPreview) {
                // ── 账户 + 金额卡片 ──
                Card(shape = RoundedCornerShape(14.dp)) {
                    Column(Modifier.padding(14.dp)) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { showAccountPicker = true }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("信用账户", fontSize = 15.sp, modifier = Modifier.weight(1f))
                            Text(
                                selectedAccount?.let { "${it.icon} ${it.name}" } ?: "请选择账户",
                                fontSize = 14.sp,
                                color = if (selectedAccount == null) MaterialTheme.colorScheme.onSurfaceVariant
                                else BlueColor
                            )
                            Text("  ›", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        HorizontalDivider()
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("分期金额", fontSize = 15.sp, modifier = Modifier.weight(1f))
                            OutlinedTextField(
                                value = amountText,
                                onValueChange = { v ->
                                    v.filter { it.isDigit() || it == '.' }
                                        .let { if (it.count { c -> c == '.' } <= 1) amountText = it }
                                },
                                singleLine = true,
                                textStyle = LocalTextStyle.current.copy(textAlign = TextAlign.End),
                                modifier = Modifier.width(170.dp)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))
                SettingRow(
                    "首笔入账日期",
                    SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(firstDate))
                ) {
                    showDatePicker = true
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = periodsText,
                    onValueChange = { periodsText = it.filter { c -> c.isDigit() }.take(3) },
                    label = { Text("分期数") },
                    singleLine = true,
                    modifier = Modifier.width(200.dp)
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = feeText,
                    onValueChange = { v ->
                        v.filter { it.isDigit() || it == '.' }
                            .let { if (it.count { c -> c == '.' } <= 1) feeText = it }
                    },
                    label = { Text("手续费总额（可选）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Text("手续费收取方式", fontSize = 14.sp)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = feeMode == InstallmentPlan.FeeMode.MONTHLY_AVG,
                        onClick = { feeMode = InstallmentPlan.FeeMode.MONTHLY_AVG },
                        label = { Text("按月均摊") }
                    )
                    FilterChip(
                        selected = feeMode == InstallmentPlan.FeeMode.FIRST_PERIOD,
                        onClick = { feeMode = InstallmentPlan.FeeMode.FIRST_PERIOD },
                        label = { Text("首期一次性") }
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("未入账的手续费计入总欠款", fontSize = 14.sp)
                        Text(
                            "仅影响欠款展示",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = feeIntoDebt, onCheckedChange = { feeIntoDebt = it })
                }
                Spacer(Modifier.height(10.dp))
                Text("余数计入", fontSize = 14.sp)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = remainderInto == InstallmentPlan.RemainderTarget.FIRST,
                        onClick = { remainderInto = InstallmentPlan.RemainderTarget.FIRST },
                        label = { Text("首期") }
                    )
                    FilterChip(
                        selected = remainderInto == InstallmentPlan.RemainderTarget.LAST,
                        onClick = { remainderInto = InstallmentPlan.RemainderTarget.LAST },
                        label = { Text("末期") }
                    )
                }
            } else {
                // ── 分期预览 ──
                val n = periodsText.toIntOrNull() ?: 12
                val previewPlan = InstallmentPlan(
                    accountId = accountId ?: 0,
                    totalAmount = amountText.toDoubleOrNull() ?: 0.0,
                    installments = n,
                    firstDate = firstDate,
                    totalFee = feeText.toDoubleOrNull() ?: 0.0,
                    feeMode = feeMode,
                    remainderInto = remainderInto
                )
                Text(
                    "${selectedAccount?.name ?: ""} · 共$n 期",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Card(shape = RoundedCornerShape(14.dp)) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
                        for (p in 1..n) {
                            val (principal, fee) = installmentPeriodAmounts(previewPlan, p)
                            Column {
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text("第${p}期", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                        Text(
                                            SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                                                .format(Date(installmentDueDate(firstDate, p))),
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(
                                            "%.2f".format(principal + fee),
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = Color(0xFFE53935)
                                        )
                                        if (fee > 0) Text(
                                            "本金%.2f +费%.2f".format(principal, fee),
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                if (p < n) HorizontalDivider()
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = { showPreview = false }) { Text("‹ 返回修改") }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (showAccountPicker) {
        AlertDialog(
            onDismissRequest = { showAccountPicker = false },
            title = { Text("选择信用账户", fontWeight = FontWeight.Bold) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (sorted.isEmpty()) {
                        Text(
                            "暂无账户，请先在账户管理中添加",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    sorted.forEach { acc ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    accountId = acc.id; showAccountPicker = false
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(acc.icon, fontSize = 18.sp)
                            Spacer(Modifier.width(10.dp))
                            Text(acc.name, fontSize = 15.sp, modifier = Modifier.weight(1f))
                            if (acc.type == Account.AccountType.CREDIT_CARD) {
                                Text("信用卡", fontSize = 10.sp, color = BlueColor)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showAccountPicker = false }) { Text("取消") }
            }
        )
    }
    if (showDatePicker) {
        val dpState = rememberDatePickerState(initialSelectedDateMillis = localToUtcMillis(firstDate))
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    dpState.selectedDateMillis?.let { firstDate = utcToLocalDay(it) }
                    showDatePicker = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("取消") }
            }
        ) { DatePicker(state = dpState) }
    }
}

// ─── 通用小组件 / 工具 ─────────────────────────────
@Composable
private fun SettingRow(
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

private fun dayStart(ts: Long): Long = Calendar.getInstance().apply {
    timeInMillis = ts
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

/** DatePicker 给的是 UTC 零点毫秒 → 转成本地日期零点 */
private fun utcToLocalDay(utc: Long): Long {
    val uc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utc }
    return Calendar.getInstance().apply {
        clear()
        set(uc.get(Calendar.YEAR), uc.get(Calendar.MONTH), uc.get(Calendar.DAY_OF_MONTH))
    }.timeInMillis
}

/** 本地日期零点 → UTC 零点毫秒（给 DatePicker 回显） */
private fun localToUtcMillis(local: Long): Long {
    val lc = Calendar.getInstance().apply { timeInMillis = local }
    return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear()
        set(lc.get(Calendar.YEAR), lc.get(Calendar.MONTH), lc.get(Calendar.DAY_OF_MONTH))
    }.timeInMillis
}
