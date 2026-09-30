package com.bookkeeping.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bookkeeping.app.data.entity.Account
import com.bookkeeping.app.data.entity.Ledger
import com.bookkeeping.app.data.entity.Transaction
import com.bookkeeping.app.theme.ExpenseRed
import com.bookkeeping.app.theme.IncomeGreen

/**
 * 「记一笔」与「编辑交易」两个弹窗共用的表单字段组件。
 * 两者视觉细节（间距/圆角/配色）不同，故样式以参数传入，逻辑只保留一份。
 */

/** 按交易类型给出金额主色：支出红 / 收入绿 / 转账取主题主色 */
@Composable
internal fun amountColorFor(type: Transaction.Type): Color = when (type) {
    Transaction.Type.EXPENSE -> ExpenseRed
    Transaction.Type.INCOME -> IncomeGreen
    Transaction.Type.TRANSFER -> MaterialTheme.colorScheme.primary
}

/**
 * 分类选择网格：每行 [columns] 个，末行不足时补空位保持等宽。
 * 选中 / 未选中的容器色与文字色由调用方给出，两个弹窗的观感差异由此保留。
 */
@Composable
internal fun TxCategoryGrid(
    categories: List<String>,
    selectedCategory: String,
    onSelect: (String) -> Unit,
    selectedContainer: Color,
    unselectedContainer: Color,
    selectedContent: Color,
    unselectedContent: Color,
    modifier: Modifier = Modifier,
    columns: Int = 4,
    itemHeight: Dp = 38.dp,
    hSpacing: Dp = 8.dp,
    vSpacing: Dp = 8.dp,
    corner: Dp = 12.dp,
    fontSize: TextUnit = 13.sp
) {
    categories.chunked(columns).forEach { rowCats ->
        Row(
            modifier.fillMaxWidth().padding(bottom = vSpacing),
            horizontalArrangement = Arrangement.spacedBy(hSpacing)
        ) {
            rowCats.forEach { cat ->
                val isSel = cat == selectedCategory
                Box(
                    Modifier
                        .weight(1f)
                        .height(itemHeight)
                        .background(
                            if (isSel) selectedContainer else unselectedContainer,
                            RoundedCornerShape(corner)
                        )
                        .clickable { onSelect(cat) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        cat,
                        fontSize = fontSize,
                        maxLines = 1,
                        color = if (isSel) selectedContent else unselectedContent,
                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
            repeat(columns - rowCats.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

/** 账户下拉行：[label] 随交易类型变化（付款/收款/转出账户） */
@Composable
internal fun TxAccountRow(
    accounts: List<Account>,
    selectedAccountId: Long?,
    label: String,
    onSelect: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    var menu by remember { mutableStateOf(false) }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Box {
            Row(Modifier.clickable { menu = true }, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    accounts.firstOrNull { it.id == selectedAccountId }
                        ?.let { "${it.icon} ${it.name}" } ?: "",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(" ▾", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                accounts.forEach { acc ->
                    DropdownMenuItem(
                        text = { Text("${acc.icon} ${acc.name}") },
                        onClick = { onSelect(acc.id); menu = false }
                    )
                }
            }
        }
    }
}

/** 账本下拉行；[locked] = true 时锁定为指定账本（由调用方强制指定，不可切换） */
@Composable
internal fun TxLedgerRow(
    ledgers: List<Ledger>,
    selectedLedgerId: Long?,
    onSelect: (Long) -> Unit,
    modifier: Modifier = Modifier,
    locked: Boolean = false
) {
    var menu by remember { mutableStateOf(false) }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("记账账本", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Box {
            Row(
                Modifier.then(if (locked) Modifier else Modifier.clickable { menu = true }),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    ledgers.firstOrNull { it.id == selectedLedgerId }
                        ?.let { "${it.icon} ${it.name}" } ?: "",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
                if (!locked) {
                    Text(" ▾", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                ledgers.forEach { led ->
                    DropdownMenuItem(
                        text = { Text("${led.icon} ${led.name}") },
                        onClick = { onSelect(led.id); menu = false }
                    )
                }
            }
        }
    }
}
