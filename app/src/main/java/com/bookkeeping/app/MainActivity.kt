package com.bookkeeping.app

import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import com.bookkeeping.app.data.AppDatabase
import com.bookkeeping.app.service.CaptureLogBus
import com.bookkeeping.app.service.NotificationCaptureService
import com.bookkeeping.app.theme.BookkeepingTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

internal enum class Tab(val label: String) { HOME("首页"), CALENDAR("日历"), PENDING("待确认"), LIST("明细"), SETTINGS("设置") }

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
            // 首页有自定义顶栏（☰/账本/搜索/云），不再显示通用顶栏
            if (currentTab != Tab.HOME) {
                // 自定义标题栏：TopAppBar 高度固定 64dp 压不矮，用 Box 自控高度
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .statusBarsPadding()
                        .height(48.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text(
                        when (currentTab) {
                            Tab.HOME -> "记账助手"
                            Tab.CALENDAR -> "记账日历"
                            Tab.PENDING -> "待确认 (${pendingCount})"
                            Tab.LIST -> "交易明细"
                            Tab.SETTINGS -> "设置"
                        },
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
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
