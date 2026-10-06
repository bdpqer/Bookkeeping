package com.bookkeeping.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 全局自定义背景图。
 * 不动 DB schema：图片按固定文件名存私有目录 filesDir/bg.jpg（与交易凭证 ReceiptStore 同套路）。
 * 设置页选图后 copy 进来，全局所有页面共用同一张；删除文件即恢复默认纯色背景。
 */
object BackgroundStore {

    private const val FILE_NAME = "bg.jpg"

    /**
     * 背景版本号：选图/恢复默认后自增。
     * AppBackground 以此为 key 重新解码——否则选完图后，只在下一次 Activity 重组时才会刷新，
     * 表现为"选了图没反应/无法应用"。
     */
    val version = mutableIntStateOf(0)

    fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    fun hasBackground(context: Context): Boolean =
        file(context).exists() && file(context).length() > 0

    /** 从 content Uri 拷贝图片到目标文件（覆盖式） */
    fun copyFromUri(context: Context, uri: Uri): Boolean = try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            file(context).outputStream().use { output -> input.copyTo(output) }
        }
        val ok = file(context).length() > 0
        if (ok) version.intValue++
        ok
    } catch (_: Exception) {
        false
    }

    /** 删除背景图，恢复默认 */
    fun clear(context: Context) {
        file(context).delete()
        version.intValue++
    }

    /** 采样解码，避免大图 OOM（复用 ReceiptStore 的思路） */
    fun decodeSampled(file: File, reqSize: Int): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= reqSize || bounds.outHeight / (sample * 2) >= reqSize) {
            sample *= 2
        }
        BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample }
        )
    } catch (_: Exception) {
        null
    }
}

/**
 * 全 App 背景层：有自定义背景图就铺满并叠一层半透明遮罩让前景清晰；
 * 无则透明（透出 MaterialTheme 的默认 surface）。
 * 图片解码放 IO 线程，避免大图阻塞主线程。
 */
@Composable
fun AppBackground(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val context = LocalContext.current

    // version 变化（选图/恢复默认）→ 立即重新解码，全局背景即时生效
    val bgVersion = BackgroundStore.version.intValue
    val hasBg = BackgroundStore.hasBackground(context)
    val bitmap by produceState<Bitmap?>(
        initialValue = null,
        key1 = bgVersion,
        key2 = hasBg
    ) {
        value = if (hasBg) {
            val f = BackgroundStore.file(context)
            withContext(Dispatchers.IO) { BackgroundStore.decodeSampled(f, 2048) }
        } else null
    }

    // 遮罩随主题：亮色叠白、深色叠黑；alpha 0.60→0.42，图片清晰可见且前景文字可读
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val scrimTop = if (darkTheme) Color.Black.copy(alpha = 0.58f) else Color.White.copy(alpha = 0.60f)
    val scrimBottom = if (darkTheme) Color.Black.copy(alpha = 0.42f) else Color.White.copy(alpha = 0.42f)

    Box(modifier = modifier.fillMaxSize()) {
        val b = bitmap
        if (b != null) {
            Image(
                bitmap = b.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            // 半透明遮罩：让背景变淡，前景文字/卡片更清晰
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(listOf(scrimTop, scrimBottom))
                    )
            )
        }
        content()
    }
}

/**
 * 设置页用的「卡片背景透明度」区块：Slider 拖动调节卡片底色透明度（0%~100%）。
 * 调的就是权限状态/自动记账/外观这类灰白色 Card 的底色（surfaceContainer* 色槽）。
 * 值实时写入 CardAlphaState（驱动 Theme 重组）+ 持久化到 SharedPreferences。
 * 透明度越低，背景图透过卡片越多；0% 时卡片底完全透明，只剩文字浮在背景上。
 */
@Composable
fun CardAlphaSection() {
    val context = LocalContext.current
    var alpha by remember {
        mutableStateOf(com.bookkeeping.app.theme.CardAlphaState.value)
    }
    val prefs = remember { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }

    Column {
        Text("卡片背景透明度", fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Slider(
                value = alpha,
                onValueChange = { v ->
                    alpha = v
                    com.bookkeeping.app.theme.CardAlphaState.value = v
                    prefs.edit().putFloat(com.bookkeeping.app.theme.CARD_ALPHA_KEY, v).apply()
                },
                valueRange = 0f..1f,
                modifier = Modifier.weight(1f)
            )
            Text(
                "${(alpha * 100).toInt()}%",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(40.dp)
            )
        }
        Text(
            "调节权限状态/自动记账/外观这类灰白色卡片的底色透明度：100% 不透明，0% 完全透出背景图",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun BackgroundPickerSection() {
    val context = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    val hasBg = remember(version) { BackgroundStore.hasBackground(context) }

    val pickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null && BackgroundStore.copyFromUri(context, uri)) version++
    }

    Column {
        Text(
            "自定义背景图",
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = {
                pickerLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            }) {
                Text(if (hasBg) "🖼 更换背景" else "🖼 选择背景图")
            }
            if (hasBg) {
                TextButton(onClick = {
                    BackgroundStore.clear(context)
                    version++
                }) {
                    Text(
                        "恢复默认",
                        color = com.bookkeeping.app.theme.DangerRed,
                        fontSize = 12.sp
                    )
                }
            }
        }
        Text(
            "从相册选择一张图片作为所有页面的背景，会叠加半透明遮罩保证文字清晰",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
