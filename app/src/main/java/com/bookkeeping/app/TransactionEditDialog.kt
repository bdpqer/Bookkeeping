package com.bookkeeping.app

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bookkeeping.app.data.AppDatabase
import com.bookkeeping.app.data.entity.Account
import com.bookkeeping.app.data.entity.Ledger
import com.bookkeeping.app.data.entity.Transaction
import com.bookkeeping.app.theme.ExpenseRed
import com.bookkeeping.app.theme.IncomeGreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ─── 交易编辑弹窗 ────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionEditDialog(
    tx: Transaction,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
    onDeleted: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.getInstance(context) }

    var amountText by remember { mutableStateOf(tx.amount.formatAmount()) }
    var selectedType by remember { mutableStateOf(tx.type) }
    var selectedCategory by remember { mutableStateOf(tx.category) }
    var merchant by remember { mutableStateOf(tx.merchant) }
    var note by remember { mutableStateOf(tx.note ?: "") }
    var accounts by remember { mutableStateOf<List<Account>>(emptyList()) }
    var ledgers by remember { mutableStateOf<List<Ledger>>(emptyList()) }
    var selectedAccountId by remember { mutableStateOf<Long?>(tx.accountId) }
    var selectedLedgerId by remember { mutableStateOf<Long?>(tx.ledgerId) }

    val categories = categoriesFor(selectedType)

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            accounts = db.accountDao().getAll()
            ledgers = db.ledgerDao().getAll()
        }
    }

    fun saveEdit() {
        val amt = amountText.toDoubleOrNull() ?: return
        if (amt <= 0) return
        scope.launch {
            withContext(Dispatchers.IO) {
                db.transactionDao().update(
                    tx.copy(
                        amount = amt,
                        type = selectedType,
                        category = selectedCategory,
                        merchant = merchant,
                        note = note,
                        accountId = selectedAccountId,
                        ledgerId = selectedLedgerId
                    )
                )
            }
            onSaved()
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().embeddedImePadding().padding(horizontal = 16.dp)) {
            // 顶部：✕ + 编辑标题 + 删除
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) { Text("✕", fontSize = 16.sp) }
                Spacer(Modifier.width(8.dp))
                Text("编辑交易", fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            // 软删除：进回收站保留 30 天，凭证暂不删（彻底删除时再清理）
                            db.transactionDao().softDelete(tx.id, System.currentTimeMillis())
                        }
                        onDeleted()
                    }
                }) { Text("🗑 删除", color = Color(0xFFE53935)) }
            }

            // 类型 Tab
            val typeLabels = listOf(
                Transaction.Type.EXPENSE to "支出",
                Transaction.Type.INCOME to "收入",
                Transaction.Type.TRANSFER to "转账"
            )
            val typeIndex = typeLabels.indexOfFirst { it.first == selectedType }.coerceAtLeast(0)
            TabRow(selectedTabIndex = typeIndex) {
                typeLabels.forEachIndexed { i, (type, label) ->
                    Tab(
                        selected = selectedType == type,
                        onClick = {
                            selectedType = type
                            val newCats = categoriesFor(type)
                            if (!newCats.contains(selectedCategory)) selectedCategory = newCats.first()
                        },
                        text = { Text(label, fontSize = 15.sp) }
                    )
                }
            }

            // 分类 + 金额行
            val amountColor = when (selectedType) {
                Transaction.Type.EXPENSE -> ExpenseRed
                Transaction.Type.INCOME -> IncomeGreen
                Transaction.Type.TRANSFER -> MaterialTheme.colorScheme.primary
            }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(40.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
                    contentAlignment = Alignment.Center
                ) { Text(categoryEmoji(selectedCategory), fontSize = 18.sp) }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(selectedCategory, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Text(
                        when (selectedType) { Transaction.Type.EXPENSE -> "支出"; Transaction.Type.INCOME -> "收入"; Transaction.Type.TRANSFER -> "转账" },
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.weight(1f))
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { input ->
                        val filtered = input.filter { it.isDigit() || it == '.' }
                        val parts = filtered.split('.')
                        val valid = when {
                            filtered.count { it == '.' } > 1 -> return@OutlinedTextField
                            parts.size == 2 && parts[1].length > 2 -> parts[0] + "." + parts[1].take(2)
                            parts[0].length > 9 -> parts[0].take(9) + (if (parts.size == 2) "." + parts[1].take(2) else "")
                            else -> filtered
                        }
                        amountText = valid
                    },
                    label = { Text("金额") },
                    singleLine = true,
                    textStyle = TextStyle(
                        fontSize = 24.sp, fontWeight = FontWeight.Bold, color = amountColor
                    ),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Done
                    ),
                    modifier = Modifier.widthIn(min = 140.dp)
                )
            }

            // 可滚动表单：分类网格 + 账户 + 账本 + 备注 + 凭证
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                categories.chunked(4).forEach { rowCats ->
                    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowCats.forEach { cat ->
                            val isSel = cat == selectedCategory
                            Box(
                                Modifier.weight(1f).height(38.dp).background(
                                    if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                    RoundedCornerShape(19.dp)
                                ).clickable { selectedCategory = cat },
                                contentAlignment = Alignment.Center
                            ) { Text(cat, fontSize = 12.sp, maxLines = 1, color = if (isSel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface) }
                        }
                        repeat(4 - rowCats.size) { Spacer(Modifier.weight(1f)) }
                    }
                }

                Spacer(Modifier.height(8.dp))

                if (accounts.isNotEmpty()) {
                    var accMenu by remember { mutableStateOf(false) }
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("付款账户", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.weight(1f))
                        Box {
                            Row(Modifier.clickable { accMenu = true }, verticalAlignment = Alignment.CenterVertically) {
                                Text(accounts.firstOrNull { it.id == selectedAccountId }?.let { "${it.icon} ${it.name}" } ?: "不关联", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                Text(" ▾", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            DropdownMenu(expanded = accMenu, onDismissRequest = { accMenu = false }) {
                                DropdownMenuItem(text = { Text("不关联账户") }, onClick = { selectedAccountId = null; accMenu = false })
                                accounts.forEach { acc ->
                                    DropdownMenuItem(text = { Text("${acc.icon} ${acc.name}") }, onClick = { selectedAccountId = acc.id; accMenu = false })
                                }
                            }
                        }
                    }
                }

                if (ledgers.isNotEmpty()) {
                    var ledMenu by remember { mutableStateOf(false) }
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("记账账本", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.weight(1f))
                        Box {
                            Row(Modifier.clickable { ledMenu = true }, verticalAlignment = Alignment.CenterVertically) {
                                Text(ledgers.firstOrNull { it.id == selectedLedgerId }?.let { "${it.icon} ${it.name}" } ?: "不关联", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                Text(" ▾", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            DropdownMenu(expanded = ledMenu, onDismissRequest = { ledMenu = false }) {
                                DropdownMenuItem(text = { Text("不关联账本") }, onClick = { selectedLedgerId = null; ledMenu = false })
                                ledgers.forEach { led ->
                                    DropdownMenuItem(text = { Text("${led.icon} ${led.name}") }, onClick = { selectedLedgerId = led.id; ledMenu = false })
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = merchant, onValueChange = { merchant = it },
                    label = { Text("商户/对方（可选）") }, singleLine = true,
                    textStyle = TextStyle(fontSize = 14.sp),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = note, onValueChange = { note = it },
                    label = { Text("备注（可选）") }, singleLine = true,
                    textStyle = TextStyle(fontSize = 14.sp),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                com.bookkeeping.app.ui.ReceiptSection(editTxId = tx.id)
                Spacer(Modifier.height(10.dp))
            }

            // ── 底部按钮 ──
            Row(
                Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                ) { Text("取消") }
                Button(
                    onClick = { saveEdit() },
                    modifier = Modifier.weight(1f)
                ) { Text("保存") }
            }
        }
    }
}
