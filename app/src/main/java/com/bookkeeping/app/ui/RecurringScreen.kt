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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bookkeeping.app.EmptyState
import com.bookkeeping.app.data.AppDatabase
import com.bookkeeping.app.data.entity.Account
import com.bookkeeping.app.data.entity.InstallmentPlan
import com.bookkeeping.app.data.entity.RecurringItem
import com.bookkeeping.app.data.entity.installmentDueDate
import com.bookkeeping.app.data.entity.installmentPeriodAmounts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.bookkeeping.app.formatAmount

internal val BlueColor = Color(0xFF2E5AAC)

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
                        EmptyState(
                            "🧩",
                            "暂无任务",
                            subMessage = "点右上角环形加号「新增」\n周期任务 / 信用卡账单分期"
                        )
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
    val remainingText = if (done) "" else remaining.formatAmount()
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
    val summary = "总额 ¥${plan.totalAmount.formatAmount()} · 手续费 ¥${plan.totalFee.formatAmount()} · 共${plan.installments}期，已入账${plan.paidPeriods}期"

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
                        val amountText = (principal + fee).formatAmount()

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
