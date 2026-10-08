package com.bookkeeping.app

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.lifecycle.lifecycleScope
import com.bookkeeping.app.theme.FabBg
import com.bookkeeping.app.data.entity.Transaction
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
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import kotlinx.coroutines.launch
import com.bookkeeping.app.data.AppDatabase
import com.bookkeeping.app.service.NotificationCaptureService
import com.bookkeeping.app.theme.BookkeepingTheme
import com.bookkeeping.app.theme.BrandBlue
import com.bookkeeping.app.theme.DangerRed

class MainActivity : androidx.fragment.app.FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        tryBindListener()
        // 打开 App 顺手刷新桌面 Widget：走 await + 真实 id 的可靠刷新，避开 Glance updateAll 异步节流
        lifecycleScope.launch {
            com.bookkeeping.app.BookkeepingApp.instance.requestWidgetRefresh()
        }
        setContent {
            val prefs = remember { getSharedPreferences("settings", MODE_PRIVATE) }
            var themeMode by remember { mutableStateOf(prefs.getString("theme_mode", "system") ?: "system") }

            // 初始化卡片透明度（一次性，从持久化值读入全局可观察状态；范围 0~100%）
            androidx.compose.runtime.LaunchedEffect(Unit) {
                com.bookkeeping.app.theme.CardAlphaState.value =
                    prefs.getFloat(com.bookkeeping.app.theme.CARD_ALPHA_KEY, com.bookkeeping.app.theme.CARD_ALPHA_DEFAULT)
                        .coerceIn(0f, 1f)
                // 初始化卡片背景自定义色（-1L = 默认跟随主题灰白；否则为 ARGB int）
                val stored = prefs.getLong(com.bookkeeping.app.theme.CARD_COLOR_KEY, -1L)
                com.bookkeeping.app.theme.CardColorState.value =
                    if (stored == -1L) null else androidx.compose.ui.graphics.Color(stored.toInt())
            }

            val isDark = when (themeMode) {
                "dark" -> true
                "light" -> false
                else -> androidx.compose.foundation.isSystemInDarkTheme()
            }

            // ── 锁定门控：离开 App 自动上锁，回来需验证 ──
            val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
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
                // 全局背景层：有自定义背景图就满铺 + 半透明遮罩；无则透出默认 surface
                AppBackground {
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
    }

    private fun tryBindListener() {
        val component = ComponentName(this, NotificationCaptureService::class.java)
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: ""
        if (!flat.contains(component.flattenToString())) return

        try {
            // minSdk=26（Android 8.0）起 startForegroundService 为唯一入口，无旧分支
            startForegroundService(Intent(this, NotificationCaptureService::class.java))
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
    var voiceVisible by remember { mutableStateOf(false) }
    var voicePrefill by remember { mutableStateOf<VoicePrefill?>(null) }
    var secondaryOpen by remember { mutableStateOf(false) }
    // Tab 内的全屏叠层页（如明细/待确认里点开交易编辑）：打开时主框架的标题栏、
    // 底部导航栏、悬浮按钮都要让位，否则会与叠层页自己的顶栏/底部按钮重叠
    var overlayOpen by remember { mutableStateOf(false) }
    var editingRule by remember { mutableStateOf<com.bookkeeping.app.data.entity.ParseRule?>(null) }
    var showRuleEditor by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val db = remember { AppDatabase.getInstance(context) }
    // 待确认角标：Flow 订阅实时更新（入库/确认/删除由 Room 自动推送）
    val pendingCount by remember(db) { db.transactionDao().observePendingCount() }
        .collectAsStateWithLifecycle(initialValue = 0)

    Scaffold(
        // 透明容器：让外层 AppBackground 的自定义背景图透出来
        // （Scaffold 默认 containerColor 是不透明 background 色，会把背景图整个盖住）
        containerColor = Color.Transparent,
        // ⚠️ 关键：containerColor=Transparent 时，contentColorFor(Transparent) 会返回
        // Color.Unspecified，导致 LocalContentColor 回退成黑色 —— 深色模式下所有未显式
        // 指定颜色的标题文字/图标（汉堡、账本名、搜索、各页标题、分组标题）全变黑。
        // 这里显式指定 contentColor=onBackground，深色模式自动变白、浅色模式变深色。
        contentColor = MaterialTheme.colorScheme.onBackground,
        topBar = {
            // 首页有自定义顶栏（☰/账本/搜索/云），不再显示通用顶栏
            if (currentTab != Tab.HOME && !overlayOpen && !secondaryOpen) {
                // 自定义标题栏：TopAppBar 高度固定 64dp 压不矮，用 Box 自控高度
                Box(
                    Modifier
                        .fillMaxWidth()
                        // 全透明顶栏：直接透出全局背景图（半透明白纱会在图上叠出一层灰，
                        // 用户已反馈过；文字可读性由背景图自身的遮罩保证）
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
            // 透明底部导航：让自定义背景图贯通整个页面（0 阶调避免 surface 染色）
            // 二级页面 / Tab 内叠层页打开时整条让位，由叠层页自己铺满（含系统栏）
            if (!overlayOpen && !secondaryOpen) NavigationBar(
                containerColor = Color.Transparent,
                tonalElevation = 0.dp
            ) {
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
                                    containerColor = DangerRed,
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
                    icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
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
        // ⚠️ 外层 Box 不能加 padding：抽屉里的二级页面（借贷/周期/报销/应收/回收站）
        // 是以全屏叠层方式渲染的，父容器一旦带上 Scaffold 的 padding（含状态栏 +
        // 底部导航栏 insets），它们就被锁在这个区域内，系统栏永远铺不满。
        // 这里改由每个 Tab 各自加 padding，二级页面保持全屏。
        Box(Modifier.fillMaxSize()) {
            when (currentTab) {
                Tab.HOME -> HomeScreen(
                    modifier = Modifier.padding(padding),
                    onNavigate = { currentTab = it },
                    onSecondaryScreenChanged = { secondaryOpen = it }
                )
                Tab.CALENDAR -> Box(Modifier.padding(padding)) { CalendarScreen() }
                // 这两个 Tab 自己接收 padding：内部的编辑页要以全屏叠层渲染，
                // 不能跟页面内容一起被 padding 限制在内容区里
                Tab.PENDING -> PendingScreen(
                    modifier = Modifier.padding(padding),
                    onOverlayChanged = { overlayOpen = it }
                )
                Tab.LIST -> TransactionListScreen(
                    modifier = Modifier.padding(padding),
                    onOverlayChanged = { overlayOpen = it }
                )
                Tab.SETTINGS -> Box(Modifier.padding(padding)) {
                    SettingsScreen(
                        themeMode = themeMode,
                        onThemeChanged = onThemeChanged,
                        onNewRule = { editingRule = null; showRuleEditor = true },
                        onEditRule = { editingRule = it; showRuleEditor = true }
                    )
                }
            }

            // 钱迹风格大按钮：可在屏幕上任意拖动；点按记一笔，长按语音记账
            if (!secondaryOpen && !overlayOpen && currentTab != Tab.SETTINGS && currentTab != Tab.PENDING && currentTab != Tab.CALENDAR) {
                val config = androidx.compose.ui.platform.LocalConfiguration.current
                val density = androidx.compose.ui.platform.LocalDensity.current
                val maxDragX = with(density) { (config.screenWidthDp.dp - 74.dp).toPx() }
                val maxDragY = with(density) { (config.screenHeightDp.dp - 170.dp).toPx() }
                var dragX by remember { mutableStateOf(0f) }
                var dragY by remember { mutableStateOf(0f) }

                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        // 外层 Box 不再统一加 padding（为了让二级页面全屏铺满），这里单独
                        // 把底部导航栏的高度让出来，免得悬浮按钮压在 NavigationBar 上
                        .padding(bottom = padding.calculateBottomPadding())
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
                        .background(FabBg, RoundedCornerShape(20.dp))
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = { showAddDialog = true },
                                onLongPress = { voiceVisible = true }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text("+", fontSize = 30.sp, color = BrandBlue, fontWeight = FontWeight.Medium)
                }
            }
        }
    }

    if (showAddDialog) {
        ManualAddDialog(
            onDismiss = { showAddDialog = false; voicePrefill = null },
            initialType = voicePrefill?.type ?: Transaction.Type.EXPENSE,
            initialCategory = voicePrefill?.category,
            initialAmount = voicePrefill?.amount ?: "",
            initialNote = voicePrefill?.note ?: ""
        )
    }

    VoiceRecordOverlay(
        visible = voiceVisible,
        onPrefill = { p ->
            voiceVisible = false
            voicePrefill = p
            showAddDialog = true
        },
        onDismiss = { voiceVisible = false }
    )

    if (showRuleEditor) {
        RuleEditDialog(
            initialRule = editingRule,
            onDismiss = { showRuleEditor = false },
            onSaved = { showRuleEditor = false; refreshServiceRules() }
        )
    }
}
