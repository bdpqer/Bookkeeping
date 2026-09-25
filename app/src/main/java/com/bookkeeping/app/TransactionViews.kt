package com.bookkeeping.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bookkeeping.app.data.entity.Ledger
import com.bookkeeping.app.data.entity.Transaction
import com.bookkeeping.app.theme.ExpenseRed
import com.bookkeeping.app.theme.IncomeGreen
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** 金额汇总防浮点尾差：如 0.1+0.2=0.30000000000000004 → 0.3 */
internal fun Double.round2(): Double = Math.round(this * 100) / 100.0

/** 金额显示统一两位小数 */
internal fun Double.formatAmount(): String = String.format("%.2f", this)

// ─── 单条交易 ─────────────────────────────────────────────

@Composable
internal fun TransactionItem(
    tx: Transaction,
    onClick: () -> Unit = {}
) {
    val context = LocalContext.current
    val typeColor = when (tx.type) {
        Transaction.Type.EXPENSE -> ExpenseRed
        Transaction.Type.INCOME -> IncomeGreen
        Transaction.Type.TRANSFER -> Color(0xFFFF9800)
    }
    val sign = when (tx.type) {
        Transaction.Type.EXPENSE -> "-"
        Transaction.Type.INCOME -> "+"
        Transaction.Type.TRANSFER -> "→"
    }
    val emoji = categoryEmoji(tx.category)

    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(36.dp).background(
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
                        tx.category,
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (com.bookkeeping.app.ui.ReceiptStore.hasReceipt(context, tx.id)) {
                        Spacer(Modifier.width(4.dp))
                        Text("📷", fontSize = 10.sp)
                    }
                }
                Text(
                    tx.source,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "$sign${tx.amount.formatAmount()}",
                    fontWeight = FontWeight.Bold,
                    color = typeColor,
                    fontSize = 16.sp
                )
                Text(
                    formatTime(tx.occurredAt),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

internal fun categoryEmoji(category: String): String = when {
    category.contains("餐饮") -> "🍜"
    category.contains("饮品") || category.contains("咖啡") -> "☕"
    category.contains("交通") -> "🚗"
    category.contains("购物") -> "🛒"
    category.contains("居住") -> "🏠"
    category.contains("水电") || category.contains("话费") -> "💡"
    category.contains("娱乐") -> "🎮"
    category.contains("医疗") -> "💊"
    category.contains("学习") -> "📚"
    category.contains("出差") || category.contains("差旅") -> "✈️"
    category.contains("打车") -> "🚕"
    category.contains("工资") -> "💰"
    category.contains("红包") -> "🧧"
    category.contains("还款") -> "💳"
    category.contains("借出") -> "🤝"
    category.contains("报销") -> "🧾"
    category.contains("理财") -> "📈"
    category.contains("转账") -> "🔄"
    category.contains("支出") || category.contains("消费") -> "💸"
    category.contains("收入") -> "💵"
    else -> "📝"
}

/** 按交易类型给出可选分类列表 */
internal fun categoriesFor(type: Transaction.Type): List<String> = when (type) {
    Transaction.Type.EXPENSE -> listOf(
        "餐饮/外卖", "餐饮/饮品", "交通", "购物", "居住",
        "娱乐", "医疗", "人情", "红包", "借出", "理财", "其他"
    )
    Transaction.Type.INCOME -> listOf(
        "工资", "红包", "人情", "还款", "报销", "理财", "其他"
    )
    Transaction.Type.TRANSFER -> listOf("转账")
}

private fun formatTime(ts: Long): String {
    val cal = Calendar.getInstance()
    val txCal = Calendar.getInstance().apply { timeInMillis = ts }
    val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    return if (cal.get(Calendar.DAY_OF_YEAR) == txCal.get(Calendar.DAY_OF_YEAR)) {
        fmt.format(txCal.time)
    } else {
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(txCal.time)
    }
}

// ─── 顶栏账本下拉标题 ─────────────────────────────────────────

/** 列表空状态占位：emoji + 提示文案（可带副文案），默认撑满父容器并居中 */
@Composable
internal fun EmptyState(
    emoji: String,
    message: String,
    modifier: Modifier = Modifier,
    subMessage: String? = null
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(emoji, fontSize = 48.sp)
            Spacer(Modifier.height(12.dp))
            Text(
                message,
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            if (subMessage != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    subMessage,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/** 顶栏标题：账本下拉列表，默认选中当前账本。id=0 表示「全部账本」 */
@Composable
internal fun LedgerDropdownTitle(
    ledgers: List<Ledger>,
    selectedLedgerId: Long,
    onSelect: (Long) -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    val current = if (selectedLedgerId == 0L) null
                  else ledgers.firstOrNull { it.id == selectedLedgerId }
    Box {
        Row(
            Modifier.clickable { menu = true },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                current?.let { "${it.icon} ${it.name}" } ?: "📚 全部账本",
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(" ▾", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text("📚 全部账本") },
                onClick = { menu = false; onSelect(0L) }
            )
            ledgers.forEach { led ->
                DropdownMenuItem(
                    text = { Text("${led.icon} ${led.name}") },
                    onClick = { menu = false; onSelect(led.id) }
                )
            }
        }
    }
}

// ─── DatePicker 时区换算 ──────────────────────────────────

/** 本地零点毫秒 → UTC 零点毫秒（DatePicker 用 UTC） */
internal fun localDayToUtc(localDayStart: Long): Long {
    val c = Calendar.getInstance().apply { timeInMillis = localDayStart }
    return Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
        clear()
        set(c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH))
    }.timeInMillis
}

/** UTC 零点毫秒 → 本地零点毫秒（DatePicker 回显） */
internal fun utcToLocalDayStart(utc: Long): Long {
    val c = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply { timeInMillis = utc }
    return Calendar.getInstance().apply {
        clear()
        set(c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH))
    }.timeInMillis
}
