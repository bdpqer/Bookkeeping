package com.bookkeeping.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.bookkeeping.app.data.AppDatabase
import com.bookkeeping.app.data.entity.Transaction
import com.bookkeeping.app.theme.ExpenseRed
import com.bookkeeping.app.theme.IncomeGreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// ─── 首页：今日汇总 + 最近交易 ─────────────────────────────────

@Composable
internal fun HomeScreen(
    onNavigate: (Tab) -> Unit = {},
    onSecondaryScreenChanged: (Boolean) -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val db = remember { AppDatabase.getInstance(context) }

    // 账本切换（null = 全部账本）
    var ledgers by remember { mutableStateOf<List<com.bookkeeping.app.data.entity.Ledger>>(emptyList()) }
    var selectedLedgerId by remember { mutableStateOf<Long?>(null) }
    val selectedLedgerName =
        if (selectedLedgerId == null) "全部账本"
        else ledgers.firstOrNull { it.id == selectedLedgerId }?.name ?: "全部账本"

    // 交易数据改为 Flow 订阅：数据库变化自动刷新（入库/编辑/删除/导入均无需手动 refresh）
    val allTransactions by remember(selectedLedgerId) {
        if (selectedLedgerId == null) db.transactionDao().observeAll()
        else db.transactionDao().observeByLedger(selectedLedgerId!!)
    }.collectAsState(initial = emptyList<Transaction>())
    val recentTransactions = allTransactions.take(8)
    val todayRange = startOfToday()..endOfToday()
    val monthRange = startOfMonth()..endOfMonth()
    val todayExpense = allTransactions.filter { it.type == Transaction.Type.EXPENSE && it.occurredAt in todayRange }.sumOf { it.amount }.round2()
    val todayIncome = allTransactions.filter { it.type == Transaction.Type.INCOME && it.occurredAt in todayRange }.sumOf { it.amount }.round2()
    val monthExpense = allTransactions.filter { it.type == Transaction.Type.EXPENSE && it.occurredAt in monthRange }.sumOf { it.amount }.round2()
    val monthIncome = allTransactions.filter { it.type == Transaction.Type.INCOME && it.occurredAt in monthRange }.sumOf { it.amount }.round2()
    var showLoanScreen by remember { mutableStateOf(false) }
    var showLedgerMenu by remember { mutableStateOf(false) }
    var showRecurringScreen by remember { mutableStateOf(false) }
    var showReimburseScreen by remember { mutableStateOf(false) }
    var showReceivableScreen by remember { mutableStateOf(false) }
    var receivableInitialTab by remember { mutableIntStateOf(0) }
    var showRecycleBinScreen by remember { mutableStateOf(false) }
    var showSearchDialog by remember { mutableStateOf(false) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    // 任一二级全屏页面打开时通知外层隐藏浮动 + 号
    LaunchedEffect(showLoanScreen, showRecurringScreen, showReimburseScreen, showReceivableScreen, showRecycleBinScreen) {
        onSecondaryScreenChanged(showLoanScreen || showRecurringScreen || showReimburseScreen || showReceivableScreen || showRecycleBinScreen)
    }

    fun openDrawer() { scope.launch { drawerState.open() } }
    fun closeDrawer() { scope.launch { drawerState.close() } }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            ledgers = db.ledgerDao().getAll()
            selectedLedgerId = ledgers.firstOrNull { it.isDefault }?.id
        }
    }

    // CSV 导入：选文件 → 解析入库（Flow 自动刷新，无需手动 refresh）
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val (ok, skip) = importCsvFromUri(context, uri)
                android.widget.Toast.makeText(
                    context, "导入完成：新增 $ok 条，跳过重复 $skip 条", android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // 备份恢复：选备份文件 → 确认覆盖 → 恢复并重启
    var pendingRestoreUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var showBackupDialog by remember { mutableStateOf(false) }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) pendingRestoreUri = uri
    }
    if (showBackupDialog) {
        var autoEnabled by remember {
            mutableStateOf(com.bookkeeping.app.worker.AutoBackupWorker.isEnabled(context))
        }
        AlertDialog(
            onDismissRequest = { showBackupDialog = false },
            title = { Text("数据备份与恢复", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(
                        "📤 备份全部数据", fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showBackupDialog = false; scope.launch { backupDatabase(context) } }
                            .padding(vertical = 14.dp)
                    )
                    Text(
                        "📊 导出 CSV（Excel 对账用）", fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showBackupDialog = false; scope.launch { exportTransactionsCsv(context) } }
                            .padding(vertical = 14.dp)
                    )
                    Text(
                        "📥 从备份文件恢复", fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showBackupDialog = false; restoreLauncher.launch(arrayOf("*/*")) }
                            .padding(vertical = 14.dp)
                    )
                    HorizontalDivider()
                    // 每周自动备份开关
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("每周自动备份", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            val last = com.bookkeeping.app.worker.AutoBackupWorker.lastBackupAt(context)
                            Text(
                                if (autoEnabled) {
                                    if (last > 0) "上次备份：${SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(last))} · 保留最近 4 份"
                                    else "开启中 · 首次备份将在后台执行"
                                } else "关闭中 · 开启后自动备份到应用目录",
                                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = autoEnabled,
                            onCheckedChange = {
                                com.bookkeeping.app.worker.AutoBackupWorker.setEnabled(context, it)
                                autoEnabled = it
                            }
                        )
                    }
                    Text(
                        "备份包含全部数据（交易/规则/账户/账本等）和凭证图片；恢复会覆盖当前所有数据",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            },
            confirmButton = {},
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showBackupDialog = false }) { Text("取消") }
            }
        )
    }
    pendingRestoreUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingRestoreUri = null },
            title = { Text("确认恢复备份？", fontWeight = FontWeight.Bold) },
            text = { Text("恢复将覆盖当前全部数据（交易、规则、账户、账本等），覆盖后无法撤销。") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    pendingRestoreUri = null
                    scope.launch {
                        if (restoreDatabaseFromUri(context, uri)) {
                            android.widget.Toast.makeText(context, "恢复成功，正在重启…", android.widget.Toast.LENGTH_SHORT).show()
                            kotlinx.coroutines.delay(600)
                            restartApp(context)
                        }
                    }
                }) { Text("覆盖并恢复", color = ExpenseRed) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { pendingRestoreUri = null }) { Text("取消") }
            }
        )
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            HomeDrawer(
                onNavigateTab = { closeDrawer(); onNavigate(it) },
                onOpenRecurring = { closeDrawer(); showRecurringScreen = true },
                onOpenReimburse = { closeDrawer(); showReimburseScreen = true },
                onOpenReceivable = { tab -> closeDrawer(); receivableInitialTab = tab; showReceivableScreen = true },
                onOpenLoan = { closeDrawer(); showLoanScreen = true },
                onOpenRecycleBin = { closeDrawer(); showRecycleBinScreen = true },
                onExport = { closeDrawer(); scope.launch { exportAndShareCsv(context) } },
                onImport = { closeDrawer(); importLauncher.launch(arrayOf("text/*", "application/octet-stream")) },
                onBackupDialog = { closeDrawer(); showBackupDialog = true }
            )
        }
    ) {
        Column(Modifier.fillMaxSize()) {
            // ── 顶栏：☰  日常账本▾        🔍 ☁️ ──
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp, start = 2.dp, end = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Menu, contentDescription = "菜单",
                    modifier = Modifier.clickable { openDrawer() }.padding(2.dp)
                )
                Spacer(Modifier.width(4.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.TopStart) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { showLedgerMenu = true }
                    ) {
                        Text(selectedLedgerName, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                        Text(" ▾", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    DropdownMenu(expanded = showLedgerMenu, onDismissRequest = { showLedgerMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("全部账本") },
                            onClick = { selectedLedgerId = null; showLedgerMenu = false }
                        )
                        ledgers.forEach { led ->
                            DropdownMenuItem(
                                text = { Text("${led.icon} ${led.name}${if (led.isDefault) "（默认）" else ""}") },
                                onClick = { selectedLedgerId = led.id; showLedgerMenu = false }
                            )
                        }
                    }
                }
                Icon(
                    Icons.Default.Search, contentDescription = "搜索",
                    modifier = Modifier.clickable { showSearchDialog = true }.padding(2.dp)
                )
                Box {
                    Text(
                        "☁️", fontSize = 18.sp,
                        modifier = Modifier.clickable { showBackupDialog = true }.padding(2.dp)
                    )
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 6.dp, end = 6.dp)
                            .size(7.dp)
                            .background(Color(0xFFE53935), CircleShape)
                    )
                }
            }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // ── Hero 本月结余卡 ──
        item {
            val balance = monthIncome - monthExpense
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        androidx.compose.ui.graphics.Brush.horizontalGradient(
                            listOf(Color(0xFF4A90D9), Color(0xFF7EC8E3), Color(0xFFE8C468))
                        ),
                        RoundedCornerShape(18.dp)
                    )
                    .padding(12.dp)
            ) {
                Column {
                    Text("本月结余", fontSize = 12.sp, color = Color.White.copy(alpha = 0.85f))
                    Text(
                        balance.formatAmount(),
                        fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Color.White
                    )
                    Spacer(Modifier.height(1.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("本月收入  ", fontSize = 12.sp, color = Color.White.copy(alpha = 0.85f))
                            Text(monthIncome.formatAmount(), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color.White)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("本月支出  ", fontSize = 12.sp, color = Color.White.copy(alpha = 0.85f))
                            Text(monthExpense.formatAmount(), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color.White)
                        }
                    }
                }
            }
        }

        // ── 今日行 ──
        item {
            val cal = java.util.Calendar.getInstance()
            val weekDay = arrayOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")[cal.get(java.util.Calendar.DAY_OF_WEEK) - 1]
            val dateStr = String.format("%02d-%02d %s", cal.get(java.util.Calendar.MONTH) + 1, cal.get(java.util.Calendar.DAY_OF_MONTH), weekDay)
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("📅", fontSize = 18.sp)
                    Spacer(Modifier.width(10.dp))
                    Text(dateStr, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.weight(1f))
                    Text("收 ", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(todayIncome.formatAmount(), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = IncomeGreen)
                    Spacer(Modifier.width(14.dp))
                    Text("支 ", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(todayExpense.formatAmount(), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = ExpenseRed)
                }
            }
        }

        // ── 交易列表 / 空态 ──
        if (recentTransactions.isEmpty()) {
            item {
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                    Box(Modifier.fillMaxWidth().padding(vertical = 64.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("☕", fontSize = 40.sp)
                            Spacer(Modifier.height(6.dp))
                            Text("当前没有数据，快去添加一笔吧~", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        } else {
            item { Text("最近交易", fontWeight = FontWeight.Bold, fontSize = 15.sp) }
            items(recentTransactions.take(5)) { tx ->
                TransactionItem(tx)
            }
            if (recentTransactions.size > 5) {
                item {
                    Text(
                        "更多明细 >",
                        fontSize = 13.sp,
                        color = Color(0xFF2E5AAC),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onNavigate(Tab.LIST) }
                            .padding(top = 4.dp, bottom = 2.dp),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        // ── 本月预算卡 ──
        item { HomeBudgetCard(monthExpense, getMonthlyBudget(context)) }

        // ── 账单报表（时间筛选 + 圆环图） ──
        if (allTransactions.isNotEmpty()) {
            item {
                ReportStatsCard(allTransactions)
            }
        }
    }
        } // Column
    } // ModalNavigationDrawer

    val currentLedgerIdForPages =
        ledgers.firstOrNull { it.id == selectedLedgerId }?.id
            ?: ledgers.firstOrNull { it.isDefault }?.id
            ?: ledgers.firstOrNull()?.id ?: 1L

    if (showLoanScreen) {
        com.bookkeeping.app.ui.LoanScreen(ledgers = ledgers, initialLedgerId = currentLedgerIdForPages, onClose = { showLoanScreen = false })
    }
    if (showRecurringScreen) {
        com.bookkeeping.app.ui.RecurringScreen(onClose = { showRecurringScreen = false })
    }
    if (showReimburseScreen) {
        com.bookkeeping.app.ui.ReimburseScreen(ledgers = ledgers, initialLedgerId = currentLedgerIdForPages, onClose = { showReimburseScreen = false })
    }
    if (showReceivableScreen) {
        com.bookkeeping.app.ui.ReceivableScreen(ledgers = ledgers, initialLedgerId = currentLedgerIdForPages, onClose = { showReceivableScreen = false }, initialTab = receivableInitialTab)
    }
    if (showRecycleBinScreen) {
        com.bookkeeping.app.ui.RecycleBinScreen(onClose = { showRecycleBinScreen = false })
    }
    if (showSearchDialog) {
        TransactionSearchDialog(db = db, onDismiss = { showSearchDialog = false })
    }
}

// ─── 首页侧滑抽屉（小星记账风格菜单） ───────────────────────────

@Composable
private fun HomeDrawer(
    onNavigateTab: (Tab) -> Unit,
    onOpenRecurring: () -> Unit,
    onOpenReimburse: () -> Unit,
    onOpenReceivable: (Int) -> Unit,
    onOpenLoan: () -> Unit,
    onOpenRecycleBin: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onBackupDialog: () -> Unit
) {
    val context = LocalContext.current
    val companionDays = remember {
        try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            ((System.currentTimeMillis() - info.firstInstallTime) / 86_400_000L).toInt() + 1
        } catch (_: Exception) { 1 }
    }
    var showIoDialog by remember { mutableStateOf(false) }

    fun toast(msg: String) {
        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    ModalDrawerSheet(
        modifier = Modifier.width(220.dp),
        windowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0)
    ) {
        Column(Modifier.padding(start = 20.dp, end = 14.dp, top = 12.dp, bottom = 8.dp)) {
            Text("记账助手", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2E5AAC))
            Spacer(Modifier.height(2.dp))
            Text("已陪伴 $companionDays 天", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        DrawerRow("🤖", "自动记账") { onNavigateTab(Tab.SETTINGS) }
        DrawerRow("📅", "周期记账") { onOpenRecurring() }
        DrawerRow("🧾", "报销管理") { onOpenReimburse() }
        DrawerRow("🤝", "应收应付") { onOpenReceivable(0) }
        DrawerRow("💳", "借贷管理") { onOpenLoan() }
        //DrawerRow("⚙️", "设置") { onNavigateTab(Tab.SETTINGS) }

        DrawerSection("数据")
        DrawerRow("☁️", "数据备份与恢复") { onBackupDialog() }
        DrawerRow("📄", "账单导入与导出") { showIoDialog = true }
        DrawerRow("🗑", "回收站") { onOpenRecycleBin() }

        DrawerSection("产品")
        DrawerRow("❓", "帮助与反馈") { toast("自用版，有问题直接在代码里改 😄") }
    }

    if (showIoDialog) {
        AlertDialog(
            onDismissRequest = { showIoDialog = false },
            title = { Text("账单导入与导出", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(
                        "📥 从 CSV 文件导入", fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showIoDialog = false; onImport() }
                            .padding(vertical = 14.dp)
                    )
                    Text(
                        "📤 导出并分享 CSV", fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showIoDialog = false; onExport() }
                            .padding(vertical = 14.dp)
                    )
                }
            },
            confirmButton = {},
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showIoDialog = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun DrawerRow(icon: String, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(icon, fontSize = 21.sp)
        Spacer(Modifier.width(18.dp))
        Text(label, fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun DrawerSection(title: String) {
    HorizontalDivider(
        Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    )
    Text(
        title,
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 24.dp, top = 2.dp, bottom = 4.dp)
    )
}

// ─── 首页交易搜索弹窗 ──────────────────────────────────────

@Composable
private fun TransactionSearchDialog(db: AppDatabase, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Transaction>>(emptyList()) }

    LaunchedEffect(query) {
        val q = query.trim()
        if (q.isBlank()) {
            results = emptyList()
        } else {
            delay(300)  // 防抖：停止输入 300ms 后才查询
            results = withContext(Dispatchers.IO) {
                // 金额前缀只保留数字和小数点，避免 LIKE 通配符干扰；空则用不可能匹配的占位符
                val amountPrefix = q.filter { it.isDigit() || it == '.' }
                db.transactionDao().searchQuick(q, amountPrefix.ifEmpty { "\uFFFF" })
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("搜索交易", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("商户 / 分类 / 备注 / 金额") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState())) {
                    if (query.isNotBlank() && results.isEmpty()) {
                        Text("无匹配记录", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    results.forEach { tx ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(categoryEmoji(tx.category), fontSize = 18.sp)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(tx.merchant.ifBlank { tx.category }, fontSize = 14.sp, maxLines = 1)
                                Text(
                                    java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                                        .format(java.util.Date(tx.occurredAt)),
                                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            val color = when (tx.type) {
                                Transaction.Type.INCOME -> IncomeGreen
                                Transaction.Type.EXPENSE -> ExpenseRed
                                else -> MaterialTheme.colorScheme.primary
                            }
                            val prefix = when (tx.type) {
                                Transaction.Type.INCOME -> "+"
                                Transaction.Type.EXPENSE -> "-"
                                else -> ""
                            }
                            Text("$prefix¥${tx.amount.formatAmount()}",
                                fontSize = 14.sp, fontWeight = FontWeight.Bold, color = color)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

// 首页预算卡（钱迹风格）
@Composable
private fun HomeBudgetCard(monthExpense: Double, budget: Double) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(34.dp).background(Color(0x1AE53935), androidx.compose.foundation.shape.CircleShape),
                    contentAlignment = Alignment.Center
                ) { Text("🎯", fontSize = 16.sp) }
                Spacer(Modifier.width(10.dp))
                Text("本月预算", fontWeight = FontWeight.Medium, fontSize = 15.sp)
                Spacer(Modifier.weight(1f))
                if (budget > 0) {
                    Text(
                        "剩余 ${(budget - monthExpense).coerceAtLeast(0.0).formatAmount()}",
                        fontSize = 15.sp, fontWeight = FontWeight.Bold,
                        color = if (monthExpense > budget) ExpenseRed else MaterialTheme.colorScheme.onSurface
                    )
                } else {
                    Text("去设置 ›", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                }
            }
            if (budget > 0) {
                Spacer(Modifier.height(6.dp))
                val ratio = (monthExpense / budget).toFloat().coerceIn(0f, 1f)
                Box(Modifier.fillMaxWidth().height(8.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))) {
                    Box(
                        Modifier.fillMaxWidth(ratio).height(8.dp).background(
                            when {
                                monthExpense > budget -> ExpenseRed
                                ratio > 0.8f -> Color(0xFFFF9800)
                                else -> IncomeGreen
                            },
                            RoundedCornerShape(4.dp)
                        )
                    )
                }
                Spacer(Modifier.height(2.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${monthExpense.formatAmount()} / ${budget.formatAmount()}",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val cal = Calendar.getInstance()
                    val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
                    val remainingDays = (daysInMonth - cal.get(Calendar.DAY_OF_MONTH)).coerceAtLeast(1)
                    Text("剩余日均 ${((budget - monthExpense).coerceAtLeast(0.0) / remainingDays).formatAmount()}",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

// ─── 时间助手 ────────────────────────────────────────────────

private fun startOfToday(): Long = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

private fun endOfToday(): Long = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59)
    set(Calendar.SECOND, 59); set(Calendar.MILLISECOND, 999)
}.timeInMillis

private fun startOfMonth(): Long = Calendar.getInstance().apply {
    set(Calendar.DAY_OF_MONTH, 1)
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

private fun endOfMonth(): Long = Calendar.getInstance().apply {
    set(Calendar.DAY_OF_MONTH, getActualMaximum(Calendar.DAY_OF_MONTH))
    set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59)
    set(Calendar.SECOND, 59); set(Calendar.MILLISECOND, 999)
}.timeInMillis
