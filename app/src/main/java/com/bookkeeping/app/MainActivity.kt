package com.bookkeeping.app

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.bookkeeping.app.data.AppDatabase
import com.bookkeeping.app.data.TransactionDao
import com.bookkeeping.app.data.entity.Transaction
import com.bookkeeping.app.receiver.SmsReceiver
import com.bookkeeping.app.service.CaptureLogBus
import com.bookkeeping.app.service.DataBus
import com.bookkeeping.app.service.NotificationCaptureService
import com.bookkeeping.app.theme.BookkeepingTheme
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
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class MainActivity : androidx.fragment.app.FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        tryBindListener()
        setContent {
            val prefs = remember { getSharedPreferences("settings", MODE_PRIVATE) }
            var themeMode by remember { mutableStateOf(prefs.getString("theme_mode", "system") ?: "system") }

            val isDark = when (themeMode) {
                "dark" -> true
                "light" -> false
                else -> androidx.compose.foundation.isSystemInDarkTheme()
            }

            // ── 锁定门控：离开 App 自动上锁，回来需验证 ──
            val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
            var locked by remember {
                mutableStateOf(
                    com.bookkeeping.app.ui.isLockEnabled(applicationContext) && !com.bookkeeping.app.ui.LockState.unlocked
                )
            }
            androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
                val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                    when (event) {
                        androidx.lifecycle.Lifecycle.Event.ON_PAUSE ->
                            if (com.bookkeeping.app.ui.isLockEnabled(applicationContext)) {
                                com.bookkeeping.app.ui.LockState.unlocked = false
                            }
                        androidx.lifecycle.Lifecycle.Event.ON_RESUME ->
                            locked = com.bookkeeping.app.ui.isLockEnabled(applicationContext) &&
                                !com.bookkeeping.app.ui.LockState.unlocked
                        else -> {}
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }

            BookkeepingTheme(darkTheme = isDark) {
                if (locked) {
                    com.bookkeeping.app.ui.LockOverlay(onUnlocked = { locked = false })
                } else {
                    MainScaffold(
                        themeMode = themeMode,
                        onThemeChanged = { mode ->
                            themeMode = mode
                            prefs.edit().putString("theme_mode", mode).apply()
                        }
                    )
                }
            }
        }
    }

    private fun tryBindListener() {
        val component = ComponentName(this, NotificationCaptureService::class.java)
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: ""
        if (!flat.contains(component.flattenToString())) return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(Intent(this, NotificationCaptureService::class.java))
            } else {
                startService(Intent(this, NotificationCaptureService::class.java))
            }
        } catch (e: Throwable) {
            Log.e("Bookkeeping", "startService err: ${e.message}")
        }

        try {
            val cls = NotificationListenerService::class.java
            val m = (cls.declaredMethods + cls.methods).firstOrNull {
                it.name == "requestRebind" && it.parameterCount == 1
            }
            if (m != null) {
                m.isAccessible = true
                m.invoke(null, component)
            }
        } catch (e: Throwable) {
            Log.e("Bookkeeping", "rebind err: ${e.message}")
        }
    }
}

// ─── 底部导航框架 ────────────────────────────────────────────

