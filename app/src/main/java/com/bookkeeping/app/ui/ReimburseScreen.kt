package com.bookkeeping.app.ui
import com.bookkeeping.app.applyBalance
import androidx.room.withTransaction

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bookkeeping.app.DetailTopBar
import com.bookkeeping.app.EmptyState
import com.bookkeeping.app.LedgerDropdownTitle
import com.bookkeeping.app.categoryEmoji
import com.bookkeeping.app.data.AppDatabase
import com.bookkeeping.app.data.entity.Ledger
import com.bookkeeping.app.data.entity.Transaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.*
import com.bookkeeping.app.formatAmount
import com.bookkeeping.app.formatTime
import com.bookkeeping.app.theme.LentOrange
import com.bookkeeping.app.theme.SuccessGreen
import com.bookkeeping.app.theme.TransferOrange



/** 报销管理页 —— 待报销 / 已报销 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReimburseScreen(ledgers: List<Ledger>, initialLedgerId: Long, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.getInstance(context) }
    var tab by remember { mutableStateOf(0) } // 0=待报销 1=已报销
    var selLedgerId by remember { mutableLongStateOf(initialLedgerId) }
    var pendingList by remember { mutableStateOf<List<Transaction>>(emptyList()) }
    var doneList by remember { mutableStateOf<List<Transaction>>(emptyList()) }
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var expandedMonths by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showAddDialog by remember { mutableStateOf(false) }
    var editingTx by remember { mutableStateOf<Transaction?>(null) }

    fun refresh() {
        scope.launch {
            pendingList = withContext(Dispatchers.IO) {
                db.transactionDao().getReimburseByStatus("PENDING")
                    .filter { selLedgerId == 0L || it.ledgerId == selLedgerId }
            }
            doneList = withContext(Dispatchers.IO) {
                db.transactionDao().getReimburseByStatus("DONE")
                    .filter { selLedgerId == 0L || it.ledgerId == selLedgerId }
            }
            val allMonths = (pendingList + doneList).map { formatTime(it.occurredAt, "yyyy-MM") }.distinct()
            if (expandedMonths.isEmpty() && allMonths.isNotEmpty()) {
                expandedMonths = setOf(allMonths.first())
            }
        }
    }

    LaunchedEffect(selLedgerId) { selectedIds = emptySet(); refresh() }
    LaunchedEffect(tab) { selectedIds = emptySet() }

    val currentList = if (tab == 0) pendingList else doneList
    // 「全选」只能作用于用户看得见的记录：折叠月份下的交易没有 checkbox，
    // 一起选上会让用户在不知情的情况下批量报销屏幕上看不到的数据。
    // 已报销 tab（tab=1）没有勾选框，可见集合为空，全选自然不可用
    val visibleIds = if (tab == 0) {
        pendingList.filter { formatTime(it.occurredAt, "yyyy-MM") in expandedMonths }
            .map { it.id }.toSet()
    } else emptySet()
    val isAllSelected = visibleIds.isNotEmpty() && visibleIds.all { it in selectedIds }
    val selectedTotal = currentList.filter { it.id in selectedIds }.sumOf { it.amount }

    Scaffold(
        // 透明容器：透出全局自定义背景图
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
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
                    IconButton(onClick = { showAddDialog = true }) {
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
                    text = {
                        Text(
                            "待报销 (${pendingList.size})",
                            color = if (tab == 0) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (tab == 0) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                )
                Tab(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    text = {
                        Text(
                            "已报销 (${doneList.size})",
                            color = if (tab == 1) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (tab == 1) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                )
            }

            if (currentList.isEmpty()) {
                EmptyState(
                    if (tab == 0) "🧾" else "✅",
                    if (tab == 0) "还没有待报销的支出\n点右上角环形加号「新增」"
                    else "暂无已报销记录\n点右上角环形加号「新增」"
                )
            } else {
                val grouped = remember(currentList) { currentList.groupBy { formatTime(it.occurredAt, "yyyy-MM") } }
                    .toSortedMap(compareByDescending<String> { it })

                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    grouped.forEach { (month, txsInMonth) ->
                        val monthTotal = txsInMonth.sumOf { it.amount }
                        val isExpanded = expandedMonths.contains(month)
                        item {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .background(
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                        RoundedCornerShape(8.dp)
                                    )
                                    .clickable {
                                        expandedMonths = if (isExpanded) {
                                            expandedMonths - month
                                        } else {
                                            expandedMonths + month
                                        }
                                    }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    month,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "(${txsInMonth.size}笔)",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.weight(1f))
                                Text(
                                    "-¥${monthTotal.formatAmount()}",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (tab == 0) TransferOrange else SuccessGreen
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(if (isExpanded) "▾" else "▸", fontSize = 12.sp)
                            }
                        }
                        if (isExpanded) {
                            items(txsInMonth, key = { it.id }) { tx ->
                                ReimburseItemRow(
                                    tx = tx,
                                    isPending = tab == 0,
                                    selected = selectedIds.contains(tx.id),
                                    onToggleSelect = {
                                        selectedIds = if (selectedIds.contains(tx.id)) {
                                            selectedIds - tx.id
                                        } else {
                                            selectedIds + tx.id
                                        }
                                    },
                                    onClick = { editingTx = tx },
                                    onMarkDone = {
                                        scope.launch {
                                            withContext(Dispatchers.IO) {
                                                db.transactionDao().updateReimburseStatus(tx.id, "DONE")
                                            }
                                            refresh()
                                        }
                                    },
                                    onCancelDone = {
                                        scope.launch {
                                            withContext(Dispatchers.IO) {
                                                db.transactionDao().updateReimburseStatus(tx.id, "PENDING")
                                            }
                                            refresh()
                                        }
                                    },
                                    onDelete = {
                                        scope.launch {
                                            withContext(Dispatchers.IO) {
                                                db.transactionDao().softDelete(tx.id, System.currentTimeMillis())
                                            }
                                            com.bookkeeping.app.BookkeepingApp.notifyTransactionChanged()
                                            refresh()
                                        }
                                    }
                                )
                            }
                        }
                    }
                    if (tab == 0) {
                        item {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(top = 6.dp)
                                    .background(
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                        RoundedCornerShape(10.dp)
                                    )
                                    .padding(horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = isAllSelected,
                                    onCheckedChange = { checked ->
                                        selectedIds = if (checked) visibleIds else emptySet()
                                    }
                                )
                                Text("全选", fontSize = 14.sp)
                                Spacer(Modifier.weight(1f))
                                Text(
                                    "合计 ¥${selectedTotal.formatAmount()}",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (selectedIds.isNotEmpty()) LentOrange
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.width(12.dp))
                                Button(
                                    onClick = {
                                        scope.launch {
                                            val ids = selectedIds.toList()
                                            if (ids.isEmpty()) return@launch
                                            withContext(Dispatchers.IO) {
                                                val txs = currentList.filter { it.id in ids }
                                                // ⚠️「生成入账 + 标记已报销」必须在同一事务里：
                                                // 早先两者分离，中途异常时原件仍未标记完成，
                                                // 用户再点一次就会对同一批生成第二轮入账（双倍报销）
                                                db.withTransaction {
                                                    // 1. 对每笔选中的待报销交易生成方向相反的入账（报销款回来）
                                                    for (tx in txs) {
                                                        val opposite = Transaction(
                                                        amount = tx.amount,
                                                        type = if (tx.type == Transaction.Type.EXPENSE)
                                                            Transaction.Type.INCOME
                                                        else Transaction.Type.EXPENSE,
                                                        category = "报销",
                                                        merchant = tx.merchant.ifBlank { tx.category } + " 报销",
                                                        source = "报销",
                                                        accountId = tx.accountId,
                                                        ledgerId = tx.ledgerId,
                                                        note = buildString {
                                                            append(
                                                                "报销入账（冲抵${if (tx.type == Transaction.Type.EXPENSE) "支出" else "收入"} ¥${tx.amount.formatAmount()}）"
                                                            )
                                                            if (tx.note.isNotBlank()) append("｜${tx.note}")
                                                        },
                                                        rawText = "[报销] ${tx.merchant.ifBlank { tx.category }} ${tx.amount}",
                                                        isManual = false,
                                                        confirmed = true,
                                                        confidence = Transaction.Confidence.HIGH,
                                                        occurredAt = System.currentTimeMillis(),
                                                        reimburseStatus = null
                                                    )
                                                    db.transactionDao().insert(opposite)
                                                    applyBalance(db, opposite)
                                                }
                                                // 2. 原交易标记为已报销
                                                db.transactionDao().batchUpdateReimburseStatus(ids, "DONE")
                                                }
                                            }
                                            selectedIds = emptySet()
                                            refresh()
                                        }
                                    },
                                    enabled = selectedIds.isNotEmpty(),
                                    shape = RoundedCornerShape(24.dp),
                                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                                ) {
                                    Text("✓ 报销 ${if (selectedIds.isNotEmpty()) selectedIds.size else ""}")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        com.bookkeeping.app.ManualAddDialog(
            onDismiss = {
                showAddDialog = false
                refresh()
            },
            initialType = Transaction.Type.EXPENSE,
            initialCategory = "报销",
            fixedCategory = false,
            onCapture = null,
            reimburseStatus = if (tab == 0) "PENDING" else "DONE",
            forcedLedgerId = if (selLedgerId == 0L) null else selLedgerId,
            embedded = true
        )
    }

    editingTx?.let { tx ->
        com.bookkeeping.app.TransactionEditDialog(
            tx = tx,
            onDismiss = { editingTx = null },
            onSaved = {
                refresh()
                editingTx = null
            },
            onDeleted = {
                refresh()
                editingTx = null
            }
        )
    }
}

@Composable
private fun ReimburseItemRow(
    tx: Transaction,
    isPending: Boolean,
    selected: Boolean,
    onToggleSelect: () -> Unit,
    onClick: () -> Unit,
    onMarkDone: () -> Unit,
    onCancelDone: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.getInstance(context) }
    var accountName by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(tx.accountId) {
        accountName = tx.accountId?.let {
            withContext(Dispatchers.IO) { db.accountDao().getById(it)?.name }
        }
    }

    val emoji = categoryEmoji(tx.category)
    val dateStr = formatTime(tx.occurredAt, "yyyy-MM-dd HH:mm")
    val amountColor = if (isPending) TransferOrange else SuccessGreen
    val accountSuffix = accountName?.let { " · $it" } ?: ""

    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isPending) {
                    Checkbox(
                        checked = selected,
                        onCheckedChange = { onToggleSelect() },
                        modifier = Modifier.padding(end = 2.dp)
                    )
                } else {
                    Text(
                        "✅",
                        fontSize = 20.sp,
                        modifier = Modifier.padding(end = 4.dp)
                    )
                }
                Box(
                    Modifier
                        .size(36.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(9.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(emoji, fontSize = 18.sp)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            tx.merchant.ifBlank { tx.category },
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            if (isPending) "待报销" else "已报销",
                            fontSize = 10.sp,
                            color = amountColor,
                            modifier = Modifier
                                .background(
                                    amountColor.copy(alpha = 0.1f),
                                    RoundedCornerShape(4.dp)
                                )
                                .padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }
                    Text(
                        "$dateStr$accountSuffix",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (tx.note.isNotBlank()) {
                        Text(
                            tx.note,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Text(
                    "-¥${tx.amount.formatAmount()}",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = amountColor
                )
            }
        }
    }
}
