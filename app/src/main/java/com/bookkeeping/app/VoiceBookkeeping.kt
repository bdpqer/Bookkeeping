package com.bookkeeping.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.bookkeeping.app.data.entity.Transaction
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 语音记账：长按钱迹风格大按钮触发，sherpa-onnx 流式 Paraformer 离线识别。
 * 模型：assets/model/（FunASR speech_paraformer_asr_nat-zh-cn-16k-common-vocab8404-online，
 * encoder.int8.onnx + decoder.int8.onnx + tokens.txt，由 OnlineRecognizer 在设备端推理）。
 *
 * 流程：浮层授权麦克风 → 录音循环喂给 OnlineStream → 实时部分结果显示 →
 * 静音端点自动停或手动「完成」 → parseVoicePrefill 解析 → ManualAddDialog 预填确认落库。
 */

/** 识别文本解析出的记账预填数据 */
internal data class VoicePrefill(
    val amount: String,
    val type: Transaction.Type,
    val category: String?,
    val note: String,
)

/** sherpa-onnx 识别引擎单例：模型只加载一次（首次约数秒），进程内复用 */
internal object VoiceAsr {
    @Volatile
    private var recognizer: OnlineRecognizer? = null

    fun get(context: Context): OnlineRecognizer =
        recognizer ?: synchronized(this) {
            recognizer ?: OnlineRecognizer(
                assetManager = context.assets,
                config = OnlineRecognizerConfig(
                    modelConfig = OnlineModelConfig(
                        paraformer = OnlineParaformerModelConfig(
                            encoder = "model/encoder.int8.onnx",
                            decoder = "model/decoder.int8.onnx",
                        ),
                        tokens = "model/tokens.txt",
                        numThreads = 2,
                    ),
                    enableEndpoint = true, // rule2 默认 1.4s 尾静音触发端点
                ),
            ).also { recognizer = it }
        }
}

/** 一次语音识别会话：录音循环 + 流式解码，结果通过 phase/partial/level 暴露给 UI */
internal class VoiceSession(private val context: Context) {
    enum class Phase { LOADING, LISTENING, DONE, ERROR }

    val phase = MutableStateFlow(Phase.LOADING)
    val partial = MutableStateFlow("")
    val level = MutableStateFlow(0f)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    @Volatile
    private var stopped = false
    private var record: AudioRecord? = null

    fun start() {
        if (job != null) return
        job = scope.launchLoop()
    }

    private fun CoroutineScope.launchLoop() = launch { runLoop() }

    /** 手动结束录音（端点检测也会自动触发同样路径） */
    fun finish() {
        stopped = true
    }

    fun cancel() {
        stopped = true
        job?.cancel()
        scope.cancel()
        releaseRecord()
    }