private enum class Tab(val label: String) { HOME("首页"), CALENDAR("日历"), PENDING("待确认"), LIST("明细"), SETTINGS("设置") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScaffold(
    themeMode: String = "system",
    onThemeChanged: (String) -> Unit = {}
) {
    var currentTab by remember { mutableStateOf(Tab.HOME) }
    var showAddDialog by remember { mutableStateOf(false) }
    var secondaryOpen by remember { mutableStateOf(false) }
    var editingRule by remember { mutableStateOf<com.bookkeeping.app.data.entity.ParseRule?>(null) }
    var showRuleEditor by remember { mutableStateOf(false) }
    var pendingCount by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val db = remember { AppDatabase.getInstance(context) }

    fun refreshPending() {
        scope.launch { pendingCount = withContext(Dispatchers.IO) { db.transactionDao().getPendingCount() } }
    }
    LaunchedEffect(Unit) { refreshPending() }
    DisposableEffect(Unit) {
        val unsub = CaptureLogBus.subscribe { refreshPending() }
        onDispose { unsub() }
    }

    Scaffold(
        topBar = {
            // 首页有自定义顶栏（☰/账本/搜索/云），不再显示通用 TopAppBar
            if (currentTab != Tab.HOME) {
                TopAppBar(
                    title = {
                        Text(
                            when (currentTab) {
                                Tab.HOME -> "记账助手"
                                Tab.CALENDAR -> "记账日历"
                                Tab.PENDING -> "待确认 (${pendingCount})"
                                Tab.LIST -> "交易明细"
                                Tab.SETTINGS -> "设置"
                            },
                            fontWeight = FontWeight.Bold
                        )
                    }
                )
            }
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = currentTab == Tab.HOME,
                    onClick = { currentTab = Tab.HOME },
                    icon = { Icon(Icons.Default.Home, contentDescription = null) },
                    label = { Text("首页") }
                )
                NavigationBarItem(
                    selected = currentTab == Tab.CALENDAR,
                    onClick = { currentTab = Tab.CALENDAR },
                    icon = { Icon(Icons.Default.DateRange, contentDescription = null) },
                    label = { Text("日历") }
                )
                NavigationBarItem(
                    selected = currentTab == Tab.PENDING,
                    onClick = { currentTab = Tab.PENDING },
                    icon = {
                        Box {
                            Icon(Icons.Default.Warning, contentDescription = null)
                            if (pendingCount > 0) {
                                Badge(
                                    modifier = Modifier.align(Alignment.TopEnd).size(16.dp),
                                    containerColor = Color(0xFFE53935),
                                    contentColor = Color.White
                                ) {
                                    Text(pendingCount.toString(), fontSize = 9.sp)
                                }
                            }
                        }
                    },
                    label = { Text("待确认") }
                )
                NavigationBarItem(
                    selected = currentTab == Tab.LIST,
                    onClick = { currentTab = Tab.LIST },
                    icon = { Icon(Icons.Default.List, contentDescription = null) },
                    label = { Text("明细") }
                )
                NavigationBarItem(
                    selected = currentTab == Tab.SETTINGS,
                    onClick = { currentTab = Tab.SETTINGS },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text("设置") }
                )
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (currentTab) {
                Tab.HOME -> HomeScreen(
                    onNavigate = { currentTab = it },
                    onSecondaryScreenChanged = { secondaryOpen = it }
                )
                Tab.CALENDAR -> CalendarScreen()
                Tab.PENDING -> PendingScreen(onResolved = { refreshPending() })
                Tab.LIST -> TransactionListScreen()
                Tab.SETTINGS -> SettingsScreen(
                    themeMode = themeMode,
                    onThemeChanged = onThemeChanged,
                    onNewRule = { editingRule = null; showRuleEditor = true },
                    onEditRule = { editingRule = it; showRuleEditor = true }
                )
            }

            // 钱迹风格大按钮：可在屏幕上任意拖动
            if (!secondaryOpen && currentTab != Tab.SETTINGS && currentTab != Tab.PENDING && currentTab != Tab.CALENDAR) {
                val config = androidx.compose.ui.platform.LocalConfiguration.current
                val density = androidx.compose.ui.platform.LocalDensity.current
                val maxDragX = with(density) { (config.screenWidthDp.dp - 74.dp).toPx() }
                val maxDragY = with(density) { (config.screenHeightDp.dp - 170.dp).toPx() }
                var dragX by remember { mutableStateOf(0f) }
                var dragY by remember { mutableStateOf(0f) }

                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp)
                        .size(58.dp)
                        .offset { IntOffset(dragX.roundToInt(), dragY.roundToInt()) }
                        .pointerInput(Unit) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                dragX = (dragX + dragAmount.x).coerceIn(-maxDragX, 0f)
                                dragY = (dragY + dragAmount.y).coerceIn(-maxDragY, 0f)
                            }
                        }
                        .background(Color(0xFFDCEBFF), RoundedCornerShape(20.dp))
                        .clickable { showAddDialog = true },
                    contentAlignment = Alignment.Center
                ) {
                    Text("+", fontSize = 30.sp, color = Color(0xFF2E5AAC), fontWeight = FontWeight.Medium)
                }
            }
        }
    }

    if (showAddDialog) {
        ManualAddDialog(onDismiss = { showAddDialog = false })
    }

    if (showRuleEditor) {
        RuleEditDialog(
            initialRule = editingRule,
            onDismiss = { showRuleEditor = false },
            onSaved = { showRuleEditor = false; refreshServiceRules() }
        )
    }
}

// ─── 记账日历（月历视图） ─────────────────────────────────

