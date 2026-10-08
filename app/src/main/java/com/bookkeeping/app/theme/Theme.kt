package com.bookkeeping.app.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80
)

private val LightColorScheme = lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40
)

/** 卡片透明度设置键（存 SharedPreferences "settings"），范围 0~1.0，默认 1.0（不透明） */
const val CARD_ALPHA_KEY = "card_alpha"
const val CARD_ALPHA_DEFAULT = 1.0f

/** 卡片背景自定义颜色键（存 SharedPreferences "settings"）。值为 ARGB Long；-1 表示跟随默认（surfaceContainerHighest） */
const val CARD_COLOR_KEY = "card_color"

/**
 * 卡片背景可选色（排除已用的黑白红绿蓝语义色）：
 * 紫 / 橙 / 青 / 青绿 / 米黄 / 淡蓝 / 薄荷。默认第一项是「默认」（跟随主题灰白）。
 * ⚠️ 宽度预算：设置页外观区可用宽度约 310dp（屏幕 374dp − LazyColumn 32dp − Card 内 32dp）。
 *    8 个色块 @30dp + 间距 7dp = 289dp，余量约 21dp。
 *    若再增加颜色，需同步缩小 BackgroundImage.kt 里色块的 size/spacedBy，否则会挤出屏幕。
 */
data class CardColorOption(val label: String, val color: androidx.compose.ui.graphics.Color?)

val CardColorOptions = listOf(
    CardColorOption("默认", null),
    CardColorOption("淡紫", androidx.compose.ui.graphics.Color(0xFFE8DEF8)),
    CardColorOption("暖橙", androidx.compose.ui.graphics.Color(0xFFFFE0B2)),
    CardColorOption("青", androidx.compose.ui.graphics.Color(0xFFB2EBF2)),
    CardColorOption("青绿", androidx.compose.ui.graphics.Color(0xFFB2DFDB)),
    CardColorOption("米黄", androidx.compose.ui.graphics.Color(0xFFFFF9C4)),
    CardColorOption("淡蓝", androidx.compose.ui.graphics.Color(0xFFBBDEFB)),
    CardColorOption("薄荷", androidx.compose.ui.graphics.Color(0xFFDCEDC8))
)

/**
 * 卡片透明度的可观察状态：设置页 Slider 改变时更新它，Theme 读取它做重组。
 * 初始值从 SharedPreferences 读，之后以内存态为准（写入时同步持久化）。
 */
object CardAlphaState {
    val state = androidx.compose.runtime.mutableStateOf(CARD_ALPHA_DEFAULT)
    var value: Float
        get() = state.value
        set(v) { state.value = v }
}

/**
 * 卡片背景自定义颜色（可观察，可空）。null = 跟随主题默认灰白（surfaceContainerHighest）。
 * 与透明度联动：Theme 里取它覆盖 surfaceContainerHighest，再乘 alpha。
 */
object CardColorState {
    val state = androidx.compose.runtime.mutableStateOf<androidx.compose.ui.graphics.Color?>(null)
    var value: androidx.compose.ui.graphics.Color?
        get() = state.value
        set(v) { state.value = v }
}

@Composable
fun BookkeepingTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val baseColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    // 卡片透明度：用户可在「设置 → 外观 → 卡片背景透明度」里调，值越大卡片越实。
    // ⚠️ material3 1.3.0 里 Card() 默认容器色是 FilledCardTokens.ContainerColor，
    // 对应色槽是 surfaceContainerHighest（已反编译 CardDefaults + FilledCardTokens 确认），
    // 不是 surfaceContainerLow！所以之前只调 Low/Container 完全没效果。
    // 必须把 surfaceContainerHighest 一起乘 alpha。surfaceContainerHigh 保持实心
    // （弹窗/菜单/对话框容器，保证可读性）。
    val cardAlpha = CardAlphaState.state.value
    // 卡片背景自定义色：非空时用它覆盖 surfaceContainerHighest（Card 默认容器色），
    // 再乘 cardAlpha 保持透明度联动；null 则用主题默认灰白。
    val customCardColor = CardColorState.state.value
    val cardContainer = (customCardColor ?: baseColorScheme.surfaceContainerHighest).copy(alpha = cardAlpha)
    val colorScheme = baseColorScheme.copy(
        surface = baseColorScheme.surface.copy(alpha = cardAlpha),
        surfaceVariant = baseColorScheme.surfaceVariant.copy(alpha = cardAlpha),
        surfaceContainerLowest = baseColorScheme.surfaceContainerLowest.copy(alpha = cardAlpha),
        surfaceContainerLow = baseColorScheme.surfaceContainerLow.copy(alpha = cardAlpha),
        surfaceContainer = baseColorScheme.surfaceContainer.copy(alpha = cardAlpha),
        surfaceContainerHighest = cardContainer
    )

    val view = LocalView.current
    if (!view.isInEditMode) {
        val window = (view.context as Activity).window
        SideEffect {
            // ⚠️ 状态栏/导航栏必须保持「全透明」，App 内容才会真正延伸到系统栏后面铺满全屏。
            // 之前这里写成 statusBarColor = primary（不透明品牌色），虽然 onCreate 里调了
            // enableEdgeToEdge()，但运行时又被这行覆盖回去，顶部永远留一条品牌色，铺不满。
            @Suppress("DEPRECATION")
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            @Suppress("DEPRECATION")
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            // Android 10+ 会在透明导航栏上再加一层对比色遮罩，必须关掉才能真正铺满
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
            }
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = !darkTheme
            insetsController.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
