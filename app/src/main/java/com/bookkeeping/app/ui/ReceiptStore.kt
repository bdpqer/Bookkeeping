package com.bookkeeping.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import java.io.File

/**
 * 交易凭证（小票/发票照片）存储。
 * 不动 DB schema：图片按约定文件名存私有目录 filesDir/receipts/{txId}.jpg。
 * 新增交易时先写 pending 临时文件，入库拿到 id 后 rename。
 */
object ReceiptStore {

    private const val PENDING_NAME = ".pending.jpg"

    fun dir(context: Context): File = File(context.filesDir, "receipts").apply { mkdirs() }

    fun pendingFile(context: Context): File = File(dir(context), PENDING_NAME)

    fun receiptFile(context: Context, txId: Long): File = File(dir(context), "$txId.jpg")

    fun hasReceipt(context: Context, txId: Long): Boolean =
        receiptFile(context, txId).exists() && receiptFile(context, txId).length() > 0

    /** 新增交易入库后调用：把 pending 重命名为 {id}.jpg */
    fun finalizePending(context: Context, txId: Long): Boolean {
        val pending = pendingFile(context)
        if (!pending.exists()) return false
        return try {
            pending.renameTo(receiptFile(context, txId))
        } catch (_: Exception) {
            false
        }
    }

    /** 从 content Uri 拷贝图片到目标文件 */
    fun copyFromUri(context: Context, uri: Uri, target: File): Boolean = try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        target.length() > 0
    } catch (_: Exception) {
        false
    }

    /** 删除交易时清理凭证 */
    fun deleteReceipt(context: Context, txId: Long) {
        receiptFile(context, txId).delete()
    }

    /** 采样解码，避免 OOM */
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

/** 本地文件缩略图 */
@Composable
fun ReceiptImage(file: File, modifier: Modifier = Modifier, big: Boolean = false) {
    val bitmap = remember(file.absolutePath, file.lastModified()) {
        ReceiptStore.decodeSampled(file, if (big) 1024 else 256)
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "交易凭证",
            modifier = modifier,
            contentScale = ContentScale.Crop
        )
    } else {
        Box(
            modifier = modifier.background(
                MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(8.dp)
            )
        )
    }
}

/**
 * 凭证区块（手动记账弹窗 / 编辑交易弹窗共用）。
 * editTxId = null → 新增模式（写 pending，入库后由调用方 finalize）
 * editTxId != null → 编辑模式（直接读写 {id}.jpg，即时生效）
 */
@Composable
fun ReceiptSection(editTxId: Long? = null) {
    val context = LocalContext.current
    var version by remember { mutableStateOf(0) }
    var showFull by remember { mutableStateOf(false) }

    val targetFile: File = if (editTxId != null) {
        ReceiptStore.receiptFile(context, editTxId)
    } else {
        ReceiptStore.pendingFile(context)
    }
    val hasPhoto = version >= 0 && targetFile.exists() && targetFile.length() > 0

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { ok -> if (ok) version++ }

    val pickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null && ReceiptStore.copyFromUri(context, uri, targetFile)) version++
    }

    val cameraUri = FileProvider.getUriForFile(
        context, "${context.packageName}.fileprovider", targetFile
    )

    Column {
        Text(
            "凭证（小票/发票，可选）",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (hasPhoto) {
                ReceiptImage(
                    file = targetFile,
                    modifier = Modifier
                        .size(64.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(8.dp)
                        )
                        .clickable { showFull = true }
                )
                Spacer(Modifier.width(8.dp))
            }
            TextButton(onClick = { cameraLauncher.launch(cameraUri) }) { Text("📷 拍照") }
            TextButton(onClick = {
                pickerLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            }) { Text("🖼 相册") }
            if (hasPhoto) {
                TextButton(onClick = { targetFile.delete(); version++ }) {
                    Text("删除", color = Color(0xFFE53935), fontSize = 12.sp)
                }
            }
        }
    }

    if (showFull && hasPhoto) {
        AlertDialog(
            onDismissRequest = { showFull = false },
            title = { Text("交易凭证") },
            text = {
                ReceiptImage(
                    file = targetFile,
                    big = true,
                    modifier = Modifier.fillMaxWidth().height(400.dp)
                )
            },
            confirmButton = {
                TextButton(onClick = { showFull = false }) { Text("关闭") }
            }
        )
    }
}
