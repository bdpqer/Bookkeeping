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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

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

    var todayExpense by remember { mutableStateOf(0.0) }
    var todayIncome by remember { mutableStateOf(0.0) }
    var monthExpense by remember { mutableStateOf(0.0) }
    var monthIncome by remember { mutableStateOf(0.0) }
    var allTransactions by remember { mutableStateOf<List<Transaction>>(emptyList()) }
    var recentTransactions by remember { mutableStateOf<List<Transaction>>(emptyList()) }
    var captureEntries by remember { mutableStateOf(CaptureLogBus.entries) }
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

    // 账本切换（null = 全部账本）
    var ledgers by remember { mutableStateOf<List<com.bookkeeping.app.data.entity.Ledger>>(emptyList()) }
    var selectedLedgerId by remember { mutableStateOf<Long?>(null) }
    val selectedLedgerName =
        if (selectedLedgerId == null) "全部账本"
        else ledgers.firstOrNull { it.id == selectedLedgerId }?.name ?: "全部账本"

    fun refresh() {
        scope.launch {
            val list = withContext(Dispatchers.IO) {
                if (selectedLedgerId == null) db.transactionDao().getAll()
                else db.transactionDao().getByLedger(selectedLedgerId!!)
            }
            allTransactions = list
            val todayStart = startOfToday()
            val todayEnd = endOfToday()
            val monthStart = startOfMonth()
            val monthEnd = endOfMonth()
            todayExpense = list.filter { it.type == Transaction.Type.EXPENSE && it.occurredAt in todayStart..todayEnd }.sumOf { it.amount }
            todayIncome = list.filter { it.type == Transaction.Type.INCOME && it.occurredAt in todayStart..todayEnd }.sumOf { it.amount }
            monthExpense = list.filter { it.type == Transaction.Type.EXPENSE && it.occurredAt in monthStart..monthEnd }.sumOf { it.amount }
            monthIncome = list.filter { it.type == Transaction.Type.INCOME && it.occurredAt in monthStart..monthEnd }.sumOf { it.amount }
            recentTransactions = list.take(8)
        }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            ledgers = db.ledgerDao().getAll()
            selectedLedgerId = ledgers.firstOrNull { it.isDefault }?.id
        }
        refresh()
    }

    // 订阅 CaptureLogBus 变化时也刷新（自动入账后首页实时更新）
    DisposableEffect(Unit) {
        val unsub = CaptureLogBus.subscribe { captureEntries = it }
        onDispose { unsub() }
    }
    LaunchedEffect(captureEntries.size, selectedLedgerId, DataBus.dataTick) { refresh() }

    // CSV 导入：选文件 → 解析入库
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val (ok, skip) = importCsvFromUri(context, uri)
                android.widget.Toast.makeText(
                    context, "导入完成：新增 $ok 条，跳过重复 $skip 条", android.widget.Toast.LENGTH_LONG
                ).show()
                refresh()
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
                        "📥 从备份文件恢复", fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showBackupDialog = false; restoreLauncher.launch(arrayOf("*/*")) }
                            .padding(vertical = 14.dp)
                    )
                    Text(
                        "备份包含全部数据（交易/规则/账户/账本等）；恢复会覆盖当前所有数据",
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
        com.bookkeeping.app.ui.LoanScreen(ledgers = ledgers, initialLedgerId = currentLedgerIdForPages, onClose = { showLoanScreen = false; refresh() })
    }
    if (showRecurringScreen) {
        com.bookkeeping.app.ui.RecurringScreen(onClose = { showRecurringScreen = false; refresh() })
    }
    if (showReimburseScreen) {
        com.bookkeeping.app.ui.ReimburseScreen(ledgers = ledgers, initialLedgerId = currentLedgerIdForPages, onClose = { showReimburseScreen = false; refresh() })
    }
    if (showReceivableScreen) {
        com.bookkeeping.app.ui.ReceivableScreen(ledgers = ledgers, initialLedgerId = currentLedgerIdForPages, onClose = { showReceivableScreen = false; refresh() }, initialTab = receivableInitialTab)
    }
    if (showRecycleBinScreen) {
        com.bookkeeping.app.ui.RecycleBinScreen(onClose = { showRecycleBinScreen = false; refresh() })
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
        results = if (query.isBlank()) emptyList()
        else withContext(Dispatchers.IO) {
            val q = query.trim()
            db.transactionDao().getAll().filter {
                it.merchant.contains(q, true) ||
                it.category.contains(q, true) ||
                (it.note?.contains(q, true) == true) ||
                String.format("%.2f", it.amount).startsWith(q)
            }.take(30)
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

@Composable
private fun TodaySummaryCard(expense: Double, income: Double) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(Modifier.padding(20.dp)) {
            Text("今日", fontSize = 14.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("支出", fontSize = 12.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(
                        "¥${String.format("%.2f", expense)}",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = ExpenseRed
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("收入", fontSize = 12.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(
                        "¥${String.format("%.2f", income)}",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = IncomeGreen
                    )
                }
            }
            Divider(Modifier.padding(vertical = 12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("结余", fontSize = 12.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(
                    "¥${String.format("%.2f", income - expense)}",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

// ─── 账单报表（时间筛选 + 圆环图） ─────────────────────────

private enum class RangeMode(val label: String) {
    TODAY("今天"), WEEK("本周"),
    MONTH_PICK("月度"), YEAR_PICK("年度"), CUSTOM("自选")
}

private data class ReportData(
    val expense: Double,
    val income: Double,
    val expSlices: List<TransactionDao.CategorySum>,
    val incSlices: List<TransactionDao.CategorySum>
)

private val ReportPalette = listOf(
    Color(0xFF4E79A7), Color(0xFFF28E2B), Color(0xFFE15759), Color(0xFF76B7B2),
    Color(0xFF59A14F), Color(0xFFEDC948), Color(0xFFB07AA1), Color(0xFFFF9DA7),
    Color(0xFF9C755F), Color(0xFFBAB0AC)
)

private fun reportColor(i: Int): Color = ReportPalette[i % ReportPalette.size]

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReportStatsCard(all: List<Transaction>) {
    var mode by remember { mutableStateOf(RangeMode.MONTH_PICK) }
    var pickedAnchor by remember { mutableStateOf(System.currentTimeMillis()) }
    var customStart by remember { mutableStateOf<Long?>(null) }
    var customEnd by remember { mutableStateOf<Long?>(null) }
    var showExpense by remember { mutableStateOf(true) }
    var selectedCat by remember { mutableStateOf<String?>(null) }

    // 日期弹窗：0 无；1 选月度；2 选年度；3 自定义-起；4 自定义-止
    var picker by remember { mutableIntStateOf(0) }

    val range = remember(mode, pickedAnchor, customStart, customEnd) {
        computeRange(mode, pickedAnchor, customStart, customEnd)
    }

    val data = remember(all, range.first, range.second) {
        val inRange = all.filter { it.occurredAt in range.first..range.second }
        ReportData(
            expense = inRange.filter { it.type == Transaction.Type.EXPENSE }.sumOf { it.amount },
            income = inRange.filter { it.type == Transaction.Type.INCOME }.sumOf { it.amount },
            expSlices = buildSlices(inRange, Transaction.Type.EXPENSE),
            incSlices = buildSlices(inRange, Transaction.Type.INCOME)
        )
    }

    val slices = if (showExpense) data.expSlices else data.incSlices
    val total = if (showExpense) data.expense else data.income
    LaunchedEffect(mode) { selectedCat = null }

    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("账单报表", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))

            // 时间模式 chips（横向可滑动）
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                RangeMode.entries.forEach { m ->
                    FilterChip(
                        selected = mode == m,
                        onClick = { mode = m },
                        label = { Text(m.label, fontSize = 12.sp) }
                    )
                }
            }

            // 月度/年度/自选 的具体选择行
            if (mode != RangeMode.TODAY && mode != RangeMode.WEEK) {
                Spacer(Modifier.height(8.dp))
                val subText = when (mode) {
                    RangeMode.MONTH_PICK ->
                        "📅 " + SimpleDateFormat("yyyy年M月", Locale.getDefault()).format(Date(pickedAnchor))
                    RangeMode.YEAR_PICK ->
                        "📅 " + SimpleDateFormat("yyyy年", Locale.getDefault()).format(Date(pickedAnchor))
                    else -> {
                        val s = customStart; val e = customEnd
                        if (s != null && e != null)
                            "📅 " + SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(s)) +
                            " ~ " + SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date(e))
                        else "📅 点击选择起止日期"
                    }
                }
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().clickable {
                        picker = when (mode) {
                            RangeMode.MONTH_PICK -> 1
                            RangeMode.YEAR_PICK -> 2
                            else -> 3
                        }
                    }
                ) {
                    Text(subText, fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                }
            }

            // 支出 / 收入 切换
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                MiniSegmented("支出", showExpense) { showExpense = true }
                Spacer(Modifier.width(12.dp))
                MiniSegmented("收入", !showExpense) { showExpense = false }
            }

            Spacer(Modifier.height(10.dp))
            if (total <= 0) {
                Box(Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
                    Text("该时段暂无${if (showExpense) "支出" else "收入"}记录",
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                // 圆环图 + 中心金额
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    DonutChart(slices = slices, modifier = Modifier.size(180.dp))
                    val sel = selectedCat?.let { c -> slices.firstOrNull { it.category == c } }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (showExpense) "支出" else "收入",
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("¥${"%.2f".format(sel?.total ?: total)}",
                            fontSize = 19.sp, fontWeight = FontWeight.Bold,
                            color = if (showExpense) ExpenseRed else IncomeGreen)
                        if (sel != null) {
                            Text("${(sel.total / total * 100).toInt()}% · 再点取消",
                                fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                // 分类图例
                Column {
                    slices.forEachIndexed { i, s ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable {
                                    selectedCat = if (selectedCat == s.category) null else s.category
                                }
                                .padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(Modifier.size(10.dp).background(reportColor(i), CircleShape))
                            Spacer(Modifier.width(8.dp))
                            Text(categoryEmoji(s.category), fontSize = 13.sp)
                            Spacer(Modifier.width(4.dp))
                            Text(s.category, fontSize = 12.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                fontWeight = if (selectedCat == s.category) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier.weight(1f))
                            Text("¥${"%.2f".format(s.total)}", fontSize = 12.sp)
                            Spacer(Modifier.width(8.dp))
                            Text("${(s.total / total * 100).toInt()}%",
                                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(36.dp), textAlign = TextAlign.End)
                        }
                    }
                }
            }

            // 汇总：支出 / 收入 / 结余
            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth()) {
                ReportSummary("支出", data.expense, ExpenseRed)
                ReportSummary("收入", data.income, IncomeGreen)
                ReportSummary("结余", data.income - data.expense, Color(0xFF2E5AAC))
            }
        }
    }

    // 日期选择弹窗
    if (picker in 1..4) {
        val initial = when (picker) {
            1 -> pickedAnchor
            2 -> pickedAnchor
            3 -> customStart ?: System.currentTimeMillis()
            else -> customEnd?.let { it - 86_399_999 } ?: System.currentTimeMillis()
        }
        ReportDatePicker(
            initial = initial,
            title = when (picker) {
                1 -> "选择月份（任意日期即可）"
                2 -> "选择年份（任意日期即可）"
                3 -> "选择开始日期"
                else -> "选择结束日期"
            },
            onDismiss = { picker = 0 },
            onConfirm = { dayStart ->
                when (picker) {
                    1, 2 -> { pickedAnchor = dayStart; picker = 0 }
                    3 -> { customStart = dayStart; picker = 4 }
                    else -> {
                        var s = customStart
                        if (s != null && dayStart < s) s = dayStart
                        customStart = s
                        customEnd = dayStart + 86_399_999
                        picker = 0
                    }
                }
            }
        )
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.ReportSummary(
    label: String, value: Double, color: Color
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.weight(1f)
    ) {
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("¥${"%.2f".format(value)}", fontSize = 14.sp,
            fontWeight = FontWeight.Bold, color = color)
    }
}

@Composable
private fun MiniSegmented(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) Color(0xFF2E5AAC) else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 6.dp)
    ) {
        Text(label, fontSize = 13.sp,
            color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 圆环图：各分类按占比画弧，弧间留 3° 间隙 */
@Composable
private fun DonutChart(slices: List<TransactionDao.CategorySum>, modifier: Modifier = Modifier) {
    val total = slices.sumOf { it.total }.coerceAtLeast(0.01)
    Canvas(modifier) {
        val stroke = 34.dp.toPx()
        val gap = 3f
        val inset = stroke / 2f
        val arcSize = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke)
        var start = -90f
        slices.forEachIndexed { i, s ->
            val sweep = (s.total / total * 360f).toFloat() - gap
            drawArc(
                color = reportColor(i),
                startAngle = start,
                sweepAngle = sweep.coerceAtLeast(1f),
                useCenter = false,
                topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                size = arcSize,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke)
            )
            start += sweep + gap
        }
    }
}

/** 分类切片：Top8 之外合并为「其他」 */
private fun buildSlices(
    inRange: List<Transaction>,
    type: Transaction.Type
): List<TransactionDao.CategorySum> {
    val grouped = inRange.filter { it.type == type }
        .groupBy { it.category }
        .map { TransactionDao.CategorySum(it.key, it.value.sumOf { v -> v.amount }) }
        .sortedByDescending { it.total }
    if (grouped.size <= 9) return grouped
    val rest = grouped.drop(8).sumOf { it.total }
    return grouped.take(8) + TransactionDao.CategorySum("其他", rest)
}

/** 按模式计算 [开始, 结束] 毫秒区间（本地时间） */
private fun computeRange(
    mode: RangeMode,
    anchor: Long,
    customStart: Long?,
    customEnd: Long?
): Pair<Long, Long> {
    val cal = Calendar.getInstance()
    fun floorDay(): Long {
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
    return when (mode) {
        RangeMode.TODAY -> {
            cal.timeInMillis = System.currentTimeMillis()
            val s = floorDay()
            s to s + 86_399_999
        }
        RangeMode.WEEK -> {
            cal.timeInMillis = System.currentTimeMillis()
            cal.firstDayOfWeek = Calendar.MONDAY
            cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            val s = floorDay()
            s to s + 7 * 86_400_000L - 1
        }
        RangeMode.MONTH_PICK -> {
            cal.timeInMillis = anchor
            cal.set(Calendar.DAY_OF_MONTH, 1)
            val s = floorDay()
            cal.add(Calendar.MONTH, 1)
            s to cal.timeInMillis - 1
        }
        RangeMode.YEAR_PICK -> {
            cal.timeInMillis = anchor
            cal.set(Calendar.MONTH, Calendar.JANUARY)
            cal.set(Calendar.DAY_OF_MONTH, 1)
            val s = floorDay()
            cal.add(Calendar.YEAR, 1)
            s to cal.timeInMillis - 1
        }
        RangeMode.CUSTOM -> {
            if (customStart != null && customEnd != null) customStart to customEnd
            else {
                cal.timeInMillis = System.currentTimeMillis()
                val s = floorDay()
                s to s + 86_399_999
            }
        }
    }
}

/** Material3 日期选择弹窗，确认时回传所选日期的本地零点 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReportDatePicker(
    initial: Long,
    title: String,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit
) {
    val state = rememberDatePickerState(initialSelectedDateMillis = localDayToUtc(initial))
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { onConfirm(utcToLocalDayStart(it)) }
            }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    ) {
        DatePicker(
            state = state,
            title = { Text(title, Modifier.padding(start = 24.dp, top = 16.dp)) }
        )
    }
}

/** 本地零点毫秒 → UTC 零点毫秒（DatePicker 用 UTC） */
private fun localDayToUtc(localDayStart: Long): Long {
    val c = Calendar.getInstance().apply { timeInMillis = localDayStart }
    return Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
        clear()
        set(c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH))
    }.timeInMillis
}

/** UTC 零点毫秒 → 本地零点毫秒 */
private fun utcToLocalDayStart(utc: Long): Long {
    val c = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply { timeInMillis = utc }
    return Calendar.getInstance().apply {
        clear()
        set(c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH))
    }.timeInMillis
}

// ─── 单条交易 ─────────────────────────────────────────────

@Composable
private fun TransactionItem(
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

private fun categoryEmoji(category: String): String = when {
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
private fun categoriesFor(type: Transaction.Type): List<String> = when (type) {
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

// ─── 待确认队列 ─────────────────────────────────────────────

@Composable
private fun PendingScreen(onResolved: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val db = remember { AppDatabase.getInstance(context) }
    var pending by remember { mutableStateOf<List<Transaction>>(emptyList()) }
    var editingTx by remember { mutableStateOf<Transaction?>(null) }

    fun refresh() {
        scope.launch {
            pending = withContext(Dispatchers.IO) { db.transactionDao().getPending() }
            onResolved()
        }
    }

    LaunchedEffect(Unit) { refresh() }
    DisposableEffect(Unit) {
        val unsub = CaptureLogBus.subscribe { refresh() }
        onDispose { unsub() }
    }

    Column(Modifier.fillMaxSize()) {
        // 一键全部确认
        if (pending.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.End
            ) {
                androidx.compose.material3.TextButton(onClick = {
                    scope.launch {
                    db.transactionDao().confirmAll()
                    DataBus.notifyDataChanged()
                    refresh()
                }
                }) { Text("✓ 全部确认") }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (pending.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("🎉", fontSize = 48.sp)
                            Text("没有待确认的交易", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            } else {
                items(pending) { tx ->
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { editingTx = tx },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        )
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(categoryEmoji(tx.category), fontSize = 20.sp)
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        tx.category,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                                Text(
                                    String.format("%s¥%.2f", if (tx.type == Transaction.Type.EXPENSE) "-" else "+", tx.amount),
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = when (tx.type) {
                                        Transaction.Type.EXPENSE -> ExpenseRed
                                        Transaction.Type.INCOME -> IncomeGreen
                                        Transaction.Type.TRANSFER -> Color(0xFFFF9800)
                                    }
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                tx.rawText.take(100),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2
                            )
                            Row(
                                Modifier.fillMaxWidth().padding(top = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                AssistChip(
                                    onClick = {
                                        scope.launch {
                                            db.transactionDao().confirm(tx.id)
                                            refresh()
                                        }
                                    },
                                    label = { Text("✓ 确认入账") }
                                )
                                AssistChip(
                                    onClick = { editingTx = tx },
                                    label = { Text("✎ 编辑后确认") }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (editingTx != null) {
        TransactionEditDialog(
            tx = editingTx!!.copy(confirmed = true),
            onDismiss = { editingTx = null },
            onSaved = { editingTx = null; refresh() },
            onDeleted = { editingTx = null; refresh() }
        )
    }
}

// ─── 交易列表 ─────────────────────────────────────────────

@Composable
private fun TransactionListScreen() {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val db = remember { AppDatabase.getInstance(context) }
    var transactions by remember { mutableStateOf<List<Transaction>>(emptyList()) }
    var editingTx by remember { mutableStateOf<Transaction?>(null) }
    var keyword by remember { mutableStateOf("") }
    var ledgers by remember { mutableStateOf<List<com.bookkeeping.app.data.entity.Ledger>>(emptyList()) }
    var selectedLedgerId by remember { mutableStateOf<Long?>(null) }
    // 时间筛选：null = 全部时间；默认显示当月
    var timeMode by remember { mutableStateOf<RangeMode?>(RangeMode.MONTH_PICK) }
    var monthAnchor by remember { mutableStateOf(System.currentTimeMillis()) }
    var showMonthPicker by remember { mutableStateOf(false) }
    // 自定义日期范围
    var customStart by remember { mutableStateOf<Long?>(null) }
    var customEnd by remember { mutableStateOf<Long?>(null) }
    var customPicker by remember { mutableIntStateOf(0) } // 0 无；1 选开始；2 选结束

    val timeRange = remember(timeMode, monthAnchor, customStart, customEnd) {
        timeMode?.let { computeRange(it, monthAnchor, customStart, customEnd) }
    }

    LaunchedEffect(Unit) {
        ledgers = withContext(Dispatchers.IO) { db.ledgerDao().getAll() }
    }

    fun refresh() {
        scope.launch {
            transactions = withContext(Dispatchers.IO) {
                val list = when {
                    selectedLedgerId != null && keyword.isNotBlank() ->
                        db.transactionDao().searchByLedger(selectedLedgerId!!, keyword.trim())
                    selectedLedgerId != null ->
                        db.transactionDao().getByLedger(selectedLedgerId!!)
                    keyword.isBlank() -> db.transactionDao().getAll()
                    else -> db.transactionDao().search(keyword.trim())
                }
                if (timeRange != null) list.filter { it.occurredAt in timeRange.first..timeRange.second } else list
            }
        }
    }

    LaunchedEffect(keyword, selectedLedgerId, timeMode, monthAnchor, customStart, customEnd, DataBus.dataTick) { refresh() }
    DisposableEffect(Unit) {
        val unsub = CaptureLogBus.subscribe { refresh() }
        onDispose { unsub() }
    }

    Column(Modifier.fillMaxSize()) {
        // 当前筛选结果的汇总：支出 / 收入 / 结余
        val sumExpense = transactions.filter { it.type == Transaction.Type.EXPENSE }.sumOf { it.amount }
        val sumIncome = transactions.filter { it.type == Transaction.Type.INCOME }.sumOf { it.amount }
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
        ) {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                ReportSummary("支出", sumExpense, ExpenseRed)
                ReportSummary("收入", sumIncome, IncomeGreen)
                ReportSummary("结余", sumIncome - sumExpense, Color(0xFF2E5AAC))
            }
        }

        // 标题 + 账本下拉筛选
        var ledgerMenu by remember { mutableStateOf(false) }
        val ledgerName =
            if (selectedLedgerId == null) "全部账本"
            else ledgers.firstOrNull { it.id == selectedLedgerId }?.name ?: "全部账本"
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(Modifier.width(6.dp))
            Box {
                Row(
                    Modifier.clickable { ledgerMenu = true },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(ledgerName, fontSize = 13.sp, color = Color(0xFF2E5AAC))
                    Text(" ▾", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(expanded = ledgerMenu, onDismissRequest = { ledgerMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("📚 全部账本") },
                        onClick = { selectedLedgerId = null; ledgerMenu = false }
                    )
                    ledgers.forEach { led ->
                        DropdownMenuItem(
                            text = { Text("${led.icon} ${led.name}") },
                            onClick = { selectedLedgerId = led.id; ledgerMenu = false }
                        )
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            // 时间下拉筛选
            Box {
                var timeMenu by remember { mutableStateOf(false) }
                val timeLabel = when (timeMode) {
                    null -> "全部时间"
                    RangeMode.TODAY -> "今天"
                    RangeMode.WEEK -> "本周"
                    RangeMode.MONTH_PICK ->
                        "📅 " + SimpleDateFormat("yyyy年M月", Locale.getDefault()).format(Date(monthAnchor))
                    RangeMode.YEAR_PICK ->
                        "📅 " + SimpleDateFormat("yyyy年", Locale.getDefault()).format(Date(monthAnchor))
                    RangeMode.CUSTOM -> {
                        val s = customStart; val e = customEnd
                        if (s != null && e != null)
                            "📅 " + SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date(s)) +
                                " ~ " + SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date(e))
                        else "📅 自定义"
                    }
                }
                Row(
                    Modifier.clickable { timeMenu = true },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(timeLabel, fontSize = 13.sp, color = Color(0xFF2E5AAC))
                    Text(" ▾", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(expanded = timeMenu, onDismissRequest = { timeMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("今天") },
                        onClick = { timeMode = RangeMode.TODAY; timeMenu = false }
                    )
                    DropdownMenuItem(
                        text = { Text("本周") },
                        onClick = { timeMode = RangeMode.WEEK; timeMenu = false }
                    )
                    DropdownMenuItem(
                        text = { Text("本月") },
                        onClick = {
                            timeMode = RangeMode.MONTH_PICK
                            monthAnchor = System.currentTimeMillis()
                            timeMenu = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("选择月份…") },
                        onClick = { timeMode = RangeMode.MONTH_PICK; timeMenu = false; showMonthPicker = true }
                    )
                    DropdownMenuItem(
                        text = { Text("今年") },
                        onClick = {
                            timeMode = RangeMode.YEAR_PICK
                            monthAnchor = System.currentTimeMillis()
                            timeMenu = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("自定义日期范围") },
                        onClick = { timeMode = RangeMode.CUSTOM; timeMenu = false; customPicker = 1 }
                    )
                    DropdownMenuItem(
                        text = { Text("全部时间") },
                        onClick = { timeMode = null; timeMenu = false }
                    )
                }
            }
        }

        // 搜索框
        androidx.compose.material3.OutlinedTextField(
            value = keyword,
            onValueChange = { keyword = it },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            label = { Text("搜索商户/备注/关键词") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp)
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            if (transactions.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                        Text(
                            when {
                                keyword.isNotBlank() -> "没找到匹配的交易"
                                timeRange != null -> "该时段暂无交易记录"
                                else -> "暂无交易记录"
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                items(transactions) { tx ->
                    TransactionItem(
                        tx = tx,
                        onClick = { editingTx = tx }
                    )
                }
            }
        }
    }

    if (editingTx != null) {
        TransactionEditDialog(
            tx = editingTx!!,
            onDismiss = { editingTx = null },
            onSaved = { editingTx = null; refresh() },
            onDeleted = { editingTx = null; refresh() }
        )
    }

    if (showMonthPicker) {
        ReportDatePicker(
            initial = monthAnchor,
            title = "选择月份（任意日期即可）",
            onDismiss = { showMonthPicker = false },
            onConfirm = { dayStart ->
                monthAnchor = dayStart
                showMonthPicker = false
            }
        )
    }

    // 自定义日期范围：先选开始，再选结束
    if (customPicker in 1..2) {
        ReportDatePicker(
            initial = if (customPicker == 1) customStart ?: System.currentTimeMillis()
                      else customEnd?.let { it - 86_399_999 } ?: System.currentTimeMillis(),
            title = if (customPicker == 1) "选择开始日期" else "选择结束日期",
            onDismiss = { customPicker = 0 },
            onConfirm = { dayStart ->
                if (customPicker == 1) {
                    customStart = dayStart
                    customPicker = 2
                } else {
                    var s = customStart
                    if (s != null && dayStart < s) s = dayStart
                    customStart = s
                    customEnd = dayStart + 86_399_999
                    customPicker = 0
                }
            }
        )
    }
}

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
    var accounts by remember { mutableStateOf<List<com.bookkeeping.app.data.entity.Account>>(emptyList()) }
    var ledgers by remember { mutableStateOf<List<com.bookkeeping.app.data.entity.Ledger>>(emptyList()) }
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
                            textStyle = androidx.compose.ui.text.TextStyle(
                                fontSize = 28.sp, fontWeight = FontWeight.Bold, color = amountColor
                            ),
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal,
                                imeAction = androidx.compose.ui.text.input.ImeAction.Done
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(amountFocus),
                            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
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
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("备注（可选）") },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp),
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
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
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

// 分类选择用的横向 Row（简化版，不用真正 LazyRow）
@Composable
private fun <T> LazyColumnRow(items: List<T>, itemContent: @Composable (T) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items.forEach { itemContent(it) }
    }
}

// ─── 工具方法 ─────────────────────────────────────────────

fun isNotificationListenerEnabled(context: Context): Boolean {
    val cn = ComponentName(context, NotificationCaptureService::class.java)
    val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: ""
    return flat.contains(cn.flattenToString())
}

// ─── 预算管理 ────────────────────────────────────────────────

private const val PREFS_BUDGET = "budget_prefs"
private const val KEY_MONTHLY_BUDGET = "monthly_budget"

fun getMonthlyBudget(context: Context): Double {
    val prefs = context.getSharedPreferences(PREFS_BUDGET, Context.MODE_PRIVATE)
    return prefs.getFloat(KEY_MONTHLY_BUDGET, 0f).toDouble()
}

fun setMonthlyBudget(context: Context, amount: Double) {
    context.getSharedPreferences(PREFS_BUDGET, Context.MODE_PRIVATE)
        .edit().putFloat(KEY_MONTHLY_BUDGET, amount.toFloat()).apply()
}

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

    var amountText by remember { mutableStateOf(String.format("%.2f", tx.amount)) }
    var selectedType by remember { mutableStateOf(tx.type) }
    var selectedCategory by remember { mutableStateOf(tx.category) }
    var merchant by remember { mutableStateOf(tx.merchant) }
    var note by remember { mutableStateOf(tx.note ?: "") }
    var accounts by remember { mutableStateOf<List<com.bookkeeping.app.data.entity.Account>>(emptyList()) }
    var ledgers by remember { mutableStateOf<List<com.bookkeeping.app.data.entity.Ledger>>(emptyList()) }
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
            DataBus.notifyDataChanged()
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
                        DataBus.notifyDataChanged()
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
                    Modifier.size(40.dp).background(MaterialTheme.colorScheme.surfaceVariant, androidx.compose.foundation.shape.CircleShape),
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
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontSize = 24.sp, fontWeight = FontWeight.Bold, color = amountColor
                    ),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal,
                        imeAction = androidx.compose.ui.text.input.ImeAction.Done
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
                androidx.compose.material3.OutlinedTextField(
                    value = merchant, onValueChange = { merchant = it },
                    label = { Text("商户/对方（可选）") }, singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                androidx.compose.material3.OutlinedTextField(
                    value = note, onValueChange = { note = it },
                    label = { Text("备注（可选）") }, singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp),
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

// ─── CSV 导出 + 分享 ──────────────────────────────────────────

private val csvTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
private val csvFileNameFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())

private suspend fun exportAndShareCsv(context: Context) {
    val db = AppDatabase.getInstance(context)
    val transactions = withContext(Dispatchers.IO) { db.transactionDao().getAll() }

    if (transactions.isEmpty()) {
        withContext(Dispatchers.Main) {
            android.widget.Toast.makeText(context, "还没有任何记录可以导出", android.widget.Toast.LENGTH_SHORT).show()
        }
        return
    }

    val csv = buildString {
        appendLine("id,type,amount,category,merchant,source,account,note,is_manual,confidence,occurred_at,raw_text")
        transactions.forEach { tx ->
            appendLine(listOf(
                tx.id,
                tx.type.name,
                String.format("%.2f", tx.amount),
                csvEscape(tx.category),
                csvEscape(tx.merchant),
                csvEscape(tx.source),
                csvEscape(tx.accountId ?: ""),
                csvEscape(tx.note ?: ""),
                if (tx.isManual) "1" else "0",
                tx.confidence.name,
                csvTimeFormat.format(java.util.Date(tx.occurredAt)),
                csvEscape(tx.rawText)
            ).joinToString(","))
        }
    }

    val fileName = "bookkeeping_${csvFileNameFormat.format(java.util.Date())}.csv"

    // 写入缓存目录（不走 MediaStore，避免权限问题）
    val cacheFile = java.io.File(context.cacheDir, fileName)
    cacheFile.writeText(csv, Charsets.UTF_8)

    // 用 FileProvider 获取 content:// URI
    val fileUri = androidx.core.content.FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        cacheFile
    )

    // 触发系统分享面板（微信/邮件/网盘/蓝牙都可以）
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/csv"
        putExtra(Intent.EXTRA_STREAM, fileUri)
        putExtra(Intent.EXTRA_SUBJECT, "记账记录 ${transactions.size} 条")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    withContext(Dispatchers.Main) {
        context.startActivity(Intent.createChooser(shareIntent, "分享 CSV").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        android.widget.Toast.makeText(
            context,
            "已生成 ${transactions.size} 条记录 → 请选择分享目标",
            android.widget.Toast.LENGTH_LONG
        ).show()
    }
}

private fun csvEscape(s: Any?): String {
    val str = s?.toString() ?: ""
    val escaped = str.replace("\"", "\"\"").replace("\n", " ").replace("\r", " ")
    return "\"$escaped\""
}

/** 解析单行 CSV（处理双引号包裹与 "" 转义） */
private fun parseCsvLine(line: String): List<String> {
    val fields = mutableListOf<String>()
    val sb = StringBuilder()
    var inQuote = false
    var i = 0
    while (i < line.length) {
        val c = line[i]
        when {
            inQuote && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
            c == '"' -> inQuote = !inQuote
            c == ',' && !inQuote -> { fields.add(sb.toString()); sb.clear() }
            else -> sb.append(c)
        }
        i++
    }
    fields.add(sb.toString())
    return fields
}

/**
 * 从 URI 读取 CSV 并导入交易记录。
 * 返回 (新增条数, 跳过重复条数)。按 id 去重：已存在的 id 直接跳过。
 */
private suspend fun importCsvFromUri(context: Context, uri: android.net.Uri): Pair<Int, Int> {
    val db = AppDatabase.getInstance(context)
    val dao = db.transactionDao()
    return withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(uri)?.use { input ->
            input.readBytes().toString(Charsets.UTF_8)
        } ?: return@withContext 0 to 0

        var imported = 0
        var skipped = 0

        text.lineSequence().forEachIndexed { index, rawLine ->
            val line = rawLine.trim().trimStart('\uFEFF')
            if (line.isEmpty()) return@forEachIndexed
            if (index == 0 && line.startsWith("id,")) return@forEachIndexed // 表头

            val f = parseCsvLine(line)
            if (f.size < 12) { skipped++; return@forEachIndexed }

            val amount = f[2].trim().toDoubleOrNull()
            if (amount == null) { skipped++; return@forEachIndexed }
            val type = try { Transaction.Type.valueOf(f[1].trim().uppercase()) } catch (_: Exception) { Transaction.Type.EXPENSE }
            val confidence = try { Transaction.Confidence.valueOf(f[9].trim().uppercase()) } catch (_: Exception) { Transaction.Confidence.HIGH }
            val occurredAt = try { csvTimeFormat.parse(f[10].trim())?.time ?: System.currentTimeMillis() } catch (_: Exception) { System.currentTimeMillis() }
            val id = f[0].trim().toLongOrNull() ?: 0L

            val tx = Transaction(
                id = id,
                amount = amount,
                type = type,
                category = f[3].trim(),
                merchant = f[4].trim(),
                source = f[5].trim(),
                accountId = f[6].trim().toLongOrNull(),
                note = f[7].trim(),
                rawText = f[11],
                isManual = f[8].trim() == "1",
                confirmed = true,
                confidence = confidence,
                occurredAt = occurredAt
            )
            // IGNORE 策略：id 已存在时返回 -1，自动跳过重复
            if (dao.insertIfNew(tx) == -1L) skipped++ else imported++
        }
        imported to skipped
    }
}

/** SQLite 文件头（用于校验备份文件合法性） */
private const val SQLITE_MAGIC = "SQLite format 3\u0000"

/**
 * 备份：checkpoint WAL 后把整个数据库文件复制到缓存目录，并弹出系统分享面板。
 */
private suspend fun backupDatabase(context: Context) {
    val cacheFile = withContext(Dispatchers.IO) {
        val db = AppDatabase.getInstance(context)
        // 先把 WAL 日志合并进主文件，确保导出的 .db 是完整数据
        db.openHelper.writableDatabase
            .query(androidx.sqlite.db.SimpleSQLiteQuery("PRAGMA wal_checkpoint(FULL)"))
            .use { it.moveToFirst() }

        val dbFile = context.getDatabasePath("bookkeeping.db")
        val fileName = "bookkeeping_backup_${csvFileNameFormat.format(java.util.Date())}.db"
        val f = java.io.File(context.cacheDir, fileName)
        dbFile.copyTo(f, overwrite = true)
        f
    }

    withContext(Dispatchers.Main) {
        val fileUri = androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", cacheFile
        )
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, fileUri)
            putExtra(Intent.EXTRA_SUBJECT, "记账助手数据库备份")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(shareIntent, "分享备份文件").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        android.widget.Toast.makeText(
            context, "备份已生成（${"%.1f".format(cacheFile.length() / 1024f)}KB）→ 请选择保存位置（微信/网盘/文件管理器）",
            android.widget.Toast.LENGTH_LONG
        ).show()
    }
}

/**
 * 恢复：校验备份文件 → 关闭 Room → 覆盖写回数据库文件 → 调用方重启进程。
 */
private suspend fun restoreDatabaseFromUri(context: Context, uri: android.net.Uri): Boolean {
    return withContext(Dispatchers.IO) {
        // 校验 SQLite 文件头
        val magic = ByteArray(16)
        val read = context.contentResolver.openInputStream(uri)?.use { it.read(magic) } ?: -1
        if (read < 16 || String(magic, Charsets.US_ASCII) != SQLITE_MAGIC) {
            withContext(Dispatchers.Main) {
                android.widget.Toast.makeText(context, "恢复失败：不是有效的记账助手备份文件", android.widget.Toast.LENGTH_LONG).show()
            }
            return@withContext false
        }

        AppDatabase.closeInstance()
        val dbFile = context.getDatabasePath("bookkeeping.db")
        dbFile.parentFile?.mkdirs()
        java.io.File(dbFile.path + "-wal").delete()
        java.io.File(dbFile.path + "-shm").delete()

        context.contentResolver.openInputStream(uri)?.use { input ->
            dbFile.outputStream().use { output -> input.copyTo(output) }
        } ?: return@withContext false
        true
    }
}

/** 重启 app 进程（恢复备份后调用，让所有页面重新加载新数据库） */
private fun restartApp(context: Context) {
    val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }
    intent?.let(context::startActivity)
    Runtime.getRuntime().exit(0)
}

// ─── 规则刷新（通知监听 Service 热更新规则） ────────────────────

private fun refreshServiceRules() {
    try {
        val context = BookkeepingApp.instance
        val intent = Intent(context, com.bookkeeping.app.service.NotificationCaptureService::class.java)
            .setAction("com.bookkeeping.app.REFRESH_RULES")
        context.startService(intent)
    } catch (_: Exception) { }
}

// ─── 规则编辑弹窗 ────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RuleEditDialog(
    initialRule: com.bookkeeping.app.data.entity.ParseRule?,
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isNew = initialRule == null

    var channelName by remember { mutableStateOf(initialRule?.channelName ?: "") }
    var channel by remember { mutableStateOf(initialRule?.channel ?: "") }
    var matchKeyword by remember { mutableStateOf(initialRule?.matchKeyword ?: "") }
    var patternAmount by remember { mutableStateOf(initialRule?.patternAmount ?: """(¥|人民币)\s*(\d+(?:\.\d{1,2})?)""") }
    var patternExpense by remember { mutableStateOf(initialRule?.patternExpense ?: "支出|消费|扣款|支付|汇出") }
    var patternIncome by remember { mutableStateOf(initialRule?.patternIncome ?: "收入|入账|转入|到账") }
    var patternTransfer by remember { mutableStateOf(initialRule?.patternTransfer ?: "转账|互联汇出|互联汇入") }
    var priority by remember { mutableStateOf(initialRule?.priority?.toString() ?: "50") }
    var isEnabled by remember { mutableStateOf(initialRule?.isEnabled ?: true) }
    var errorMsg by remember { mutableStateOf("") }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0x80000000).copy(alpha = 0.5f),
        onClick = onDismiss
    ) {
        Box(contentAlignment = Alignment.Center) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
                    .height(620.dp),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        if (isNew) "新建规则" else "编辑规则",
                        fontWeight = FontWeight.Bold, fontSize = 18.sp
                    )
                    Spacer(Modifier.height(12.dp))

                    val scrollState = rememberScrollState()
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(scrollState),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                                androidx.compose.material3.OutlinedTextField(
                                    value = channelName, onValueChange = { channelName = it },
                                    label = { Text("渠道名称（显示用）") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                                )
                                androidx.compose.material3.OutlinedTextField(
                                    value = channel, onValueChange = { channel = it },
                                    label = { Text("渠道标识（包名/代号）") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                                )
                                androidx.compose.material3.OutlinedTextField(
                                    value = matchKeyword, onValueChange = { matchKeyword = it },
                                    label = { Text("匹配关键词（包含即命中）") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                                )
                                androidx.compose.material3.OutlinedTextField(
                                    value = patternAmount, onValueChange = { patternAmount = it },
                                    label = { Text("金额正则") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                                )
                                androidx.compose.material3.OutlinedTextField(
                                    value = patternExpense, onValueChange = { patternExpense = it },
                                    label = { Text("支出关键词正则") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                                )
                                androidx.compose.material3.OutlinedTextField(
                                    value = patternIncome, onValueChange = { patternIncome = it },
                                    label = { Text("收入关键词正则") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                                )
                                androidx.compose.material3.OutlinedTextField(
                                    value = patternTransfer, onValueChange = { patternTransfer = it },
                                    label = { Text("转账关键词正则") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("优先级", fontSize = 14.sp, modifier = Modifier.weight(1f))
                                    androidx.compose.material3.OutlinedTextField(
                                        value = priority, onValueChange = { priority = it.filter { c -> c.isDigit() } },
                                        singleLine = true, modifier = Modifier.width(100.dp)
                                    )
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("启用", fontSize = 14.sp, modifier = Modifier.weight(1f))
                                    androidx.compose.material3.Switch(checked = isEnabled, onCheckedChange = { isEnabled = it })
                                }

                                if (errorMsg.isNotEmpty()) {
                                    Text(errorMsg, color = Color(0xFFE53935), fontSize = 12.sp)
                                }

                                Spacer(Modifier.height(4.dp))
                    }

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        if (!isNew) {
                            androidx.compose.material3.TextButton(onClick = {
                                scope.launch {
                                    AppDatabase.getInstance(context).parseRuleDao()
                                        .deleteById(initialRule!!.id)
                                    refreshServiceRules()
                                    onSaved()
                                }
                            }) { Text("删除", color = Color(0xFFE53935)) }
                        }
                        androidx.compose.material3.TextButton(onClick = onDismiss) { Text("取消") }
                        Spacer(Modifier.width(4.dp))
                        androidx.compose.material3.Button(onClick = {
                            if (channelName.isBlank() || channel.isBlank() || patternAmount.isBlank()) {
                                errorMsg = "名称/标识/金额正则不能为空"
                                return@Button
                            }
                            scope.launch {
                                val rule = com.bookkeeping.app.data.entity.ParseRule(
                                    id = initialRule?.id ?: 0,
                                    channel = channel,
                                    channelName = channelName,
                                    matchKeyword = matchKeyword,
                                    patternAmount = patternAmount,
                                    patternExpense = patternExpense,
                                    patternIncome = patternIncome,
                                    patternTransfer = patternTransfer,
                                    isEnabled = isEnabled,
                                    priority = priority.toIntOrNull() ?: 50
                                )
                                if (isNew) {
                                    AppDatabase.getInstance(context).parseRuleDao().insert(rule)
                                } else {
                                    AppDatabase.getInstance(context).parseRuleDao().update(rule)
                                }
                                refreshServiceRules()
                                onSaved()
                            }
                        }) { Text("保存") }
                    }
                }
            }
        }
    }
}
