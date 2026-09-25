package com.bookkeeping.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bookkeeping.app.EmptyState
import com.bookkeeping.app.data.AppDatabase
import com.bookkeeping.app.data.entity.Transaction
import com.bookkeeping.app.theme.ExpenseRed
import com.bookkeeping.app.theme.IncomeGreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.bookkeeping.app.formatAmount

private val deletedTimeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
private val occurredTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

/** 保留 30 天后自动清除 */
private const val RETENTION_DAYS = 30L

/** 回收站：软删除的交易在此保留 30 天，可恢复或彻底删除 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecycleBinScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.getInstance(context) }
    var list by remember { mutableStateOf<List<Transaction>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var purgeTarget by remember { mutableStateOf<Transaction?>(null) }
    var showClearAll by remember { mutableStateOf(false) }

    fun reload() {
        scope.launch {
            list = withContext(Dispatchers.IO) {
                // 先自动清理超过保留期的记录
                db.transactionDao().purgeOlderThan(System.currentTimeMillis() - RETENTION_DAYS * 86_400_000L)
                db.transactionDao().getDeleted()
            }
            loaded = true
        }
    }

    LaunchedEffect(Unit) { reload() }

    fun restore(tx: Transaction) {
        scope.launch {
            withContext(Dispatchers.IO) { db.transactionDao().restore(tx.id) }
            reload()
        }
    }

    fun purge(tx: Transaction) {
        scope.launch {
            withContext(Dispatchers.IO) {
                db.transactionDao().purge(tx.id)
                ReceiptStore.deleteReceipt(context, tx.id)
            }
            reload()
        }
    }

    fun purgeAll() {
        scope.launch {
            withContext(Dispatchers.IO) {
                list.forEach { ReceiptStore.deleteReceipt(context, it.id) }
                db.transactionDao().purgeAll()
            }
            reload()
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                title = { Text("回收站", fontWeight = FontWeight.Bold) },
                actions = {
                    if (list.isNotEmpty()) {
                        TextButton(onClick = { showClearAll = true }) {
                            Text("清空回收站", color = ExpenseRed)
                        }
                    }
                },
                windowInsets = WindowInsets(0, 0, 0, 0)
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text(
                "删除的记录在这里保留 $RETENTION_DAYS 天，之后自动清除",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )

            if (loaded && list.isEmpty()) {
                EmptyState("🗑", "回收站是空的")
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 12.dp, end = 12.dp, bottom = 16.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(list, key = { it.id }) { tx ->
                        DeletedTxItem(
                            tx = tx,
                            onRestore = { restore(tx) },
                            onPurge = { purgeTarget = tx }
                        )
                    }
                }
            }
        }
    }

    purgeTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { purgeTarget = null },
            title = { Text("彻底删除", fontWeight = FontWeight.Bold) },
            text = { Text("该记录将从回收站中永久删除，无法恢复。确定删除吗？") },
            confirmButton = {
                TextButton(onClick = { purgeTarget = null; purge(target) }) {
                    Text("彻底删除", color = ExpenseRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { purgeTarget = null }) { Text("取消") }
            }
        )
    }

    if (showClearAll) {
        AlertDialog(
            onDismissRequest = { showClearAll = false },
            title = { Text("清空回收站", fontWeight = FontWeight.Bold) },
            text = { Text("共 ${list.size} 条记录将被永久删除，无法恢复。确定清空吗？") },
            confirmButton = {
                TextButton(onClick = { showClearAll = false; purgeAll() }) {
                    Text("全部删除", color = ExpenseRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearAll = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun DeletedTxItem(
    tx: Transaction,
    onRestore: () -> Unit,
    onPurge: () -> Unit
) {
    val isExpense = tx.type == Transaction.Type.EXPENSE
    val isTransfer = tx.type == Transaction.Type.TRANSFER
    val amountColor = when {
        isTransfer -> Color(0xFF2E5AAC)
        isExpense -> ExpenseRed
        else -> IncomeGreen
    }
    val typeLabel = when {
        isTransfer -> "转账"
        isExpense -> "支出"
        else -> "收入"
    }

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(emojiFor(tx.category), fontSize = 24.sp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        tx.merchant.ifBlank { tx.category },
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(Modifier.width(6.dp))
                    Surface(
                        color = amountColor.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            typeLabel,
                            fontSize = 10.sp,
                            color = amountColor,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }
                }
                Text(
                    "原记录 ${occurredTimeFormat.format(Date(tx.occurredAt))} · 删除于 ${deletedTimeFormat.format(Date(tx.deletedAt))}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (tx.note.isNotBlank()) {
                    Text(
                        tx.note,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                (if (isExpense || isTransfer) "-" else "+") + tx.amount.formatAmount(),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = amountColor
            )
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "恢复",
                    fontSize = 13.sp,
                    color = Color(0xFF2E5AAC),
                    modifier = Modifier
                        .clickable(onClick = onRestore)
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                )
                Text(
                    "删除",
                    fontSize = 13.sp,
                    color = ExpenseRed,
                    modifier = Modifier
                        .clickable(onClick = onPurge)
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                )
            }
        }
    }
}

private fun emojiFor(category: String): String = when {
    category.contains("餐饮") -> "🍜"
    category.contains("饮品") || category.contains("咖啡") -> "☕"
    category.contains("交通") -> "🚗"
    category.contains("购物") -> "🛒"
    category.contains("工资") -> "💰"
    category.contains("红包") -> "🧧"
    category.contains("还款") -> "💳"
    category.contains("娱乐") -> "🎮"
    category.contains("医疗") -> "💊"
    category.contains("居住") || category.contains("房租") -> "🏠"
    category.contains("报销") -> "🧾"
    else -> "📝"
}
