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


// ─── 账单分期编辑器 ───────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun InstallmentEditor(onBack: () -> Unit) {
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
                                            (principal + fee).formatAmount(),
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = Color(0xFFE53935)
                                        )
                                        if (fee > 0) Text(
                                            "本金${principal.formatAmount()} +费${fee.formatAmount()}",
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
        val dpState = rememberDatePickerState(initialSelectedDateMillis = localDayToUtc(firstDate))
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    dpState.selectedDateMillis?.let { firstDate = utcToLocalDayStart(it) }
                    showDatePicker = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("取消") }
            }
        ) { DatePicker(state = dpState) }
    }
}

private fun dayStart(ts: Long): Long = Calendar.getInstance().apply {
    timeInMillis = ts
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis
