package com.bookkeeping.app

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bookkeeping.app.data.AppDatabase
import com.bookkeeping.app.data.entity.Account
import com.bookkeeping.app.data.entity.Ledger
import com.bookkeeping.app.data.entity.Transaction
import com.bookkeeping.app.service.DataBus
import com.bookkeeping.app.theme.ExpenseRed
import com.bookkeeping.app.theme.IncomeGreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ─── 手动记账弹窗 ────────────────────────────────────────────

/** 记一笔表单捕获的数据（capture 模式下回传给调用方，不落库） */
data class ManualEntryData(
    val amount: Double,
    val type: Transaction.Type,
    val category: String,
    val merchant: String,
    val note: String
)

/**
 * 嵌入模式下的底部 inset：弹窗已处于外层 Scaffold padding 区内
 * （状态栏/底部导航栏高度已被外层垫掉）。键盘弹出时按「弹窗自身距屏幕底部的真实距离」
 * 精确补差值，使返回/保存按钮始终紧贴键盘顶部，键盘收起时 padding 为 0。
 */
@Composable
internal fun Modifier.embeddedImePadding(): Modifier {
    val view = LocalView.current
    val density = LocalDensity.current
    var distanceFromBottomPx by remember { mutableIntStateOf(0) }
    val imeBottomPx = WindowInsets.ime.getBottom(density)
    val padPx = (imeBottomPx - distanceFromBottomPx).coerceAtLeast(0)

    return this
        .onGloballyPositioned { coords ->
            val bottomInRoot = coords.positionInRoot().y + coords.size.height
            distanceFromBottomPx = (view.height - bottomInRoot).coerceAtLeast(0f).toInt()
        }
        .padding(bottom = with(density) { padPx.toDp() })
}

