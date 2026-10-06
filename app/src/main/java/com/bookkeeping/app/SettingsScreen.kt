package com.bookkeeping.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.bookkeeping.app.theme.SmsPurple
import com.bookkeeping.app.data.AppDatabase
import com.bookkeeping.app.sanitizeAmountInput
import com.bookkeeping.app.service.CaptureLogBus
import com.bookkeeping.app.theme.ExpenseRed
import com.bookkeeping.app.theme.IncomeGreen
import com.bookkeeping.app.theme.DangerRed
import com.bookkeeping.app.theme.TransferBlue
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    // 规则改成 Flow 订阅：新建/编辑/删除后自动刷新
    // （早先是一次性快照，保存后只刷新了服务侧缓存，页面列表不更新）
    val rules by remember { AppDatabase.getInstance(context).parseRuleDao().observeAll() }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    var accounts by remember { mutableStateOf<List<com.bookkeeping.app.data.entity.Account>>(emptyList()) }
    var ledgers by remember { mutableStateOf<List<com.bookkeeping.app.data.entity.Ledger>>(emptyList()) }
    var budgetMap by remember { mutableStateOf(mapOf<Long, Double>()) }
    var lockEnabled by remember { mutableStateOf(com.bookkeeping.app.ui.isLockEnabled(context)) }
    var bioEnabled by remember { mutableStateOf(com.bookkeeping.app.ui.isBiometricUnlockEnabled(context)) }
    var hasPin by remember { mutableStateOf(com.bookkeeping.app.ui.hasPinSet(context)) }
    var showPinSetup by remember { mutableStateOf(false) }
    val canBiometric = remember { com.bookkeeping.app.ui.canAuthenticateBiometric(context) }
    // 这些交互状态提升到屏幕顶层：LazyColumn 的 item 滑出视口后会被回收，
    // remember 里的值随之销毁 —— 早先选好的账本、填了一半的预算滚出屏幕再回来就没了
    var budgetLedgerId by remember { mutableStateOf<Long?>(null) }
    var budgetMenu by remember { mutableStateOf(false) }
    var rulesExpanded by remember { mutableStateOf(false) }

    fun loadAll() {
        scope.launch {
            // 复用同一个 db 实例，避免连续 4 次 getInstance
            val db = AppDatabase.getInstance(context)
            accounts = db.accountDao().getAllIncludingDisabled()
            ledgers = db.ledgerDao().getAll()
            budgetMap = db.budgetDao().getAll().associate { it.ledgerId to it.monthlyAmount }
        }
    }

    LaunchedEffect(Unit) { loadAll() }

    var smsGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    DisposableEffect(Unit) {
        val unsub = CaptureLogBus.subscribe { entries = it }
        onDispose { unsub() }
    }

    // ON_RESUME 时统一刷新所有依赖外部状态的字段：
    //  - 跨 App 跳系统设置（通知监听）返回时，listenerEnabled 自动刷新
    //  - SMS 权限弹窗返回时，smsGranted 自动刷新（与 smsLauncher 回调冗余兜底）
    //  - 隐私锁 / PIN / 生物识别被用户在系统层修改时也能跟随刷新
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event != Lifecycle.Event.ON_RESUME) return@LifecycleEventObserver
            listenerEnabled = isNotificationListenerEnabled(context)
            smsGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) ==
                PackageManager.PERMISSION_GRANTED
            hasPin = com.bookkeeping.app.ui.hasPinSet(context)
            lockEnabled = com.bookkeeping.app.ui.isLockEnabled(context)
            bioEnabled = com.bookkeeping.app.ui.isBiometricUnlockEnabled(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val smsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* 状态由 ON_RESUME 统一刷新 */ }

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
                        }
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
                                label = { Text(label) },
                                // 选中态跟随当前主题（此前 themeMode 传入却未读取，三个 chip 长得一样）
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = if (themeMode == mode) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    },
                                    labelColor = if (themeMode == mode) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))
                    // ── 自定义背景图 ──
                    BackgroundPickerSection()
                    Spacer(Modifier.height(14.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))
                    // ── 卡片背景透明度 ──
                    CardAlphaSection()
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
                                Text("⚠️ 尚未设置 PIN", fontSize = 11.sp, color = DangerRed)
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
                        // Account.name 有唯一索引且 DAO 是 REPLACE：直接 insert 同名行会
                        // 先把已有的「新账户」删掉再插入，旧 id 凭空消失，
                        // 而 transactions.accountId 不会跟着更新 → 那些交易记账账户全变空白。
                        // 这里先查重，名字已存在就往后加序号
                        val dao = AppDatabase.getInstance(context).accountDao()
                        val taken = dao.getAllIncludingDisabled().map { it.name }.toSet()
                        var name = "新账户"
                        var n = 2
                        while (name in taken) { name = "新账户$n"; n++ }
                        dao.insert(com.bookkeeping.app.data.entity.Account(
                            name = name,
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
                                }) { Text("删除", color = DangerRed, fontSize = 12.sp) }
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
                        // 同「新账户」：Ledger.name 唯一 + REPLACE，重复新增会静默替换掉已有账本，
                        // 旧 id 消失后原账本下的交易与预算都悬空
                        val dao = AppDatabase.getInstance(context).ledgerDao()
                        val taken = dao.getAll().map { it.name }.toSet()
                        var name = "新账本"
                        var n = 2
                        while (name in taken) { name = "新账本$n"; n++ }
                        dao.insert(com.bookkeeping.app.data.entity.Ledger(
                            name = name,
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
                                        AppDatabase.getInstance(context).budgetDao().deleteByLedger(led.id)
                                        loadAll()
                                    }
                                }) { Text("删除", color = DangerRed, fontSize = 12.sp) }
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
                    Text("选择账本设置每月预算，首页会显示已花费/剩余；设为 0 或留空 = 关闭该账本预算",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))

                    // ── 账本下拉选择 ──（budgetLedgerId / budgetMenu 已提升到屏幕顶层）
                    val budgetLedger = ledgers.firstOrNull { it.id == budgetLedgerId }

                    Box {
                        OutlinedButton(
                            onClick = { budgetMenu = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                budgetLedger?.let { "${it.icon} ${it.name}" } ?: "选择账本",
                                modifier = Modifier.weight(1f)
                            )
                            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                        }
                        DropdownMenu(expanded = budgetMenu, onDismissRequest = { budgetMenu = false }) {
                            ledgers.forEach { led ->
                                DropdownMenuItem(
                                    text = { Text("${led.icon} ${led.name}${if (led.isDefault) "（默认）" else ""}") },
                                    onClick = { budgetLedgerId = led.id; budgetMenu = false }
                                )
                            }
                            if (ledgers.isEmpty()) {
                                DropdownMenuItem(
                                    text = { Text("暂无账本") },
                                    onClick = { budgetMenu = false },
                                    enabled = false
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))

                    // ── 预算输入 + 保存（随选中账本预填） ──
                    var budgetText by remember(budgetLedgerId) {
                        mutableStateOf(
                            budgetLedgerId?.let { id -> budgetMap[id] ?: 0.0 }
                                ?.let { if (it > 0) String.format("%.0f", it) else "" } ?: ""
                        )
                    }
                    var budgetError by remember { mutableStateOf("") }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.OutlinedTextField(
                            value = budgetText,
                            onValueChange = { budgetText = sanitizeAmountInput(it) ?: return@OutlinedTextField },
                            label = { Text("月度预算（设为 0 关闭）") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            enabled = budgetLedgerId != null,
                            isError = budgetError.isNotEmpty(),
                            supportingText = if (budgetError.isNotEmpty()) ({ Text(budgetError) }) else null
                        )
                        Spacer(Modifier.width(8.dp))
                        androidx.compose.material3.Button(
                            onClick = {
                                val target = budgetLedgerId
                                if (target == null) {
                                    budgetError = "请先选择账本"
                                    return@Button
                                }
                                // 留空等同于设为 0（关闭预算）：上面的说明文案就是这么写的，
                                // 早先留空会走到 else 报「请输入有效金额」，与文案自相矛盾
                                val amt = budgetText.trim().let { if (it.isEmpty()) 0.0 else it.toDoubleOrNull() }
                                if (amt != null && amt >= 0) {
                                    scope.launch {
                                        setBudgetForLedger(context, target, amt)
                                        loadAll()
                                    }
                                    budgetError = ""
                                    android.widget.Toast.makeText(
                                        context,
                                        if (amt == 0.0) "${budgetLedger?.name} 预算已关闭"
                                        else "${budgetLedger?.name} 预算已设为 ¥${String.format("%.0f", amt)}",
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
                Column {
                    // ── 折叠头部：点击展开/收起规则清单 ──
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { rulesExpanded = !rulesExpanded }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "规则清单（${rules.size}）",
                            fontSize = 14.sp, fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            if (rulesExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                            contentDescription = if (rulesExpanded) "收起" else "展开"
                        )
                    }
                    if (rulesExpanded) {
                        if (rules.isEmpty()) {
                            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                                Text("暂无规则", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                            }
                        } else {
                            rules.forEach { rule ->
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
                                        // 直接用 Flow 给出的 rule.isEnabled 作为唯一数据源。
                                        // 早先这里用 remember 按位置缓存开关状态：列表重排
                                        // （新增/删除规则）后，某个位置会记住上一条规则的开关
                                        checked = rule.isEnabled,
                                        onCheckedChange = { newVal ->
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
        }

        // 注：原「数据管理」区的 CSV 导出/导入卡片已移除——
        // 汉堡菜单（HomeScreen 抽屉）里的 onExport / onImport 走的是同一组
        // exportAndShareCsv() / importCsvFromUri()，功能完全等价，避免重复入口。

        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "📱 实时捕获调试（${entries.size} 条）",
                    fontWeight = FontWeight.Bold, fontSize = 14.sp
                )
                Spacer(Modifier.weight(1f))
                if (entries.isNotEmpty()) {
                    TextButton(onClick = { CaptureLogBus.clear() }) {
                        Text("清空", fontSize = 12.sp)
                    }
                }
            }
        }

        if (entries.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text("暂无捕获", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                }
            }
        } else {
            items(entries, key = { it.uid }) { entry ->
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
    onRequest: () -> Unit
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
    val color = if (entry.source == "NOTIFICATION") TransferBlue else SmsPurple
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
