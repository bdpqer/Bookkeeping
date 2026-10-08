package com.bookkeeping.app.theme

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

/**
 * 下拉菜单（DropdownMenu）的容器色：**跟随卡片自定义色，但不跟随透明度滑块**。
 *
 * ⚠️ 为什么要单独定义：M3 的 `DropdownMenu` 默认容器色是 `MenuDefaults.containerColor`，
 * 反编译确认它取的是 `ColorSchemeKeyTokens.SurfaceContainer`，而 Theme 里为了让卡片能透出
 * 背景图，把 `surfaceContainer` 乘了 CardAlphaState（透明度滑块）。结果就是滑块拉到 0 时
 * 菜单也跟着全透明，选项浮在背景图上根本看不清。
 *
 * 菜单是临时浮层、承载文字选项，必须保证可读 → 底色固定不透明（alpha = 1f）。
 * 但仍跟随「卡片背景颜色」色卡，视觉上与卡片保持同一色系。
 */
@Composable
fun menuContainerColor(): Color {
    val custom = CardColorState.state.value
    // colorScheme.surfaceContainer 已被 Theme 乘过 alpha，这里 copy(alpha = 1f) 还原为不透明原色
    return custom ?: MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 1f)
}

/**
 * 全项目统一的下拉菜单入口：唯一区别是容器色强制不透明（见 [menuContainerColor]）。
 * 新增下拉一律用它，不要再直接调 `DropdownMenu`，否则又会跟着透明度滑块变透明。
 */
@Composable
fun AppDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        offset = offset,
        containerColor = menuContainerColor(),
        content = content
    )
}