/**
 * 记一笔弹窗。
 * @param initialType    预选类型（支出/收入/转账）
 * @param initialCategory 预选分类（null 用默认）
 * @param fixedCategory  true 时隐藏类型 Tab + 分类网格，类型/分类锁定为 initial 值（用于报销）
 * @param onCapture      非 null 时进入「捕获模式」：保存按钮回传 ManualEntryData 而不落库（用于周期任务）
 * @param embedded       true 表示弹窗已处于外层 Scaffold 的 padding 区内（二级页面调用）：
 *                       不再重复加状态栏/导航栏 inset，键盘 padding 只取 ime 与 nav 的差值
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualAddDialog(
    onDismiss: () -> Unit,
    initialType: Transaction.Type = Transaction.Type.EXPENSE,
    initialCategory: String? = null,
    fixedCategory: Boolean = false,
    onCapture: ((ManualEntryData) -> Unit)? = null,
    reimburseStatus: String? = null,
    forcedLedgerId: Long? = null,
    embedded: Boolean = false
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.getInstance(context) }

    var amountText by remember { mutableStateOf("") }
    var selectedType by remember { mutableStateOf(initialType) }
    var selectedCategory by remember { mutableStateOf(initialCategory ?: "餐饮/外卖") }
    var merchant by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var accounts by remember { mutableStateOf<List<Account>>(emptyList()) }
    var ledgers by remember { mutableStateOf<List<Ledger>>(emptyList()) }
    var selectedAccountId by remember { mutableStateOf<Long?>(null) }
    var selectedLedgerId by remember { mutableStateOf<Long?>(null) }

    val categories = categoriesFor(selectedType)

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            accounts = db.accountDao().getAll()
            ledgers = db.ledgerDao().getAll()
            selectedAccountId = accounts.firstOrNull()?.id
            selectedLedgerId = forcedLedgerId
                ?: ledgers.firstOrNull { it.isDefault }?.id ?: ledgers.firstOrNull()?.id
        }
    }

    // 保存并关闭
    fun saveRecord() {
        val amt = amountText.toDoubleOrNull() ?: return
        if (amt <= 0) return
        // 捕获模式：回传数据给调用方，不落库
        if (onCapture != null) {
            onCapture(
                ManualEntryData(
                    amount = amt,
                    type = selectedType,
                    category = selectedCategory,
                    merchant = merchant,
                    note = note
                )
            )
            onDismiss()
            return
        }
        val tx = Transaction(
            amount = amt,
            type = selectedType,
            category = selectedCategory,
            merchant = merchant,
            source = "手动",
            accountId = selectedAccountId,
            ledgerId = selectedLedgerId,
            note = note,
            rawText = "[手动] $selectedCategory ${merchant.ifBlank { "" }} $amt",
            isManual = true,
            confirmed = true,
            confidence = Transaction.Confidence.HIGH,
            occurredAt = System.currentTimeMillis(),
            reimburseStatus = reimburseStatus ?: if (selectedCategory == "报销") {
                when (selectedType) {
                    Transaction.Type.EXPENSE -> "PENDING"
                    Transaction.Type.INCOME -> "DONE"
                    else -> null
                }
            } else null
        )
        scope.launch {
            withContext(Dispatchers.IO) {
                val newId = db.transactionDao().insert(tx)
                // 凭证图片落盘：pending → {id}.jpg
                com.bookkeeping.app.ui.ReceiptStore.finalizePending(context, newId)
                // 预算超支检查（每自然月最多提醒一次）
                checkBudgetAndNotify(context)
                // 更新账户余额
                if (selectedAccountId != null) {
                    val delta = when (selectedType) {
                        Transaction.Type.EXPENSE -> -amt
                        Transaction.Type.INCOME -> amt
                        Transaction.Type.TRANSFER -> 0.0
                    }
                    if (delta != 0.0) db.accountDao().adjustBalance(selectedAccountId!!, delta)
                }
            }
            DataBus.notifyDataChanged()
            onDismiss()
        }
    }

    val amountFocus = remember { FocusRequester() }
    val amountColor = when (selectedType) {
        Transaction.Type.EXPENSE -> ExpenseRed
        Transaction.Type.INCOME -> IncomeGreen
        Transaction.Type.TRANSFER -> MaterialTheme.colorScheme.primary
    }
    val canSave = (amountText.toDoubleOrNull() ?: 0.0) > 0

    // 捕获模式（周期任务）只支持支出/收入，不显示转账
    val typeLabels = if (onCapture != null) listOf(
        Transaction.Type.EXPENSE to "支出",
        Transaction.Type.INCOME to "收入"
    ) else listOf(
        Transaction.Type.EXPENSE to "支出",
        Transaction.Type.INCOME to "收入",
        Transaction.Type.TRANSFER to "转账"
    )
    val typeIndex = typeLabels.indexOfFirst { it.first == selectedType }.coerceAtLeast(0)

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier
                .fillMaxSize()
                .then(
                    if (embedded) Modifier.embeddedImePadding()
                    else Modifier.statusBarsPadding().navigationBarsPadding().imePadding()
                )
        ) {
            LaunchedEffect(Unit) {
                try { amountFocus.requestFocus() } catch (_: Exception) { }
            }


            // ── 顶部导航栏：标题（返回键在底部，账本在下方账户区） ─-
            Row(
                Modifier.fillMaxWidth().height(52.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "记一笔",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // ── 类型 Tab：支出/收入/转账（fixedCategory 时隐藏） ──
            if (!fixedCategory) {
                TabRow(
                    selectedTabIndex = typeIndex,
                    containerColor = Color.Transparent,
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
                                    fontSize = 16.sp,
                                    fontWeight = if (selectedType == type) FontWeight.Bold else FontWeight.Normal,
                                    color = if (selectedType == type) amountColor else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        )
                    }
                }
            }

            // ── 表单区（可滚动） ──
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
            ) {
                Spacer(Modifier.height(14.dp))

                // 金额卡片
                Card(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    )
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("¥", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = amountColor)
                        Spacer(Modifier.width(8.dp))
                        OutlinedTextField(
                            value = amountText,
                            onValueChange = { input ->
                                // 只允许数字和小数点，最多2位小数，整数最多9位
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
                            placeholder = {
                                Text(
                                    "0.00",
                                    fontSize = 28.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                                )
                            },
                            singleLine = true,
                            textStyle = TextStyle(
                                fontSize = 28.sp, fontWeight = FontWeight.Bold, color = amountColor
                            ),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Decimal,
                                imeAction = ImeAction.Done
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(amountFocus),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color.Transparent,
                                unfocusedBorderColor = Color.Transparent,
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                cursorColor = amountColor
                            )
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                // 分类选择（fixedCategory 时锁定为 initialCategory，隐藏网格）
                if (!fixedCategory) {
                    categories.chunked(4).forEach { rowCats ->
                        Row(
                            Modifier.fillMaxWidth().padding(bottom = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            rowCats.forEach { cat ->
                                val isSel = cat == selectedCategory
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .height(42.dp)
                                        .background(
                                            if (isSel) amountColor.copy(alpha = 0.12f)
                                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                            RoundedCornerShape(12.dp)
                                        )
                                        .clickable { selectedCategory = cat },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        cat,
                                        fontSize = 13.sp,
                                        maxLines = 1,
                                        color = if (isSel) amountColor else MaterialTheme.colorScheme.onSurface,
                                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            }
                            repeat(4 - rowCats.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }

                Spacer(Modifier.height(6.dp))

                // 账户 / 账本选择卡片
                Card(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                    )
                ) {
                    Column(Modifier.padding(horizontal = 14.dp)) {
                        if (accounts.isNotEmpty()) {
                            val accountLabel = when (selectedType) {
                                Transaction.Type.EXPENSE -> "付款账户"
                                Transaction.Type.INCOME -> "收款账户"
                                Transaction.Type.TRANSFER -> "转出账户"
                            }
                            var accMenu by remember { mutableStateOf(false) }
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(accountLabel, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.weight(1f))
                                Box {
                                    Row(
                                        Modifier.clickable { accMenu = true },
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            accounts.firstOrNull { it.id == selectedAccountId }?.let { "${it.icon} ${it.name}" } ?: "不关联",
                                            fontSize = 14.sp, fontWeight = FontWeight.Medium
                                        )
                                        Text(" ▾", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    DropdownMenu(expanded = accMenu, onDismissRequest = { accMenu = false }) {
                                        DropdownMenuItem(
                                            text = { Text("不关联账户") },
                                            onClick = { selectedAccountId = null; accMenu = false }
                                        )
                                        accounts.forEach { acc ->
                                            DropdownMenuItem(
                                                text = { Text("${acc.icon} ${acc.name}") },
                                                onClick = { selectedAccountId = acc.id; accMenu = false }
                                            )
                                        }
                                    }
                                }
                            }
                            if (ledgers.isNotEmpty()) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                            }
                        }

                        if (ledgers.isNotEmpty()) {
                            var ledMenu by remember { mutableStateOf(false) }
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("记账账本", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.weight(1f))
                                Box {
                                    Row(
                                        Modifier.then(
                                            if (forcedLedgerId == null) Modifier.clickable { ledMenu = true }
                                            else Modifier
                                        ),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            ledgers.firstOrNull { it.id == selectedLedgerId }?.let { "${it.icon} ${it.name}" } ?: "不关联",
                                            fontSize = 14.sp, fontWeight = FontWeight.Medium
                                        )
                                        if (forcedLedgerId == null) {
                                            Text(" ▾", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                    DropdownMenu(expanded = ledMenu, onDismissRequest = { ledMenu = false }) {
                                        DropdownMenuItem(
                                            text = { Text("不关联账本") },
                                            onClick = { selectedLedgerId = null; ledMenu = false }
                                        )
                                        ledgers.forEach { led ->
                                            DropdownMenuItem(
                                                text = { Text("${led.icon} ${led.name}") },
                                                onClick = { selectedLedgerId = led.id; ledMenu = false }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = merchant,
                    onValueChange = { merchant = it },
                    label = { Text("商户/对方（可选）") },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 14.sp),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("备注（可选）") },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 14.sp),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                com.bookkeeping.app.ui.ReceiptSection(editTxId = null)
                Spacer(Modifier.height(12.dp))
            }

            // ── 底部：返回 + 保存 ──
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f).height(50.dp),
                    shape = RoundedCornerShape(25.dp)
                ) {
                    Text("返回", fontSize = 16.sp)
                }
                Button(
                    onClick = { saveRecord() },
                    enabled = canSave,
                    modifier = Modifier.weight(1f).height(50.dp),
                    shape = RoundedCornerShape(25.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = amountColor,
                        disabledContainerColor = amountColor.copy(alpha = 0.3f)
                    )
                ) {
                    Text("保存", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
