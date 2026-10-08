package com.bookkeeping.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.bookkeeping.app.theme.drawerGradientBackground

// ─── 交易编辑弹窗 ────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionEditDialog(
    tx: Transaction,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
    onDeleted: () -> Unit,
    /**
     * true 表示已处于外层 Scaffold 的 padding 区内（二级页面里调起）：
     * 不重复铺渐变、不加系统栏 inset，键盘 padding 只取 ime 与底部距离的差值。
     */
    embedded: Boolean = false
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

    val categories = remember(selectedType) { categoriesFor(selectedType) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            accounts = db.accountDao().getAll()
            ledgers = db.ledgerDao().getAll()
            // 自动记账的交易可能未关联账户/账本，或 id 已失效：默认选中默认账户/默认账本
            if (selectedAccountId == null || accounts.none { it.id == selectedAccountId }) {
                selectedAccountId = accounts.firstOrNull()?.id
            }
            if (selectedLedgerId == null || ledgers.none { it.id == selectedLedgerId }) {
                selectedLedgerId = ledgers.firstOrNull { it.isDefault }?.id ?: ledgers.firstOrNull()?.id
            }
        }
    }

    fun saveEdit() {
        val amt = amountText.toDoubleOrNull() ?: return
        if (amt <= 0) return
        scope.launch {
            withContext(Dispatchers.IO) {
                val updated = tx.copy(
                    amount = amt,
                    type = selectedType,
                    category = selectedCategory,
                    merchant = merchant,
                    note = note,
                    accountId = selectedAccountId,
                    ledgerId = selectedLedgerId
                )
                db.transactionDao().update(updated)
                // 账户余额要跟着改：先冲回旧值，再计入新值
                // （早先这里只 update 不动余额，改金额/改收支方向/换账户后余额永久失真）
                reapplyBalance(db, tx, updated)
            }
            onSaved()
        }
    }

    val amountColor = amountColorFor(selectedType)
    // 渐变底（深蓝→青→金）上统一用白色：默认的次要文字色（灰）压在蓝段几乎看不见
    val onGradient = Color.White
    // 删除按钮：DangerRed 在深蓝段偏暗，用提亮一档的红保证可读
    val deleteColor = Color(0xFFFF8A80)

    fun deleteTx() {
        scope.launch {
            withContext(Dispatchers.IO) {
                // 软删除：进回收站保留 30 天，凭证暂不删（彻底删除时再清理）
                db.transactionDao().softDelete(tx.id, System.currentTimeMillis())
                // 进回收站就该把这笔对余额的影响冲回来，否则余额只减不增
                revertBalance(db, tx)
            }
            onDeleted()
        }
    }

    val typeLabels = listOf(
        Transaction.Type.EXPENSE to "支出",
        Transaction.Type.INCOME to "收入",
        Transaction.Type.TRANSFER to "转账"
    )
    val typeIndex = typeLabels.indexOfFirst { it.first == selectedType }.coerceAtLeast(0)

    // 表单主体（独立全屏 / 内嵌两种容器共用）
    val body: @Composable (Modifier) -> Unit = { outerModifier ->
        Column(
            outerModifier
                .fillMaxSize()
                .then(
                    if (embedded) Modifier.embeddedImePadding()
                    else Modifier.imePadding()
                )
        ) {
            // 内嵌时外层 Scaffold 已有返回键，这里只补标题 + 删除
            if (embedded) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "编辑交易",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = onGradient,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { deleteTx() }) { Text("🗑 删除", color = deleteColor) }
                }
            }

            // 类型 Tab
            TabRow(
                selectedTabIndex = typeIndex,
                containerColor = Color.Transparent,
                contentColor = androidx.compose.ui.graphics.Color.White,
                indicator = { tabPositions ->
                    if (typeIndex < tabPositions.size) {
                        TabRowDefaults.SecondaryIndicator(
                            Modifier.tabIndicatorOffset(tabPositions[typeIndex]),
                            color = amountColor
                        )
                    }
                }
            ) {
                typeLabels.forEach { (type, label) ->
                    Tab(
                        selected = selectedType == type,
                        onClick = {
                            selectedType = type
                            val newCats = categoriesFor(type)
                            if (!newCats.contains(selectedCategory)) selectedCategory = newCats.first()
                        },
                        text = {
                            Text(
                                label,
                                fontSize = 15.sp,
                                color = if (selectedType == type) amountColor else onGradient
                            )
                        }
                    )
                }
            }

            // 分类 + 金额行
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(40.dp)
                        .background(onGradient.copy(alpha = 0.22f), CircleShape),
                    contentAlignment = Alignment.Center
                ) { Text(categoryEmoji(selectedCategory), fontSize = 18.sp) }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        selectedCategory,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = onGradient
                    )
                    Text(
                        when (selectedType) { Transaction.Type.EXPENSE -> "支出"; Transaction.Type.INCOME -> "收入"; Transaction.Type.TRANSFER -> "转账" },
                        fontSize = 11.sp,
                        color = onGradient.copy(alpha = 0.85f)
                    )
                }
                Spacer(Modifier.weight(1f))
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { input ->
                        amountText = sanitizeAmountInput(input) ?: return@OutlinedTextField
                    },
                    label = { Text("金额") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    textStyle = TextStyle(
                        fontSize = 24.sp, fontWeight = FontWeight.Bold, color = amountColor
                    ),
                    colors = outlinedOnGradient(onGradient),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Done
                    ),
                    modifier = Modifier.widthIn(min = 140.dp)
                )
            }

            // 可滚动表单：分类网格 + 账户 + 账本 + 备注 + 凭证
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
            ) {
                TxCategoryGrid(
                    categories = categories,
                    selectedCategory = selectedCategory,
                    onSelect = { selectedCategory = it },
                    // 选中 = 实心白底 + 类型色字；未选中 = 半透明白底 + 白字
                    selectedContainer = onGradient,
                    unselectedContainer = onGradient.copy(alpha = 0.22f),
                    selectedContent = amountColor,
                    unselectedContent = onGradient,
                    itemHeight = 38.dp,
                    hSpacing = 8.dp,
                    vSpacing = 8.dp,
                    corner = 19.dp,
                    fontSize = 12.sp
                )

                Spacer(Modifier.height(8.dp))

                // 账户 / 账本卡片：容器色取主题色槽（Theme 已按「卡片背景透明度」乘过 alpha）
                if (accounts.isNotEmpty() || ledgers.isNotEmpty()) {
                    Card(
                        Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(Modifier.padding(horizontal = 14.dp)) {
                            if (accounts.isNotEmpty()) {
                                TxAccountRow(
                                    accounts = accounts,
                                    selectedAccountId = selectedAccountId,
                                    label = "付款账户",
                                    onSelect = { selectedAccountId = it },
                                    modifier = Modifier.padding(vertical = 12.dp),
                                    contentColor = onGradient
                                )
                                if (ledgers.isNotEmpty()) {
                                    HorizontalDivider(color = onGradient.copy(alpha = 0.25f))
                                }
                            }

                            if (ledgers.isNotEmpty()) {
                                TxLedgerRow(
                                    ledgers = ledgers,
                                    selectedLedgerId = selectedLedgerId,
                                    onSelect = { selectedLedgerId = it },
                                    modifier = Modifier.padding(vertical = 12.dp),
                                    contentColor = onGradient
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = merchant, onValueChange = { merchant = it },
                    label = { Text("商户/对方（可选）") }, singleLine = true,
                    textStyle = TextStyle(fontSize = 14.sp, color = onGradient),
                    shape = RoundedCornerShape(12.dp),
                    colors = outlinedOnGradient(onGradient),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = note, onValueChange = { note = it },
                    label = { Text("备注（可选）") }, singleLine = true,
                    textStyle = TextStyle(fontSize = 14.sp, color = onGradient),
                    shape = RoundedCornerShape(12.dp),
                    colors = outlinedOnGradient(onGradient),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                com.bookkeeping.app.ui.ReceiptSection(
                    editTxId = tx.id,
                    labelColor = onGradient,
                    actionColor = onGradient
                )
                Spacer(Modifier.height(12.dp))
            }

            // ── 底部按钮 ──
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f).height(44.dp),
                    shape = RoundedCornerShape(22.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = onGradient),
                    border = BorderStroke(1.dp, onGradient)
                ) { Text("取消", fontSize = 16.sp) }
                Button(
                    onClick = { saveEdit() },
                    modifier = Modifier.weight(1f).height(44.dp),
                    shape = RoundedCornerShape(22.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = amountColor,
                        contentColor = Color.White
                    )
                ) { Text("保存", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
            }
        }
    }

    if (embedded) {
        // 内嵌调用（报销等二级页面内）：外层 Scaffold 已铺同源渐变，
        // 这里只做透明容器，不重复铺渐变，也不加系统栏 inset
        Box(Modifier.fillMaxSize()) { body(Modifier) }
    } else {
        // 独立全屏：与其余二级页面完全一致 —— 抽屉同源竖向渐变铺满（含系统栏）+ 透明容器
        Scaffold(
            modifier = Modifier.drawerGradientBackground(),
            containerColor = Color.Transparent,
            contentColor = androidx.compose.ui.graphics.Color.White,
            contentWindowInsets = WindowInsets.systemBars,
            topBar = {
                DetailTopBar(
                    onBack = onDismiss,
                    title = { Text("编辑交易", fontWeight = FontWeight.Bold) },
                    actions = {
                        TextButton(onClick = { deleteTx() }) { Text("🗑 删除", color = deleteColor) }
                    }
                )
            }
        ) { padding -> body(Modifier.padding(padding)) }
    }
}