    @SuppressLint("MissingPermission")
    private suspend fun runLoop() {
        try {
            phase.value = Phase.LOADING
            val rec = VoiceAsr.get(context)
            val stream = rec.createStream()

            val minBuf = AudioRecord.getMinBufferSize(
                16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val audio = AudioRecord(
                MediaRecorder.AudioSource.MIC, 16000,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                max(minBuf * 4, 8192)
            )
            if (audio.state != AudioRecord.STATE_INITIALIZED) {
                audio.release()
                Log.e("VoiceBookkeeping", "AudioRecord init failed")
                phase.value = Phase.ERROR
                return
            }
            record = audio
            audio.startRecording()

            val buf = ShortArray(1600) // 100ms @16k
            stopped = false
            phase.value = Phase.LISTENING

            while (!stopped) {
                val n = audio.read(buf, 0, buf.size)
                if (n <= 0) continue
                val samples = FloatArray(n) { buf[it] / 32768f }
                stream.acceptWaveform(samples, 16000)
                var sum = 0.0
                for (i in 0 until n) sum += buf[i].toDouble() * buf[i]
                level.value = (sqrt(sum / n) / 32768).toFloat().coerceIn(0f, 1f)
                while (rec.isReady(stream)) rec.decode(stream)
                partial.value = rec.getResult(stream).text
                if (rec.isEndpoint(stream)) stopped = true // 静音自动结束
            }

            stream.inputFinished()
            while (rec.isReady(stream)) rec.decode(stream)
            val text = rec.getResult(stream).text.trim()
            stream.release()
            partial.value = text
            phase.value = if (text.isEmpty()) Phase.ERROR else Phase.DONE
        } catch (e: Exception) {
            Log.e("VoiceBookkeeping", "ASR session failed", e)
            phase.value = Phase.ERROR
        } finally {
            releaseRecord()
        }
    }

    private fun releaseRecord() {
        try {
            record?.stop()
        } catch (_: Exception) {
        }
        try {
            record?.release()
        } catch (_: Exception) {
        }
        record = null
    }
}

// ── 文本解析：金额 / 类型 / 分类 ──────────────────────────────────────

private val CN_DIGIT = mapOf(
    '零' to 0L, '一' to 1L, '二' to 2L, '两' to 2L, '三' to 3L, '四' to 4L,
    '五' to 5L, '六' to 6L, '七' to 7L, '八' to 8L, '九' to 9L
)

/** 中文数字转数值：支持 十/百/千/万 + 「点」小数，如 三十五→35、两千零五点五→2005.5 */
internal fun parseChineseNumber(s: String): Double? {
    val parts = s.split("点", limit = 2)
    var total = 0L
    var section = 0L
    var current = 0L
    var sawAny = false
    for (c in parts[0]) {
        when {
            CN_DIGIT.containsKey(c) -> { current = CN_DIGIT[c]!!; sawAny = true }
            c == '十' -> { section += (if (current == 0L) 1L else current) * 10; current = 0; sawAny = true }
            c == '百' -> { section += (if (current == 0L) 1L else current) * 100; current = 0; sawAny = true }
            c == '千' -> { section += (if (current == 0L) 1L else current) * 1000; current = 0; sawAny = true }
            c == '万' -> { total = (total + section + current) * 10000; section = 0; current = 0; sawAny = true }
            else -> return null
        }
    }
    if (!sawAny) return null
    val intVal = total + section + current
    if (parts.size == 1) return intVal.toDouble()
    var dec = 0.0
    var scale = 0.1
    for (c in parts[1]) {
        val d = CN_DIGIT[c]?.toDouble() ?: if (c in '0'..'9') (c - '0').toDouble() else return null
        dec += d * scale
        scale /= 10
    }
    return intVal + dec
}

private const val CN_NUM_CLASS = "零一二两三四五六七八九十百千万点"

/** 从识别文本中提取金额：数字+块/元 → X块Y → 中文数字+块/元 → 最后裸数字 */
internal fun extractVoiceAmount(text: String): String? {
    // 1) 阿拉伯数字 + 块/元（含 X块Y毛 的简化形式 X块Y）
    Regex("(\\d+(?:\\.\\d+)?)\\s*[块钱]\\s*(\\d)?").find(text)?.let { m ->
        val x = m.groupValues[1].toDouble()
        val y = m.groupValues[2]
        return if (y.isNotEmpty()) (x + y.toDouble() / 10).round2().formatAmount() else x.round2().formatAmount()
    }
    // 2) 中文数字 + 块/元，支持 X块Y
    Regex("([$CN_NUM_CLASS]+)\\s*[块钱]\\s*([$CN_NUM_CLASS\\d])?").find(text)?.let { m ->
        val x = parseChineseNumber(m.groupValues[1]) ?: return@let
        val y = m.groupValues[2]
        val extra = when {
            y.isEmpty() -> 0.0
            y in "0".."9" -> y.toDouble() / 10
            CN_DIGIT.containsKey(y.single()) -> CN_DIGIT[y.single()]!! / 10.0
            else -> 0.0
        }
        return (x + extra).round2().formatAmount()
    }
    // 3) 兜底：最后一个裸中文数字串（如「晚饭花了三十」，无块/元后缀）
    Regex("[$CN_NUM_CLASS]+").findAll(text)
        .lastOrNull { it.value.any { c -> c != '点' } }
        ?.let { m -> parseChineseNumber(m.value)?.let { return it.round2().formatAmount() } }
    // 4) 兜底：最后一个裸阿拉伯数字
    Regex("(\\d+(?:\\.\\d+)?)").findAll(text).lastOrNull()?.let { m ->
        return m.groupValues[1].toDouble().round2().formatAmount()
    }
    return null
}

private val INCOME_KEYWORDS = listOf(
    "到账", "收入", "进账", "工资", "收到", "收款", "红包", "退款", "报销", "奖金", "利息", "转入", "赚"
)

private val CATEGORY_KEYWORDS: List<Pair<String, List<String>>> = listOf(
    "餐饮/外卖" to listOf("早饭", "午饭", "晚饭", "早餐", "午餐", "晚餐", "吃饭", "外卖", "餐厅", "咖啡", "奶茶", "零食", "水果", "面", "饭", "吃", "喝"),
    "交通出行" to listOf("打车", "滴滴", "出租", "地铁", "公交", "加油", "油费", "停车", "高铁", "火车", "机票", "车费", "车"),
    "购物" to listOf("超市", "淘宝", "京东", "拼多多", "购物", "衣服", "鞋", "日用品", "买"),
    "居住" to listOf("房租", "水电", "物业", "燃气", "宽带", "话费"),
    "医疗" to listOf("医院", "看病", "挂号", "药"),
    "娱乐" to listOf("电影", "游戏", "会员", "旅游", "KTV"),
)

/** 识别文本 → 记账预填（金额提取失败返回 null，由 UI 提示手动输入） */
internal fun parseVoicePrefill(text: String): VoicePrefill? {
    val amount = extractVoiceAmount(text) ?: return null
    val type = if (INCOME_KEYWORDS.any { it in text }) Transaction.Type.INCOME else Transaction.Type.EXPENSE
    val category = CATEGORY_KEYWORDS.firstOrNull { (_, kws) -> kws.any { it in text } }?.first
    return VoicePrefill(amount = amount, type = type, category = category, note = text)
}

// ── 语音浮层 UI ─────────────────────────────────────────────────────

@Composable
internal fun VoiceRecordOverlay(
    visible: Boolean,
    onPrefill: (VoicePrefill) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasPermission) launcher.launch(Manifest.permission.RECORD_AUDIO)
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f))
    ) {
        when {
            !hasPermission -> Column(
                Modifier.align(Alignment.Center).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("需要麦克风权限\n才能使用语音记账", color = Color.White, fontSize = 16.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { launcher.launch(Manifest.permission.RECORD_AUDIO) }) { Text("授权", color = Color.White) }
                    OutlinedButton(onClick = onDismiss) { Text("取消", color = Color.White) }
                }
            }

            else -> {
                val session = remember { VoiceSession(context) }
                val phase by session.phase.collectAsState()
                val partial by session.partial.collectAsState()
                val level by session.level.collectAsState()

                DisposableEffect(Unit) {
                    session.start()
                    onDispose { session.cancel() }
                }

                Column(
                    Modifier.align(Alignment.Center).padding(horizontal = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // 麦克风呼吸圆
                    val transition = rememberInfiniteTransition(label = "mic")
                    val breathe by transition.animateFloat(
                        initialValue = 0.96f, targetValue = 1.06f,
                        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
                        label = "breathe"
                    )
                    Box(
                        Modifier
                            .size(120.dp)
                            .graphicsLayer {
                                scaleX = if (phase == VoiceSession.Phase.LISTENING) breathe * (1f + level * 0.35f) else 1f
                                scaleY = if (phase == VoiceSession.Phase.LISTENING) breathe * (1f + level * 0.35f) else 1f
                            }
                            .background(Color(0xFF2E5AAC).copy(alpha = 0.9f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("🎤", fontSize = 52.sp)
                    }
                    Spacer(Modifier.height(24.dp))
                    val hint = when (phase) {
                        VoiceSession.Phase.LOADING -> "正在加载模型…"
                        VoiceSession.Phase.LISTENING -> "请说出这笔账，例如：\n午饭花了三十五块"
                        VoiceSession.Phase.DONE -> "识别结果"
                        VoiceSession.Phase.ERROR -> "没听清，再试一次"
                    }
                    Text(hint, color = Color(0xFFB9C6DC), fontSize = 14.sp, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        partial.ifEmpty { " " },
                        color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center, lineHeight = 32.sp
                    )
                    if (phase == VoiceSession.Phase.DONE) {
                        val p = parseVoicePrefill(partial)
                        Spacer(Modifier.height(8.dp))
                        if (p != null) {
                            Text(
                                "金额 ¥${p.amount} · ${if (p.type == Transaction.Type.INCOME) "收入" else "支出"}" +
                                    (p.category?.let { " · $it" } ?: ""),
                                color = Color(0xFF7EE38B), fontSize = 14.sp
                            )
                        } else {
                            Text(
                                "未识别到金额，可在弹窗中手动补填",
                                color = Color(0xFFFFC857), fontSize = 14.sp
                            )
                        }
                    }
                }

                Row(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 48.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = null, Modifier.size(16.dp))
                        Spacer(Modifier.size(4.dp))
                        Text("取消")
                    }
                    when (phase) {
                        VoiceSession.Phase.LISTENING -> Button(
                            onClick = { session.finish() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E5AAC))
                        ) { Text("完成", color = Color.White) }
                        VoiceSession.Phase.DONE -> {
                            val p = parseVoicePrefill(partial)
                            Button(
                                // 金额没识别出来也允许进入弹窗手动补填（备注带原始识别文本）
                                onClick = {
                                    onPrefill(p ?: VoicePrefill("", Transaction.Type.EXPENSE, null, partial))
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E5AAC))
                            ) { Text("记一笔", color = Color.White) }
                        }
                        else -> OutlinedButton(
                            onClick = { /* LOADING/ERROR 无操作 */ },
                            enabled = false
                        ) { Text("…") }
                    }
                }
            }
        }
    }
}
