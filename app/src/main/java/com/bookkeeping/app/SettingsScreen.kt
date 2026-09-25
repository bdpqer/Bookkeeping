package com.bookkeeping.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.bookkeeping.app.data.AppDatabase
import com.bookkeeping.app.service.CaptureLogBus
import com.bookkeeping.app.theme.ExpenseRed
import com.bookkeeping.app.theme.IncomeGreen
import kotlinx.coroutines.launch

// ─── 设置（调试面板 + 权限检查） ──────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(
    themeMode: String = "system",
    onThemeChanged: (String) -> Unit = {},
    onNewRule: () -> Unit = {},
    onEditRule: (com.bookkeeping.app.data.entity.ParseRule) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf(CaptureLogBus.entries) }
    var listenerEnabled by remember { mutableStateOf(isNotificationListenerEnabled(context)) }
    var rules by remember { mutableStateOf<List<com.bookkeeping.app.data.entity.ParseRule>>(emptyList()) }
    var accounts by remember { mutableStateOf<List<com.bookkeeping.app.data.entity.Account>>(emptyList()) }
    var ledgers by remember { mutableStateOf<List<com.bookkeeping.app.data.entity.Ledger>>(emptyList()) }
    var recurringItems by remember { mutableStateOf<List<com.bookkeeping.app.data.entity.RecurringItem>>(emptyList()) }
    var lockEnabled by remember { mutableStateOf(com.bookkeeping.app.ui.isLockEnabled(context)) }
    var bioEnabled by remember { mutableStateOf(com.bookkeeping.app.ui.isBiometricUnlockEnabled(context)) }
    var hasPin by remember { mutableStateOf(com.bookkeeping.app.ui.hasPinSet(context)) }
    var showPinSetup by remember { mutableStateOf(false) }
    val canBiometric = remember { com.bookkeeping.app.ui.canAuthenticateBiometric(context) }

    fun loadAll() {
        scope.launch {
            rules = AppDatabase.getInstance(context).parseRuleDao().getAll()
            accounts = AppDatabase.getInstance(context).accountDao().getAllIncludingDisabled()
            ledgers = AppDatabase.getInstance(context).ledgerDao().getAll()
            recurringItems = AppDatabase.getInstance(context).recurringDao().getAll()
        }
    }

    LaunchedEffect(Unit) { loadAll() }

    val smsGranted = remember {
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) ==
            PackageManager.PERMISSION_GRANTED
    }

    DisposableEffect(Unit) {
        val unsub = CaptureLogBus.subscribe { entries = it }
        onDispose { unsub() }
    }

    val smsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* refresh on back */ }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Text("权限状态", fontWeight = FontWeight.Bold, fontSize = 16.sp) }
        item {
            Card(shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.padding(16.dp)) {
                    PermissionRow(
                        label = "通知监听",
                        desc = "捕获微信/支付宝/银行App通知",
                        granted = listenerEnabled,
                        onRequest = {
                            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        },
                        onBack = { listenerEnabled = isNotificationListenerEnabled(context) }
                    )
                    Spacer(Modifier.height(10.dp))
                    PermissionRow(
                        label = "短信读取",
                        desc = "捕获银行短信内容",
                        granted = smsGranted,
                        onRequest = {
                            val perms = mutableListOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                perms += Manifest.permission.POST_NOTIFICATIONS
                            }
                            smsLauncher.launch(perms.toTypedArray())
                        }
                    )
                }
            }
        }

        // ── 自动记账总开关 ──
        item {
            val settingsPrefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            var autoEnabled by remember { mutableStateOf(settingsPrefs.getBoolean("auto_capture_enabled", true)) }
            Card(shape = RoundedCornerShape(12.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("自动记账", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Text(
                            if (autoEnabled) "已开启 · 收到支付通知/银行短信将自动记录"
                            else "已关闭 · 不再自动解析通知和短信",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = autoEnabled,
                        onCheckedChange = {
                            autoEnabled = it
                            settingsPrefs.edit().putBoolean("auto_capture_enabled", it).apply()
                        }
                    )
                }
            }
        }

        // ── 主题切换 ──
        item { Text("外观", fontWeight = FontWeight.Bold, fontSize = 16.sp) }
        item {
            Card(shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("深色模式", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色").forEach { (mode, label) ->
                            AssistChip(
                                onClick = { onThemeChanged(mode) },
                                label = { Text(label) }
                            )
                        }
                    }
                }
            }
        }

        // ── 隐私锁 ──
        item { Text("隐私锁", fontWeight = FontWeight.Bold, fontSize = 16.sp) }
        item {
            Card(shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("启动/回到 App 时锁定", fontSize = 14.sp)
                            if (lockEnabled && !hasPin) {
                                Text("⚠️ 尚未设置 PIN", fontSize = 11.sp, color = Color(0xFFE53935))
                            }
                        }
                        androidx.compose.material3.Switch(
                            checked = lockEnabled,
                            onCheckedChange = { enable ->
                                if (enable && !hasPin) {
                                    showPinSetup = true
                                } else {
                                    com.bookkeeping.app.ui.setLockEnabled(context, enable)
                                    lockEnabled = enable
                                    if (!enable) {
                                        com.bookkeeping.app.ui.setBiometricEnabled(context, false)
                                        bioEnabled = false
                                    }
                                }
                            }
                        )
                    }

                    if (lockEnabled) {
                        Spacer(Modifier.height(10.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("指纹/生物识别解锁", fontSize = 14.sp)
                                if (!canBiometric) {
                                    Text("设备不支持或未录入", fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            androidx.compose.material3.Switch(
                                checked = bioEnabled,
                                enabled = canBiometric && hasPin,
                                onCheckedChange = { enable ->
                                    com.bookkeeping.app.ui.setBiometricEnabled(context, enable)
                                    bioEnabled = enable
                                }
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        TextButton(onClick = { showPinSetup = true }) {
                            Text(if (hasPin) "修改 PIN" else "设置 PIN")
                        }
                        Text(
                            "离开 App 后需重新验证（PIN 或指纹）",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // ── 账户管理 ──
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text("账户管理", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                TextButton(onClick = {
                    scope.launch {
                        AppDatabase.getInstance(context).accountDao()
                            .insert(com.bookkeeping.app.data.entity.Account(
                                name = "新账户",
                                type = com.bookkeeping.app.data.entity.Account.AccountType.OTHER,
                                icon = "💰"
                            ))
                        loadAll()
                    }
                }) { Text("+ 新增", fontSize = 12.sp) }
            }
        }
        item {
            Card(shape = RoundedCornerShape(12.dp)) {
                Column {
                    accounts.forEach { acc ->
                        Row(
                            Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(acc.icon, fontSize = 20.sp)
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(acc.name, fontWeight = FontWeight.Medium)
                                    Text(
                                        "余额 ¥${acc.balance.formatAmount()}",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Row {
                                TextButton(onClick = {
                                    scope.launch {
                                        // 简化：直接删除，余额不清零
                                        AppDatabase.getInstance(context).accountDao().delete(acc.id)
                                        loadAll()
                                    }
                                }) { Text("删除", color = Color(0xFFE53935), fontSize = 12.sp) }
                            }
                        }
                        if (acc != accounts.lastOrNull()) HorizontalDivider()
                    }
                    if (accounts.isEmpty()) {
                        Text("暂无账户", Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                    }
                }
            }
        }

        // ── 账本管理 ──
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text("账本管理", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                TextButton(onClick = {
                    scope.launch {
                        AppDatabase.getInstance(context).ledgerDao()
                            .insert(com.bookkeeping.app.data.entity.Ledger(
                                name = "新账本",
                                icon = "📒"
                            ))
                        loadAll()
                    }
                }) { Text("+ 新增", fontSize = 12.sp) }
            }
        }
        item {
            Card(shape = RoundedCornerShape(12.dp)) {
                Column {
                    ledgers.forEach { led ->
                        Row(
                            Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(led.icon, fontSize = 20.sp)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    if (led.isDefault) "${led.name} (默认)" else led.name,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                            Row {
                                if (!led.isDefault) {
                                    TextButton(onClick = {
                                        scope.launch {
                                            AppDatabase.getInstance(context).ledgerDao().clearAllDefault()
                                            AppDatabase.getInstance(context).ledgerDao().setDefault(led.id)
                                            loadAll()
                                        }
                                    }) { Text("设为默认", fontSize = 12.sp) }
                                }
                                TextButton(onClick = {
                                    scope.launch {
                                        AppDatabase.getInstance(context).ledgerDao().delete(led.id)
                                        loadAll()
                                    }
                                }) { Text("删除", color = Color(0xFFE53935), fontSize = 12.sp) }
                            }
                        }
                        if (led != ledgers.lastOrNull()) HorizontalDivider()
                    }
                    if (ledgers.isEmpty()) {
                        Text("暂无账本", Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                    }
                }
            }
        }

        item { Text("预算管理", fontWeight = FontWeight.Bold, fontSize = 16.sp) }
        item {
            Card(shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("设置每月预算，首页会显示已花费/剩余",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))

                    var budgetText by remember { mutableStateOf(getMonthlyBudget(context).let {
                        if (it > 0) String.format("%.0f", it) else ""
                    }) }
                    var budgetError by remember { mutableStateOf("") }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.OutlinedTextField(
                            value = budgetText,
                            onValueChange = { budgetText = it.filter { c -> c.isDigit() || c == '.' } },
                            label = { Text("月度预算（设为 0 关闭）") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            isError = budgetError.isNotEmpty(),
                            supportingText = if (budgetError.isNotEmpty()) ({ Text(budgetError) }) else null
                        )
                        Spacer(Modifier.width(8.dp))
                        androidx.compose.material3.Button(
                            onClick = {
                                val amt = budgetText.toDoubleOrNull()
                                if (amt != null && amt >= 0) {
                                    setMonthlyBudget(context, amt)
                                    budgetError = ""
                                    android.widget.Toast.makeText(
                                        context,
                                        if (amt == 0.0) "预算已关闭" else "预算已设置为 ¥${String.format("%.0f", amt)}",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                } else {
                                    budgetError = "请输入有效金额"
                                }
                            }
                        ) { Text("保存") }
                    }
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("解析规则", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.TextButton(onClick = {
                        scope.launch {
                            AppDatabase.getInstance(context).parseRuleDao().clear()
                            AppDatabase.getInstance(context).parseRuleDao()
                                .insertAll(com.bookkeeping.app.parser.ParseEngine.DEFAULT_RULES)
                            loadAll()
                            refreshServiceRules()
                            android.widget.Toast.makeText(context, "已重置为默认规则", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }) { Text("重置") }
                    androidx.compose.material3.TextButton(onClick = onNewRule) { Text("+ 新建") }
                }
            }
        }
        item {
            Card(shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.padding(vertical = 6.dp)) {
                    if (rules.isEmpty()) {
                        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                            Text("暂无规则", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                        }
                    } else {
                        rules.forEach { rule ->
                            var enabled by remember { mutableStateOf(rule.isEnabled) }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onEditRule(rule) }
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(rule.channelName, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                    Text(
                                        if (rule.matchKeyword.isNotBlank()) "关键词: ${rule.matchKeyword}"
                                        else rule.channel,
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis
                                    )
                                }
                                androidx.compose.material3.Switch(
                                    checked = enabled,
                                    onCheckedChange = { newVal ->
                                        enabled = newVal
                                        scope.launch {
                                            AppDatabase.getInstance(context).parseRuleDao()
                                                .update(rule.copy(isEnabled = newVal))
                                            refreshServiceRules()
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        // ── 周期记账 / 账单提醒 ──
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text("周期记账 & 账单提醒", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                TextButton(onClick = {
                    scope.launch {
                        val now = System.currentTimeMillis()
                        AppDatabase.getInstance(context).recurringDao()
                            .insert(com.bookkeeping.app.data.entity.RecurringItem(
                                name = "新周期",
                                mode = com.bookkeeping.app.data.entity.RecurringItem.Mode.REMIND,
                                period = com.bookkeeping.app.data.entity.RecurringItem.Period.MONTHLY,
                                startDate = now,
                                nextRunAt = now
                            ))
                        loadAll()
                        com.bookkeeping.app.worker.RecurringWorker.triggerNow(context)
                    }
                }) { Text("+ 新增", fontSize = 12.sp) }
            }
        }
        item {
            Card(shape = RoundedCornerShape(12.dp)) {
                Column {
                    val sdf = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                    recurringItems.forEach { item ->
                        Row(
                            Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(item.name, fontWeight = FontWeight.Medium)
                                    Spacer(Modifier.width(6.dp))
                                    AssistChip(
                                        onClick = {
                                            scope.launch {
                                                AppDatabase.getInstance(context).recurringDao()
                                                    .update(item.copy(isEnabled = !item.isEnabled))
                                                loadAll()
                                                com.bookkeeping.app.worker.RecurringWorker.triggerNow(context)
                                            }
                                        },
                                        label = {
                                            Text(
                                                when {
                                                    !item.isEnabled -> "已停用"
                                                    item.mode == com.bookkeeping.app.data.entity.RecurringItem.Mode.AUTO_TX -> "自动记账"
                                                    else -> "账单提醒"
                                                },
                                                fontSize = 10.sp
                                            )
                                        }
                                    )
                                }
                                Text(
                                    buildString {
                                        append(when (item.period) {
                                            com.bookkeeping.app.data.entity.RecurringItem.Period.DAILY -> "每天"
                                            com.bookkeeping.app.data.entity.RecurringItem.Period.WEEKLY -> "每周"
                                            com.bookkeeping.app.data.entity.RecurringItem.Period.MONTHLY -> "每月"
                                            com.bookkeeping.app.data.entity.RecurringItem.Period.YEARLY -> "每年"
                                        })
                                        item.dayOfMonth?.let { append(" ${it}号") }
                                        item.dayOfWeek?.let { append(" 周${it}") }
                                        append("  · 下次 ${sdf.format(java.util.Date(item.nextRunAt))}")
                                    },
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                item.amount?.let {
                                    Text("¥${it.formatAmount()}", fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            TextButton(onClick = {
                                scope.launch {
                                    AppDatabase.getInstance(context).recurringDao().delete(item.id)
                                    loadAll()
                                }
                            }) { Text("删除", color = Color(0xFFE53935), fontSize = 12.sp) }
                        }
                        if (item != recurringItems.lastOrNull()) HorizontalDivider()
                    }
                    if (recurringItems.isEmpty()) {
                        Text(
                            "暂无周期规则，点击 + 新增工资/房贷/信用卡还款日提醒",
                            Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp
                        )
                    }
                }
            }
        }

        item { Text("数据管理", fontWeight = FontWeight.Bold, fontSize = 16.sp) }
        item {
            val settingsScope = rememberCoroutineScope()
            val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) {
                    settingsScope.launch {
                        val (ok, skip) = importCsvFromUri(context, uri)
                        android.widget.Toast.makeText(
                            context, "导入完成：新增 $ok 条，跳过重复 $skip 条", android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
            Card(shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("将所有交易记录导出为 CSV 分享，或从 CSV 文件导入（自动跳过重复记录）",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    Row {
                        androidx.compose.material3.Button(
                            onClick = { importLauncher.launch(arrayOf("text/*", "application/octet-stream")) }
                        ) { Text("📥 从文件导入") }
                        Spacer(Modifier.width(12.dp))
                        androidx.compose.material3.Button(
                            onClick = { settingsScope.launch { exportAndShareCsv(context) } }
                        ) { Text("📤 导出并分享") }
                    }
                }
            }
        }

        item { Text("📱 实时捕获调试（${entries.size} 条）", fontWeight = FontWeight.Bold, fontSize = 14.sp) }

        if (entries.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text("暂无捕获", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                }
            }
        } else {
            items(entries, key = { System.identityHashCode(it) }) { entry ->
                CaptureEntryRow(entry)
            }
        }
    }

    if (showPinSetup) {
        com.bookkeeping.app.ui.PinSetupDialog(
            onDismiss = { showPinSetup = false },
            onSaved = {
                hasPin = true
                com.bookkeeping.app.ui.setLockEnabled(context, true)
                lockEnabled = true
            }
        )
    }
}

@Composable
private fun PermissionRow(
    label: String,
    desc: String,
    granted: Boolean,
    onRequest: () -> Unit,
    onBack: () -> Unit = {}
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier.width(10.dp).height(10.dp).background(
                if (granted) IncomeGreen else ExpenseRed, RoundedCornerShape(5.dp)
            )
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(label, fontWeight = FontWeight.Medium, fontSize = 13.sp)
            Text(desc, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!granted) {
            AssistChip(onClick = { onRequest() }, label = { Text("去开启") })
        } else {
            Text("✓", color = IncomeGreen, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun CaptureEntryRow(entry: CaptureLogBus.CaptureEntry) {
    val color = if (entry.source == "NOTIFICATION") Color(0xFF1E88E5) else Color(0xFF8E24AA)
    val label = if (entry.source == "NOTIFICATION") "📱" else "💬"
    val detail = (entry.packageName ?: entry.sender ?: "").take(20)

    Card(shape = RoundedCornerShape(8.dp)) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, fontSize = 12.sp)
                Spacer(Modifier.width(4.dp))
                Text(entry.displayTime, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                Text(detail, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                entry.rawText.take(80),
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}
