package com.bookkeeping.app.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 「真磨砂」用的模糊背景图。
 *
 * 原理：App 背景是一张静态自定义背景图（BackgroundStore，Crop 铺满整个窗口），
 * 卡片要做出「身后画面被模糊」的 iOS 毛玻璃效果，不需要实时 RenderEffect——
 * 只要把这张背景图预先按窗口宽高比 Crop + 模糊 + 缩小成一张小图，
 * 卡片显示时按自己在窗口里的 bounds 线性映射，从模糊小图上裁出对应区域放大画在底层即可。
 * 背景不动 → 裁出来的永远是「卡片身后那块被模糊的画面」。
 *
 * 模糊算法：多级「缩小再放大」（双线性插值），效果近似高斯模糊，
 * 不依赖 RenderEffect（API 31+），minSdk 26 全版本可用。
 * 输出尺寸很小（宽 ~144px），内存与绘制开销可忽略。
 */
object BlurBgState {

    /** 模糊后的背景小图；null = 无背景图 / 尚未生成（GlassCard 会降级为仿磨砂） */
    private val _bitmap = mutableStateOf<ImageBitmap?>(null)
    val bitmap: State<ImageBitmap?> get() = _bitmap

    /** 生成时的窗口尺寸（px）。卡片映射坐标用；窗口尺寸变了需要重新生成 */
    var srcWindowW: Int = 0
        private set
    var srcWindowH: Int = 0
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 生成/刷新模糊背景图。耗时操作全部在 IO 线程，完成后切主线程更新状态。
     * 无背景图时清空状态（GlassCard 降级仿磨砂）。
     *
     * @param windowW/H 当前窗口像素尺寸（MainActivity 的 displayMetrics）
     */
    fun generateAsync(context: Context, windowW: Int, windowH: Int) {
        if (windowW <= 0 || windowH <= 0) return
        val f = BackgroundStoreFile(context)
        if (!f.exists() || f.length() == 0L) {
            _bitmap.value = null
            return
        }
        scope.launch {
            val result = blurFile(f, windowW, windowH)
            withContext(Dispatchers.Main) {
                srcWindowW = windowW
                srcWindowH = windowH
                _bitmap.value = result
            }
        }
    }

    /** 卡片裁剪映射用：模糊图与窗口的尺寸比是否有效 */
    fun isReady(): Boolean = _bitmap.value != null && srcWindowW > 0 && srcWindowH > 0

    // ---------- 内部实现 ----------

    private fun BackgroundStoreFile(context: Context): File = File(context.filesDir, "bg.jpg")

    /**
     * 读背景文件 → 按窗口宽高比中心 Crop → 缩小到目标宽 → 多级缩小放大模糊。
     */
    private fun blurFile(f: File, windowW: Int, windowH: Int): ImageBitmap? = try {
        // 1. 采样解码（省内存，最终只输出 144px 宽，1024 足够）
        val src = decodeSampled(f, 1024) ?: return null

        // 2. 按窗口宽高比在源图中央 Crop（与 AppBackground 的 ContentScale.Crop 一致）
        val winAspect = windowW.toFloat() / windowH
        val srcAspect = src.width.toFloat() / src.height
        val cropW: Int
        val cropH: Int
        if (srcAspect > winAspect) {
            // 源图更宽 → 以高度为准，左右裁
            cropH = src.height
            cropW = (src.height * winAspect).toInt().coerceAtMost(src.width)
        } else {
            cropW = src.width
            cropH = (src.width / winAspect).toInt().coerceAtMost(src.height)
        }
        val cx = (src.width - cropW) / 2
        val cy = (src.height - cropH) / 2

        // 3. Crop + 缩小到目标尺寸（一次完成，双线性滤波）
        val outW = 144
        val outH = (outW * windowH.toFloat() / windowW).toInt().coerceAtLeast(1)
        var small = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(small)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        canvas.drawBitmap(
            src,
            android.graphics.Rect(cx, cy, cx + cropW, cy + cropH),
            android.graphics.Rect(0, 0, outW, outH),
            paint
        )
        src.recycle()

        // 4. 多级「缩小再放大」模糊：每轮尺寸减半再放大回来，两轮后已非常模糊
        repeat(2) {
            val w2 = (small.width / 2).coerceAtLeast(1)
            val h2 = (small.height / 2).coerceAtLeast(1)
            val down = Bitmap.createScaledBitmap(small, w2, h2, true)
            val up = Bitmap.createScaledBitmap(down, small.width, small.height, true)
            down.recycle()
            if (up != small) {
                small.recycle()
                small = up
            }
        }

        small.asImageBitmap()
    } catch (_: Exception) {
        null
    }

    private fun decodeSampled(f: File, reqSize: Int): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= reqSize || bounds.outHeight / (sample * 2) >= reqSize) {
            sample *= 2
        }
        BitmapFactory.decodeFile(
            f.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample }
        )
    } catch (_: Exception) {
        null
    }
}
