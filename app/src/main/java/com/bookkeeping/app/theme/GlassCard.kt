package com.bookkeeping.app.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.Card
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CardElevation
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * 全局磨砂玻璃开关，供 GlassCard 读取。默认关闭，跟随 CardFrostState 联动。
 */
val LocalCardFrost = staticCompositionLocalOf { false }

/**
 * 磨砂玻璃描边：细白描边，模拟玻璃边缘反光。
 * 深色模式下白描边更淡，避免过曝。
 */
@Composable
private fun frostBorder(): BorderStroke {
    val isLight = MaterialTheme.colorScheme.surface.luminance() > 0.5f
    val c = if (isLight) Color.White.copy(alpha = 0.9f) else Color.White.copy(alpha = 0.16f)
    return BorderStroke(1.dp, c)
}

/** 顶部高光渐变：极淡白从上到下淡出，模拟玻璃反光 */
private val glassHighlight = Brush.verticalGradient(
    colors = listOf(Color.White.copy(alpha = 0.18f), Color.Transparent),
    startY = 0f,
    endY = 260f
)

/**
 * 与 material3 Card 兼容的「磨砂玻璃卡片」。
 *
 * [LocalCardFrost] 开启时分两档：
 *  1. **真磨砂**：BlurBgState 里有模糊背景图时，卡片按自己在窗口中的 bounds
 *     从模糊图上裁出「身后那块画面」画在最底层，其上叠用户色卡×透明度的色罩与顶部高光，
 *     Card 容器色置 Transparent —— 效果即 iOS 通知那种「身后画面被模糊」的毛玻璃。
 *     用户色卡与透明度滑块继续生效（色罩层）。
 *  2. **仿磨砂降级**：无背景图（如渐变二级页）时，退回半透明底 + 描边 + 高光。
 *
 * 关闭时行为与普通 Card 完全一致。
 *
 * ⚠️ 历史坑：不能用「外层 Box + Card(matchParentSize)」叠高光 —— Box 依赖 Card 测量、
 * Card 又 matchParentSize 依赖 Box，高度由内容撑开时互相等待 → 高度塌陷为 0，卡片整体消失。
 * 所有叠加层都走 drawBehind/drawWithContent，尺寸语义与普通 Card 一致。
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = CardDefaults.shape,
    colors: CardColors = CardDefaults.cardColors(),
    elevation: CardElevation = CardDefaults.cardElevation(),
    border: BorderStroke? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val frost = LocalCardFrost.current
    if (!frost) {
        Card(modifier, shape, colors, elevation, border, content)
        return
    }

    val finalBorder = border ?: frostBorder()
    val blurBmp = BlurBgState.bitmap.value
    val realGlass = blurBmp != null && BlurBgState.isReady()

    // 卡片在窗口中的位置（真磨砂裁剪映射用）。滚动/布局变化时持续更新。
    var bounds by remember { mutableStateOf<Rect?>(null) }
    val posModifier = if (realGlass) {
        Modifier.onGloballyPositioned { bounds = it.boundsInWindow() }
    } else Modifier

    if (realGlass && blurBmp != null) {
        // ── 真磨砂 ──
        // 色罩 = 用户色卡色 × 透明度滑块（Theme 已算进 surfaceContainerHighest），
        // 但上限压到 0.85：即便用户滑块 100%，也保留 15% 模糊画面透出，玻璃感不丢失。
        val tint = colors.containerColor.let { c ->
            if (c.alpha > 0.85f) c.copy(alpha = 0.85f) else c
        }
        val bmpW = blurBmp.width
        val bmpH = blurBmp.height
        Card(
            modifier = posModifier
                .clip(shape)
                .drawBehind {
                    val b = bounds ?: return@drawBehind
                    // 卡片窗口坐标 → 模糊小图坐标（线性映射）
                    val sx = (b.left * bmpW / BlurBgState.srcWindowW).roundToInt()
                    val sy = (b.top * bmpH / BlurBgState.srcWindowH).roundToInt()
                    val sw = (b.width * bmpW / BlurBgState.srcWindowW).roundToInt()
                        .coerceAtLeast(1)
                    val sh = (b.height * bmpH / BlurBgState.srcWindowH).roundToInt()
                        .coerceAtLeast(1)
                    // 越界钳制（边界浮点误差）
                    val clampedSx = sx.coerceIn(0, (bmpW - 1))
                    val clampedSy = sy.coerceIn(0, (bmpH - 1))
                    val srcW2 = sw.coerceIn(1, bmpW - clampedSx)
                    val srcH2 = sh.coerceIn(1, bmpH - clampedSy)
                    drawImage(
                        image = blurBmp,
                        srcOffset = IntOffset(clampedSx, clampedSy),
                        srcSize = IntSize(srcW2, srcH2),
                        dstSize = IntSize(size.width.roundToInt().coerceAtLeast(1), size.height.roundToInt().coerceAtLeast(1))
                    )
                    // 用户色罩（色卡×透明度）
                    drawRect(tint)
                    // 顶部高光
                    drawRect(glassHighlight)
                },
            shape = shape,
            colors = colors.copy(containerColor = Color.Transparent),
            elevation = elevation,
            border = finalBorder,
            content = content
        )
    } else {
        // ── 仿磨砂降级（无背景图：渐变页/默认底色）──
        Card(
            modifier = posModifier.drawWithContent {
                drawContent()
                drawRect(glassHighlight)
            },
            shape = shape,
            colors = colors,
            elevation = elevation,
            border = finalBorder,
            content = content
        )
    }
}
