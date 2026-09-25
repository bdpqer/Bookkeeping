package com.bookkeeping.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddCircle
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import com.bookkeeping.app.LedgerDropdownTitle
import com.bookkeeping.app.data.AppDatabase
import com.bookkeeping.app.data.entity.DebtRecord
import com.bookkeeping.app.data.entity.Ledger
import com.bookkeeping.app.data.entity.Transaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.bookkeeping.app.formatAmount

private val debtDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

/** 借贷管理全屏页：借出/借入列表 + 新增 + 还款冲抵 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoanScreen(ledgers: List<Ledger>, initialLedgerId: Long, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.getInstance(context) }
    var selLedgerId by remember { mutableLongStateOf(initialLedgerId) }
    var debts by remember { mutableStateOf<List<DebtRecord>>(emptyList()) }
    var totalLent by remember { mutableStateOf(0.0) }
    var totalBorrowed by remember { mutableStateOf(0.0) }
    var showAdd by remember { mutableStateOf(false) }
    var repayTarget by remember { mutableStateOf<DebtRecord?>(null) }

    fun reload() {
        scope.launch {
            withContext(Dispatchers.IO) {
                debts = if (selLedgerId == 0L) db.debtDao().getAll()
                        else db.debtDao().getByLedger(selLedgerId)
                totalLent = if (selLedgerId == 0L) db.debtDao().totalLentOut()
                            else db.debtDao().totalLentOutByLedger(selLedgerId)
                totalBorrowed = if (selLedgerId == 0L) db.debtDao().totalBorrowed()
                                else db.debtDao().totalBorrowedByLedger(selLedgerId)
            }
        }
    }

    LaunchedEffect(selLedgerId) { reload() }

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
                    LedgerDropdownTitle(
                        ledgers = ledgers,
                        selectedLedgerId = selLedgerId,
                        onSelect = { selLedgerId = it }
                    )
                },
                actions = {
                    IconButton(onClick = { showAdd = true }) {
                        Icon(Icons.Outlined.AddCircle, contentDescription = "新增")
                    }
                },
                windowInsets = WindowInsets(0, 0, 0, 0)
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // 汇总卡
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Card(
                    modifier = Modifier.weight(1f),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0)),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text("别人欠我", fontSize = 12.sp, color = Color(0xFF8D6E63))
                        Text(
                            "¥${totalLent.formatAmount()}",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFEF6C00)
                        )
                    }
                }
                Card(
                    modifier = Modifier.weight(1f),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFE3F2FD)),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text("我欠别人", fontSize = 12.sp, color = Color(0xFF546E7A))
                        Text(
                            "¥${totalBorrowed.formatAmount()}",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF1565C0)
                        )
                    }
                }
            }

            if (debts.isEmpty()) {
                Box(
                    Modifier.fillMaxWidth().weight(1f).padding(48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "暂无借贷记录\n点右上角环形加号「新增」",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        bottom = 24.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(debts, key = { it.id }) { debt ->
                        DebtItem(
                            debt = debt,
                            onRepay = { repayTarget = debt },
                            onDelete = {
                                scope.launch {
                                    withContext(Dispatchers.IO) { db.debtDao().delete(debt.id) }
                                    reload()
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    if (showAdd) {
        AddDebtDialog(
            ledgers = ledgers,
            forcedLedgerId = if (selLedgerId == 0L) null else selLedgerId,
            onDismiss = { showAdd = false },
            onSaved = { showAdd = false; reload() }
        )
    }

    repayTarget?.let { target ->
        RepayDebtDialog(
            debt = target,
            onDismiss = { repayTarget = null },
            onSaved = { repayTarget = null; reload() }
        )
    }
}

@Composable
private fun DebtItem(
    debt: DebtRecord,
    onRepay: () -> Unit,
    onDelete: () -> Unit
) {
    val isLent = debt.direction == DebtRecord.LENT_OUT
    val progress = if (debt.amount > 0) {
        (debt.repaid / debt.amount).toFloat().coerceIn(0f, 1f)
    } else {
        1f
    }
    val noteSuffix = if (debt.note.isNotBlank()) " · ${debt.note}" else ""
    val dateText = debtDateFormat.format(Date(debt.occurredAt)) + noteSuffix

    Card(shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .width(4.dp)
                        .height(36.dp)
                        .background(
                            if (isLent) Color(0xFFEF6C00) else Color(0xFF1565C0),
                            RoundedCornerShape(2.dp)
                        )
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(debt.person, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (isLent) "借出" else "借入",
                            fontSize = 11.sp,
                            color = if (isLent) Color(0xFFEF6C00) else Color(0xFF1565C0),
                            modifier = Modifier
                                .background(
                                    (if (isLent) Color(0xFFEF6C00) else Color(0xFF1565C0)).copy(alpha = 0.1f),
                                    RoundedCornerShape(4.dp)
                                )
                                .padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }
                    Text(
                        dateText,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        "¥${debt.amount.formatAmount()}",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = if (isLent) Color(0xFFEF6C00) else Color(0xFF1565C0)
                    )
                    Text(
                        if (debt.settled) "✅ 已结清" else "剩 ¥${debt.remaining.formatAmount()}",
                        fontSize = 11.sp,
                        color = if (debt.settled) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = progress,
                modifier = Modifier.fillMaxWidth().height(4.dp),
                color = if (debt.settled) Color(0xFF4CAF50)
                else if (isLent) Color(0xFFEF6C00)
                else Color(0xFF1565C0),
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDelete) {
                    Text("删除", fontSize = 12.sp, color = Color(0xFFE53935))
                }
                if (!debt.settled) {
                    TextButton(onClick = onRepay) {
                        Text(
                            if (isLent) "收还款" else "去归还",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AddDebtDialog(ledgers: List<Ledger>, forcedLedgerId: Long?, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.getInstance(context) }
    var isLent by remember { mutableStateOf(true) }
    var person by remember { mutableStateOf("") }
    var amountText by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var selLedgerId by remember {
        mutableStateOf(forcedLedgerId ?: ledgers.firstOrNull { it.isDefault }?.id ?: ledgers.firstOrNull()?.id ?: 1L)
    }
    var ledMenu by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新增借贷") },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(
                        onClick = { isLent = true },
                        label = { Text(if (isLent) "↗ 借出（别人欠我）" else "↗ 借出") }
                    )
                    AssistChip(
                        onClick = { isLent = false },
                        label = { Text(if (!isLent) "↘ 借入（我欠别人）" else "↘ 借入") }
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = person,
                    onValueChange = { person = it },
                    label = { Text("对方姓名") },
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("金额") },
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                // 账本选择行
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("记账账本", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    Box {
                        Row(
                            Modifier.then(if (forcedLedgerId == null) Modifier.clickable { ledMenu = true } else Modifier),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                ledgers.firstOrNull { it.id == selLedgerId }?.let { "${it.icon} ${it.name}" } ?: "未选择",
                                fontSize = 14.sp, fontWeight = FontWeight.Medium
                            )
                            if (forcedLedgerId == null) {
                                Text(" ▾", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        DropdownMenu(expanded = ledMenu, onDismissRequest = { ledMenu = false }) {
                            ledgers.forEach { led ->
                                DropdownMenuItem(
                                    text = { Text("${led.icon} ${led.name}") },
                                    onClick = { ledMenu = false; selLedgerId = led.id }
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("备注（可选）") },
                    singleLine = true
                )
                error?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = Color(0xFFE53935), fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val amt = amountText.toDoubleOrNull()
                when {
                    person.isBlank() -> error = "请填写对方姓名"
                    amt == null || amt <= 0 -> error = "请填写有效金额"
                    else -> {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                db.debtDao().insert(
                                    DebtRecord(
                                        direction = if (isLent) DebtRecord.LENT_OUT else DebtRecord.BORROWED,
                                        person = person.trim(),
                                        amount = amt,
                                        note = note.trim(),
                                        occurredAt = System.currentTimeMillis(),
                                        ledgerId = selLedgerId
                                    )
                                )
                            }
                            onSaved()
                        }
                    }
                }
            }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun RepayDebtDialog(
    debt: DebtRecord,
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.getInstance(context) }
    val isLent = debt.direction == DebtRecord.LENT_OUT
    var amountText by remember { mutableStateOf(debt.remaining.formatAmount()) }
    var alsoRecord by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    val amountStr = debt.amount.formatAmount()
    val repaidStr = debt.repaid.formatAmount()
    val remainingStr = debt.remaining.formatAmount()
    val summaryText = "借款 ¥$amountStr，" +
        (if (isLent) "已收 ¥$repaidStr" else "已还 ¥$repaidStr") +
        "，剩 ¥$remainingStr"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isLent) "收到 ${debt.person} 的还款" else "归还给 ${debt.person}") },
        text = {
            Column {
                Text(
                    summaryText,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("本次金额") },
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("同时生成一笔收支记录", fontSize = 13.sp)
                    Switch(checked = alsoRecord, onCheckedChange = { alsoRecord = it })
                }
                Text(
                    if (isLent) "将记一笔收入（分类：还款）" else "将记一笔支出（分类：还款）",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                error?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = Color(0xFFE53935), fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val amt = amountText.toDoubleOrNull()
                when {
                    amt == null || amt <= 0 -> error = "请填写有效金额"
                    amt > debt.remaining + 0.005 -> error = "超过剩余金额 ¥$remainingStr"
                    else -> {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                db.debtDao().updateRepaid(debt.id, debt.repaid + amt)
                                if (alsoRecord) {
                                    db.transactionDao().insert(
                                        Transaction(
                                            amount = amt,
                                            type = if (isLent) Transaction.Type.INCOME else Transaction.Type.EXPENSE,
                                            category = "还款",
                                            merchant = debt.person,
                                            source = "借贷",
                                            note = if (isLent) "收回借款" else "归还借款",
                                            rawText = "[借贷] ${debt.person} ¥${amt.formatAmount()}",
                                            isManual = true,
                                            confirmed = true,
                                            confidence = Transaction.Confidence.HIGH,
                                            occurredAt = System.currentTimeMillis()
                                        )
                                    )
                                }
                            }
                            onSaved()
                        }
                    }
                }
            }) { Text("确认") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