@Composable
private fun CalendarScreen() {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val db = remember { AppDatabase.getInstance(context) }

    val cal = Calendar.getInstance()
    var viewYearMonth by remember { mutableStateOf(cal.clone() as Calendar) }
    var daySums by remember { mutableStateOf<Map<Long, Pair<Double, Double>>>(emptyMap()) } // dayKey → (expense, income)
    var selectedDayKey by remember { mutableStateOf<Long?>(null) }
    var dayTransactions by remember { mutableStateOf<List<Transaction>>(emptyList()) }

    // 计算当月所有天的收支
    fun loadMonthData() {
        scope.launch {
            val y = viewYearMonth.get(Calendar.YEAR)
            val m = viewYearMonth.get(Calendar.MONTH)
            val firstDay = Calendar.getInstance().apply { set(y, m, 1, 0, 0, 0); set(Calendar.MILLISECOND, 0) }
            val lastDay = Calendar.getInstance().apply { set(y, m, firstDay.getActualMaximum(Calendar.DAY_OF_MONTH), 23, 59, 59) }

            val sums = withContext(Dispatchers.IO) {
                val result = mutableMapOf<Long, Pair<Double, Double>>()
                val all = db.transactionDao().getByTimeRange(firstDay.timeInMillis, lastDay.timeInMillis)
                all.forEach { tx ->
                    val dayKey = tx.occurredAt / 86_400_000L
                    val (exp, inc) = result[dayKey] ?: 0.0 to 0.0
                    result[dayKey] = when (tx.type) {
                        Transaction.Type.EXPENSE -> exp + tx.amount to inc
                        Transaction.Type.INCOME -> exp to inc + tx.amount
                        else -> exp to inc
                    }
                }
                result
            }
            daySums = sums
        }
    }

    fun loadSelectedDay(key: Long) {
        scope.launch {
            selectedDayKey = key
            val start = key * 86_400_000L
            val end = start + 86_400_000L - 1
            dayTransactions = withContext(Dispatchers.IO) {
                db.transactionDao().getByTimeRange(start, end)
            }
        }
    }

    LaunchedEffect(viewYearMonth) { loadMonthData() }
    DisposableEffect(Unit) {
        val unsub = CaptureLogBus.subscribe { loadMonthData() }
        onDispose { unsub() }
    }

    Column(Modifier.fillMaxSize()) {
        // 月份切换 + 标题
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = {
                viewYearMonth = (viewYearMonth.clone() as Calendar).apply { add(Calendar.MONTH, -1) }
            }) { Icon(Icons.Default.ArrowBack, null) }
            val sdf = java.text.SimpleDateFormat("yyyy年 M月", java.util.Locale.CHINA)
            Text(sdf.format(viewYearMonth.time), fontSize = 18.sp, fontWeight = FontWeight.Bold)
            IconButton(onClick = {
                viewYearMonth = (viewYearMonth.clone() as Calendar).apply { add(Calendar.MONTH, 1) }
            }) { Icon(Icons.Default.ArrowForward, null) }
        }

        // 周标题行
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            listOf("日", "一", "二", "三", "四", "五", "六").forEach { w ->
                Text(w, modifier = Modifier.weight(1f), fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center)
            }
        }

        Spacer(Modifier.height(4.dp))

        // 月历网格
        val daysInMonth = viewYearMonth.getActualMaximum(Calendar.DAY_OF_MONTH)
        val firstDayOfWeek = (viewYearMonth.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY) // 0..6
        val totalCells = firstDayOfWeek + daysInMonth
        val gridCells = (0 until totalCells).map { idx ->
            val dayNum = if (idx < firstDayOfWeek) null else idx - firstDayOfWeek + 1
            dayNum
        }

        val today = Calendar.getInstance()
        val todayKey = today.timeInMillis / 86_400_000L

        LazyVerticalGrid(
            columns = GridCells.Fixed(7),
            modifier = Modifier.fillMaxWidth().height(280.dp).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            items(gridCells) { dayNum ->
                if (dayNum == null) {
                    Box(Modifier.aspectRatio(1f))
                } else {
                    val dayCal = (viewYearMonth.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, dayNum) }
                    val dayKey = dayCal.timeInMillis / 86_400_000L
                    val sums = daySums[dayKey]
                    val isToday = dayKey == todayKey
                    val isSelected = dayKey == selectedDayKey

                    Box(
                        modifier = Modifier
                            .aspectRatio(1f)
                            .background(
                                when {
                                    isSelected -> MaterialTheme.colorScheme.primaryContainer
                                    isToday -> MaterialTheme.colorScheme.surfaceVariant
                                    else -> Color.Transparent
                                },
                                RoundedCornerShape(8.dp)
                            )
                            .clickable { loadSelectedDay(dayKey) },
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                dayNum.toString(),
                                fontSize = 14.sp,
                                fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                                color = when {
                                    isSelected -> MaterialTheme.colorScheme.onPrimaryContainer
                                    isToday -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.onSurface
                                }
                            )
                            // 收支小点（两列显示）
                            if (sums != null && (sums.first > 0.01 || sums.second > 0.01)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                    if (sums.first > 0.01) Box(Modifier.size(5.dp).background(ExpenseRed, CircleShape))
                                    if (sums.second > 0.01) Box(Modifier.size(5.dp).background(IncomeGreen, CircleShape))
                                }
                            } else if (sums != null) {
                                // 只有极小金额也显示一个小点
                                Box(Modifier.size(4.dp).background(
                                    MaterialTheme.colorScheme.onSurfaceVariant, CircleShape))
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // 图例
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).background(ExpenseRed, CircleShape))
                Spacer(Modifier.width(4.dp))
                Text("支出", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(12.dp))
                Box(Modifier.size(6.dp).background(IncomeGreen, CircleShape))
                Spacer(Modifier.width(4.dp))
                Text("收入", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Spacer(Modifier.height(8.dp))
        HorizontalDivider()

        // 选中日期详情
        val selKey = selectedDayKey
        if (selKey != null) {
            val selCal = Calendar.getInstance().apply { timeInMillis = selKey * 86_400_000L }
            val selSums = daySums[selKey] ?: (0.0 to 0.0)
            val selExp = selSums.first
            val selInc = selSums.second

            Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "${selCal.get(Calendar.YEAR)}年${selCal.get(Calendar.MONTH) + 1}月${selCal.get(Calendar.DAY_OF_MONTH)}日",
                        fontSize = 15.sp, fontWeight = FontWeight.Medium
                    )
                    Text(
                        buildString {
                            if (selExp > 0.01) append("-¥${String.format("%.2f", selExp)}")
                            if (selInc > 0.01) append(" +¥${String.format("%.2f", selInc)}")
                            if (selExp < 0.01 && selInc < 0.01) append("无交易")
                        },
                        fontSize = 14.sp, fontWeight = FontWeight.Medium,
                        color = when {
                            selExp > 0.01 && selInc > 0.01 -> MaterialTheme.colorScheme.onSurface
                            selExp > 0.01 -> ExpenseRed
                            selInc > 0.01 -> IncomeGreen
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }

                if (dayTransactions.isEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                        Text("这一天没有交易", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        items(dayTransactions) { tx ->
                            TransactionItem(tx = tx)
                        }
                    }
                }
            }
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("点选日期查看当日明细", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ─── 首页：今日汇总 + 最近交易 ─────────────────────────────────

@Composable
private fun HomeScreen(
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
                        Text(selectedLedgerName, fontWeight = FontWeight.Normal, fontSize = 18.sp)
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
                        String.format("%.2f", balance),
                        fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Color.White
                    )
                    Spacer(Modifier.height(1.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("本月收入  ", fontSize = 12.sp, color = Color.White.copy(alpha = 0.85f))
                            Text(String.format("%.2f", monthIncome), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color.White)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("本月支出  ", fontSize = 12.sp, color = Color.White.copy(alpha = 0.85f))
                            Text(String.format("%.2f", monthExpense), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color.White)
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
                    Text(String.format("%.2f", todayIncome), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = IncomeGreen)
                    Spacer(Modifier.width(14.dp))
                    Text("支 ", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(String.format("%.2f", todayExpense), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = ExpenseRed)
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

/** 首页快捷入口（彩色圆底） */
@Composable
private fun androidx.compose.foundation.layout.RowScope.HomeShortcut(
    icon: String,
    label: String,
    bg: Color,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable { onClick() }
    ) {
        Box(
            Modifier.size(48.dp).background(bg, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            when (icon) {
                "↓" -> Text("↓", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2E9E63))
                "↑" -> Text("↑", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color(0xFFD84B4B))
                "grid" -> {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Box(Modifier.size(7.dp).background(Color(0xFF6B7280), RoundedCornerShape(1.5.dp)))
                            Box(Modifier.size(7.dp).background(Color(0xFF6B7280), RoundedCornerShape(1.5.dp)))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Box(Modifier.size(7.dp).background(Color(0xFF6B7280), RoundedCornerShape(1.5.dp)))
                            Box(Modifier.size(7.dp).background(Color(0xFF6B7280), RoundedCornerShape(1.5.dp)))
                        }
                    }
                }
                else -> Text(icon, fontSize = 21.sp)
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
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
                            Text("$prefix¥${String.format("%.2f", tx.amount)}",
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
                        "剩余 ${String.format("%.2f", (budget - monthExpense).coerceAtLeast(0.0))}",
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
                    Text("${String.format("%.2f", monthExpense)} / ${String.format("%.2f", budget)}",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val cal = Calendar.getInstance()
                    val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
                    val remainingDays = (daysInMonth - cal.get(Calendar.DAY_OF_MONTH)).coerceAtLeast(1)
                    Text("剩余日均 ${String.format("%.2f", (budget - monthExpense).coerceAtLeast(0.0) / remainingDays)}",
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

// ─── 设置（调试面板 + 权限检查） ──────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
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
                                        String.format("余额 ¥%.2f", acc.balance),
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
                                    Text("¥${String.format("%.2f", it)}", fontSize = 11.sp,
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

