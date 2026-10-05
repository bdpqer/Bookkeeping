package com.bookkeeping.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import com.bookkeeping.app.theme.ExpenseRedFaint
import com.bookkeeping.app.DetailTopBar
import com.bookkeeping.app.LedgerDropdownTitle
import com.bookkeeping.app.data.AppDatabase
import com.bookkeeping.app.data.entity.Ledger
import com.bookkeeping.app.data.entity.Receivable
import com.bookkeeping.app.sanitizeAmountInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar
import com.bookkeeping.app.theme.DangerRed
import com.bookkeeping.app.theme.SuccessGreen
import com.bookkeeping.app.theme.TransferOrange
import com.bookkeeping.app.formatTime
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.outlined.AddCircle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bookkeeping.app.embeddedImePadding
import com.bookkeeping.app.formatAmount
import kotlinx.coroutines.launch
import com.bookkeeping.app.TxLedgerRow

import com.bookkeeping.app.EmptyState
/** 应收/应付款管理页 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceivableScreen(ledgers: List<Ledger>, initialLedgerId: Long, onClose: () -> Unit, initialTab: Int = 0) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.getInstance(context) }
    var tab by remember { mutableStateOf(initialTab) } // 0=应收款 1=应付款
    var selLedgerId by remember { mutableLongStateOf(initialLedgerId) }
    var list by remember { mutableStateOf<List<Receivable>>(emptyList()) }
    var showEditDialog by remember { mutableStateOf(false) }
    var editingItem by remember { mutableStateOf<Receivable?>(null) }
    val direction = if (tab == 0) Receivable.Direction.RECEIVABLE else Receivable.Direction.PAYABLE

    fun refresh() {
        scope.launch {
            list = withContext(Dispatchers.IO) {
                db.receivableDao().getByDirection(direction.name)
                    .filter { selLedgerId == 0L || it.ledgerId == selLedgerId }
            }
        }
    }

    LaunchedEffect(tab, selLedgerId) { refresh() }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            DetailTopBar(
                onBack = onClose,
                title = {
                    LedgerDropdownTitle(
                        ledgers = ledgers,
                        selectedLedgerId = selLedgerId,
                        onSelect = { selLedgerId = it }
                    )
                },
                actions = {
                    IconButton(onClick = { editingItem = null; showEditDialog = true }) {
                        Icon(Icons.Outlined.AddCircle, contentDescription = "新增")
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = tab) {
                Tab(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    text = { Text("应收款") }
                )
                Tab(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    text = { Text("应付款") }
                )
            }

            // 派生列表缓存：原先裸写在 Composable body 里，每次重组都做两次全表过滤
            val (pendingList, doneList) = remember(list) {
                list.partition { it.status == Receivable.Status.PENDING }
            }
            // 今日零点只算一次，避免每行都新建 Calendar
            val todayStart = remember {
                Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
            }

            if (list.isEmpty()) {
                EmptyState(
                    emoji = if (tab == 0) "💰" else "💸",
                    message = if (tab == 0) "暂无应收款" else "暂无应付款",
                    subMessage = "点右上角环形加号「新增」\n（如房租、信用卡、报销待回款）"
                )
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // 汇总条
                    item {
                        val totalPending = pendingList.sumOf { it.amount }
                        val totalDone = doneList.sumOf { it.amount }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                    RoundedCornerShape(10.dp)
                                )
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val sign = if (tab == 0) "+" else "-"
                            val pendingColor = if (tab == 0) SuccessGreen else TransferOrange
                            Text(
                                "待处理 ${pendingList.size} 笔 · $sign¥${totalPending.formatAmount()}",
                                fontSize = 13.sp,
                                color = pendingColor,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                "已完成 ${doneList.size} 笔 · ¥${totalDone.formatAmount()}",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // 待处理项
                    items(pendingList, key = { it.id }) { r ->
                        ReceivableRow(
                            r = r,
                            isReceivable = tab == 0,
                            todayStart = todayStart,
                            onClick = { editingItem = r; showEditDialog = true },
                            onMarkDone = {
                                scope.launch {
                                    withContext(Dispatchers.IO) { db.receivableDao().markDone(r.id) }
                                    refresh()
                                }
                            },
                            onReopen = {},
                            onDelete = {
                                scope.launch {
                                    withContext(Dispatchers.IO) { db.receivableDao().delete(r.id) }
                                    refresh()
                                }
                            }
                        )
                    }

                    if (doneList.isNotEmpty()) {
                        item {
                            Text(
                                "已完成",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                        items(doneList, key = { it.id }) { r ->
                            ReceivableRow(
                                r = r,
                                isReceivable = tab == 0,
                                todayStart = todayStart,
                                onClick = { editingItem = r; showEditDialog = true },
                                onMarkDone = {},
                                onReopen = {
                                    scope.launch {
                                        withContext(Dispatchers.IO) {
                                            db.receivableDao().update(r.copy(status = Receivable.Status.PENDING))
                                        }
                                        refresh()
                                    }
                                },
                                onDelete = {
                                    scope.launch {
                                        withContext(Dispatchers.IO) { db.receivableDao().delete(r.id) }
                                        refresh()
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showEditDialog) {
        ReceivableEditDialog(
            item = editingItem,
            defaultDirection = direction,
            ledgers = ledgers,
            forcedLedgerId = if (selLedgerId == 0L) null else selLedgerId,
            onDismiss = { showEditDialog = false; editingItem = null },
            onSaved = { showEditDialog = false; editingItem = null; refresh() }
        )
    }
}

@Composable
private fun ReceivableRow(
    r: Receivable,
    isReceivable: Boolean,
    todayStart: Long,
    onClick: () -> Unit,
    onMarkDone: () -> Unit,
    onReopen: () -> Unit,
    onDelete: () -> Unit
) {
    val isOverdue = r.status == Receivable.Status.PENDING && r.dueDate < todayStart
    val isDone = r.status == Receivable.Status.DONE
    val dueStr = formatTime(r.dueDate, "yyyy-MM-dd")
    val amountColor = when {
        isDone -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        isOverdue -> DangerRed
        isReceivable -> SuccessGreen
        else -> TransferOrange
    }
    val amountPrefix = if (isReceivable) "+" else "-"

    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        isDone -> "✅"
                        isOverdue -> "⚠️"
                        isReceivable -> "💰"
                        else -> "💸"
                    },
                    fontSize = 20.sp
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            r.counterparty,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (isDone) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            else MaterialTheme.colorScheme.onSurface
                        )
                        if (isOverdue) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "逾期",
                                fontSize = 10.sp,
                                color = DangerRed,
                                modifier = Modifier
                                    .background(ExpenseRedFaint, RoundedCornerShape(4.dp))
                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    val categorySuffix = if (r.category.isNotBlank()) " · ${r.category}" else ""
                    Text(
                        "到期：$dueStr$categorySuffix",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (r.description.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            r.description,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Text(
                    "$amountPrefix¥${r.amount.formatAmount()}",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = amountColor
                )
            }
            Spacer(Modifier.height(10.dp))
            // 删除/编辑按钮任何状态下都可用；待处理可标记完成，已完成可撤销
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDelete) {
                    Text("删除", color = DangerRed, fontSize = 12.sp)
                }
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = onClick) {
                    Text("编辑", fontSize = 12.sp)
                }
                Spacer(Modifier.weight(1f))
                if (isDone) {
                    OutlinedButton(
                        onClick = onReopen,
                        shape = RoundedCornerShape(8.dp)
                    ) { Text("↩ 撤销完成", fontSize = 12.sp) }
                } else {
                    Button(
                        onClick = onMarkDone,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen)
                    ) { Text("✓ 完成", fontSize = 12.sp) }
                }
            }
        }
    }
}

// ─── 编辑/创建弹窗 ───────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReceivableEditDialog(
    item: Receivable?,
    defaultDirection: Receivable.Direction,
    ledgers: List<Ledger>,
    forcedLedgerId: Long?,
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.getInstance(context) }
    val editing = item != null
    var direction by remember { mutableStateOf(item?.direction ?: defaultDirection) }
    var counterparty by remember { mutableStateOf(item?.counterparty ?: "") }
    var amountText by remember { mutableStateOf(item?.amount?.toString() ?: "") }
    var description by remember { mutableStateOf(item?.description ?: "") }
    var category by remember { mutableStateOf(item?.category ?: "") }
    var dueDateMillis by remember { mutableStateOf(item?.dueDate ?: System.currentTimeMillis()) }
    var status by remember { mutableStateOf(item?.status ?: Receivable.Status.PENDING) }
    var selLedgerId by remember {
        mutableLongStateOf(item?.ledgerId ?: forcedLedgerId ?: ledgers.firstOrNull { it.isDefault }?.id ?: ledgers.firstOrNull()?.id ?: 1L)
    }
    var ledMenu by remember { mutableStateOf(false) }
    val dueDateStr = remember(dueDateMillis) {
        formatTime(dueDateMillis, "yyyy-MM-dd")
    }
    var error by remember { mutableStateOf<String?>(null) }

    // 必填项标签：红色星号 + 加粗
    val requiredLabel: @Composable (String) -> Unit = { text ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("*", color = DangerRed, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.width(2.dp))
            Text(text, fontWeight = FontWeight.Bold)
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().embeddedImePadding().padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss) { Text("✕") }
                Text(
                    if (editing) "编辑" else "新建 ${if (defaultDirection == Receivable.Direction.RECEIVABLE) "应收款" else "应付款"}",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    modifier = Modifier.weight(1f)
                )
                if (editing) {
                    TextButton(onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) { db.receivableDao().delete(item!!.id) }
                            onSaved()
                        }
                    }) { Text("🗑 删除", color = DangerRed) }
                }
            }

            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                // 方向
                Text("类型", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    InputChip(
                        selected = direction == Receivable.Direction.RECEIVABLE,
                        onClick = { direction = Receivable.Direction.RECEIVABLE },
                        label = { Text("💰 应收款（别人给我）") }
                    )
                    InputChip(
                        selected = direction == Receivable.Direction.PAYABLE,
                        onClick = { direction = Receivable.Direction.PAYABLE },
                        label = { Text("💸 应付款（我要给）") }
                    )
                }

                Spacer(Modifier.height(12.dp))
                // 账本选择行（复用 TxLedgerRow，forcedLedgerId 非空时锁定不可切换）
                TxLedgerRow(
                    ledgers = ledgers,
                    selectedLedgerId = selLedgerId,
                    onSelect = { selLedgerId = it },
                    locked = forcedLedgerId != null
                )

                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = counterparty,
                    onValueChange = { counterparty = it; error = null },
                    label = { requiredLabel("对方（如：房东/信用卡/XX公司）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = amountText,
                    onValueChange = {
                        amountText = sanitizeAmountInput(it) ?: return@OutlinedTextField
                        error = null
                    },
                    label = { requiredLabel("金额") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(12.dp))
                Text("到期日：$dueDateStr", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("今天", "+1天", "+3天", "+7天", "+30天").forEach { label ->
                        AssistChip(
                            onClick = {
                                val cal = Calendar.getInstance()
                                when (label) {
                                    "今天" -> {}
                                    "+1天" -> cal.add(Calendar.DAY_OF_MONTH, 1)
                                    "+3天" -> cal.add(Calendar.DAY_OF_MONTH, 3)
                                    "+7天" -> cal.add(Calendar.DAY_OF_MONTH, 7)
                                    "+30天" -> cal.add(Calendar.DAY_OF_MONTH, 30)
                                }
                                dueDateMillis = cal.timeInMillis
                            },
                            label = { Text(label) }
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = category,
                    onValueChange = { category = it },
                    label = { Text("分类（可选，如：房租/信用卡/物业费）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("备注（可选）") },
                    modifier = Modifier.fillMaxWidth()
                )

                if (editing) {
                    Spacer(Modifier.height(12.dp))
                    Text("状态", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        InputChip(
                            selected = status == Receivable.Status.PENDING,
                            onClick = { status = Receivable.Status.PENDING },
                            label = { Text("⏳ 待处理") }
                        )
                        InputChip(
                            selected = status == Receivable.Status.DONE,
                            onClick = { status = Receivable.Status.DONE },
                            label = { Text("✅ 已完成") }
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
            }

            error?.let {
                Text(
                    "⚠️ $it",
                    color = DangerRed,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                )
            }

            Row(
                Modifier.fillMaxWidth().padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("取消") }
                Button(onClick = {
                    val msg = when {
                        counterparty.isBlank() -> "请填写对方（必填项）"
                        amountText.isBlank() -> "请填写金额（必填项）"
                        amountText.toDoubleOrNull() == null || amountText.toDouble() <= 0 -> "金额无效，请重新输入"
                        else -> null
                    }
                    if (msg != null) { error = msg; return@Button }

                    val amt = amountText.toDouble()
                    val itemToSave = (item ?: Receivable(
                        direction = direction,
                        counterparty = "",
                        amount = 0.0,
                        dueDate = dueDateMillis,
                        description = ""
                    )).copy(
                        direction = direction,
                        counterparty = counterparty.trim(),
                        amount = amt,
                        dueDate = dueDateMillis,
                        description = description.trim(),
                        category = category.trim(),
                        status = status,
                        ledgerId = selLedgerId
                    )

                    scope.launch {
                        withContext(Dispatchers.IO) {
                            if (editing) db.receivableDao().update(itemToSave)
                            else db.receivableDao().insert(itemToSave)
                        }
                        onSaved()
                    }
                }, modifier = Modifier.weight(1f)) { Text("保存") }
            }
        }
    }
}
