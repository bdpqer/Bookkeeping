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
import androidx.compose.ui.graphics.toArgb
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

/** 卡片透明度设置键（存 SharedPreferences "settings"），范围 0.4~1.0，默认 1.0（不透明） */
const val CARD_ALPHA_KEY = "card_alpha"
const val CARD_ALPHA_DEFAULT = 1.0f

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
    val colorScheme = baseColorScheme.copy(
        surface = baseColorScheme.surface.copy(alpha = cardAlpha),
        surfaceVariant = baseColorScheme.surfaceVariant.copy(alpha = cardAlpha),
        surfaceContainerLowest = baseColorScheme.surfaceContainerLowest.copy(alpha = cardAlpha),
        surfaceContainerLow = baseColorScheme.surfaceContainerLow.copy(alpha = cardAlpha),
        surfaceContainer = baseColorScheme.surfaceContainer.copy(alpha = cardAlpha),
        surfaceContainerHighest = baseColorScheme.surfaceContainerHighest.copy(alpha = cardAlpha)
    )

    val view = LocalView.current
    if (!view.isInEditMode) {
        val window = (view.context as Activity).window
        SideEffect {
            @Suppress("DEPRECATION")
            window.statusBarColor = colorScheme.primary.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
