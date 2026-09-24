package com.bookkeeping.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bookkeeping.app.data.entity.Transaction
import com.bookkeeping.app.theme.ExpenseRed
import com.bookkeeping.app.theme.IncomeGreen
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** 金额汇总防浮点尾差：如 0.1+0.2=0.30000000000000004 → 0.3 */
internal fun Double.round2(): Double = Math.round(this * 100) / 100.0

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
                    "$sign${String.format("%.2f", tx.amount)}",
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
