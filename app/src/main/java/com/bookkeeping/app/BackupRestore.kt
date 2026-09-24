package com.bookkeeping.app

import android.content.Context
import android.content.Intent
import com.bookkeeping.app.data.AppDatabase
import com.bookkeeping.app.data.entity.Transaction
import com.bookkeeping.app.service.NotificationCaptureService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

// ─── CSV 导出 + 分享 ──────────────────────────────────────────

private val csvTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
private val csvFileNameFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())

internal suspend fun exportAndShareCsv(context: Context) {
    val db = AppDatabase.getInstance(context)
    val transactions = withContext(Dispatchers.IO) { db.transactionDao().getAll() }

    if (transactions.isEmpty()) {
        withContext(Dispatchers.Main) {
            android.widget.Toast.makeText(context, "还没有任何记录可以导出", android.widget.Toast.LENGTH_SHORT).show()
        }
        return
    }

    val csv = buildString {
        appendLine("id,type,amount,category,merchant,source,account,note,is_manual,confidence,occurred_at,raw_text")
        transactions.forEach { tx ->
            appendLine(listOf(
                tx.id,
                tx.type.name,
                String.format("%.2f", tx.amount),
                csvEscape(tx.category),
                csvEscape(tx.merchant),
                csvEscape(tx.source),
                csvEscape(tx.accountId ?: ""),
                csvEscape(tx.note ?: ""),
                if (tx.isManual) "1" else "0",
                tx.confidence.name,
                csvTimeFormat.format(Date(tx.occurredAt)),
                csvEscape(tx.rawText)
            ).joinToString(","))
        }
    }

    val fileName = "bookkeeping_${csvFileNameFormat.format(Date())}.csv"

    // 写入缓存目录（不走 MediaStore，避免权限问题）
    val cacheFile = java.io.File(context.cacheDir, fileName)
    cacheFile.writeText(csv, Charsets.UTF_8)

    // 用 FileProvider 获取 content:// URI
    val fileUri = androidx.core.content.FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        cacheFile
    )

    // 触发系统分享面板（微信/邮件/网盘/蓝牙都可以）
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/csv"
        putExtra(Intent.EXTRA_STREAM, fileUri)
        putExtra(Intent.EXTRA_SUBJECT, "记账记录 ${transactions.size} 条")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    withContext(Dispatchers.Main) {
        context.startActivity(Intent.createChooser(shareIntent, "分享 CSV").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        android.widget.Toast.makeText(
            context,
            "已生成 ${transactions.size} 条记录 → 请选择分享目标",
            android.widget.Toast.LENGTH_LONG
        ).show()
    }
}

private fun csvEscape(s: Any?): String {
    val str = s?.toString() ?: ""
    val escaped = str.replace("\"", "\"\"").replace("\n", " ").replace("\r", " ")
    return "\"$escaped\""
}

/** 解析单行 CSV（处理双引号包裹与 "" 转义） */
private fun parseCsvLine(line: String): List<String> {
    val fields = mutableListOf<String>()
    val sb = StringBuilder()
    var inQuote = false
    var i = 0
    while (i < line.length) {
        val c = line[i]
        when {
            inQuote && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
            c == '"' -> inQuote = !inQuote
            c == ',' && !inQuote -> { fields.add(sb.toString()); sb.clear() }
            else -> sb.append(c)
        }
        i++
    }
    fields.add(sb.toString())
    return fields
}

/**
 * 从 URI 读取 CSV 并导入交易记录。
 * 返回 (新增条数, 跳过重复条数)。按 id 去重：已存在的 id 直接跳过。
 */
internal suspend fun importCsvFromUri(context: Context, uri: android.net.Uri): Pair<Int, Int> {
    val db = AppDatabase.getInstance(context)
    val dao = db.transactionDao()
    return withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(uri)?.use { input ->
            input.readBytes().toString(Charsets.UTF_8)
        } ?: return@withContext 0 to 0

        var imported = 0
        var skipped = 0

        text.lineSequence().forEachIndexed { index, rawLine ->
            val line = rawLine.trim().trimStart('\uFEFF')
            if (line.isEmpty()) return@forEachIndexed
            if (index == 0 && line.startsWith("id,")) return@forEachIndexed // 表头

            val f = parseCsvLine(line)
            if (f.size < 12) { skipped++; return@forEachIndexed }

            val amount = f[2].trim().toDoubleOrNull()
            if (amount == null) { skipped++; return@forEachIndexed }
            val type = try { Transaction.Type.valueOf(f[1].trim().uppercase()) } catch (_: Exception) { Transaction.Type.EXPENSE }
            val confidence = try { Transaction.Confidence.valueOf(f[9].trim().uppercase()) } catch (_: Exception) { Transaction.Confidence.HIGH }
            val occurredAt = try { csvTimeFormat.parse(f[10].trim())?.time ?: System.currentTimeMillis() } catch (_: Exception) { System.currentTimeMillis() }
            val id = f[0].trim().toLongOrNull() ?: 0L

            val tx = Transaction(
                id = id,
                amount = amount,
                type = type,
                category = f[3].trim(),
                merchant = f[4].trim(),
                source = f[5].trim(),
                accountId = f[6].trim().toLongOrNull(),
                note = f[7].trim(),
                rawText = f[11],
                isManual = f[8].trim() == "1",
                confirmed = true,
                confidence = confidence,
                occurredAt = occurredAt
            )
            // IGNORE 策略：id 已存在时返回 -1，自动跳过重复
            if (dao.insertIfNew(tx) == -1L) skipped++ else imported++
        }
        imported to skipped
    }
}

/** SQLite 文件头（用于校验备份文件合法性） */
private const val SQLITE_MAGIC = "SQLite format 3\u0000"

/**
 * 备份：checkpoint WAL 后把数据库 + 凭证图片打包成 zip，并弹出系统分享面板。
 * zip 结构：bookkeeping.db + receipts/{txId}.jpg...
 */
internal suspend fun backupDatabase(context: Context) {
    val cacheFile = withContext(Dispatchers.IO) {
        val db = AppDatabase.getInstance(context)
        // 先把 WAL 日志合并进主文件，确保导出的 .db 是完整数据
        db.openHelper.writableDatabase
            .query(androidx.sqlite.db.SimpleSQLiteQuery("PRAGMA wal_checkpoint(FULL)"))
            .use { it.moveToFirst() }

        val dbFile = context.getDatabasePath("bookkeeping.db")
        val fileName = "bookkeeping_backup_${csvFileNameFormat.format(Date())}.zip"
        val f = java.io.File(context.cacheDir, fileName)
        ZipOutputStream(f.outputStream().buffered()).use { zip ->
            // 1. 数据库
            zip.putNextEntry(ZipEntry("bookkeeping.db"))
            dbFile.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
            // 2. 凭证图片（filesDir/receipts/{txId}.jpg）
            val receipts = java.io.File(context.filesDir, "receipts")
            receipts.listFiles()
                ?.filter { it.isFile && it.length() > 0 }
                ?.sortedBy { it.name }
                ?.forEach { img ->
                    zip.putNextEntry(ZipEntry("receipts/${img.name}"))
                    img.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
        }
        f
    }

    withContext(Dispatchers.Main) {
        val fileUri = androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", cacheFile
        )
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, fileUri)
            putExtra(Intent.EXTRA_SUBJECT, "记账助手备份")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(shareIntent, "分享备份文件").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        android.widget.Toast.makeText(
            context, "备份已生成（${"%.2f".format(cacheFile.length() / 1024f / 1024f)}MB，含数据库+凭证图片）→ 请选择保存位置（微信/网盘/文件管理器）",
            android.widget.Toast.LENGTH_LONG
        ).show()
    }
}

/**
 * 恢复：自动识别 zip 新格式（数据库+图片）或旧版 .db 格式。
 * 校验 → 关闭 Room → 覆盖写回数据库文件（+ 还原凭证图片）→ 调用方重启进程。
 */
internal suspend fun restoreDatabaseFromUri(context: Context, uri: android.net.Uri): Boolean {
    return withContext(Dispatchers.IO) {
        // 读文件头判断格式：PK = zip，"SQLite format 3" = 旧版裸 db
        val head = ByteArray(16)
        val read = context.contentResolver.openInputStream(uri)?.use { it.read(head) } ?: -1
        val isZip = read >= 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
        val isSqlite = read >= 16 && String(head, Charsets.US_ASCII) == SQLITE_MAGIC
        if (!isZip && !isSqlite) {
            withContext(Dispatchers.Main) {
                android.widget.Toast.makeText(context, "恢复失败：不是有效的记账助手备份文件", android.widget.Toast.LENGTH_LONG).show()
            }
            return@withContext false
        }

        AppDatabase.closeInstance()
        val dbFile = context.getDatabasePath("bookkeeping.db")
        dbFile.parentFile?.mkdirs()
        java.io.File(dbFile.path + "-wal").delete()
        java.io.File(dbFile.path + "-shm").delete()

        if (isZip) {
            // 解压到临时目录 → 校验包内数据库 → 落库 + 还原图片
            val tempDir = java.io.File(context.cacheDir, "restore_tmp")
            tempDir.deleteRecursively()
            tempDir.mkdirs()
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    ZipInputStream(input.buffered()).use { zis ->
                        var entry = zis.nextEntry
                        while (entry != null) {
                            // 路径穿越防护：条目名不允许包含 ".." 或以 "/" 开头
                            if (!entry.name.contains("..") && !entry.name.startsWith("/")) {
                                val out = java.io.File(tempDir, entry.name)
                                if (entry.isDirectory) {
                                    out.mkdirs()
                                } else {
                                    out.parentFile?.mkdirs()
                                    out.outputStream().use { zis.copyTo(it) }
                                }
                            }
                            zis.closeEntry()
                            entry = zis.nextEntry
                        }
                    }
                } ?: return@withContext false

                val newDb = java.io.File(tempDir, "bookkeeping.db")
                if (!newDb.exists() || newDb.length() < 16 ||
                    String(newDb.readBytes(), 0, 16, Charsets.US_ASCII) != SQLITE_MAGIC
                ) {
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(context, "恢复失败：备份包内无有效数据库", android.widget.Toast.LENGTH_LONG).show()
                    }
                    return@withContext false
                }
                newDb.copyTo(dbFile, overwrite = true)

                // 还原凭证图片（存在才拷贝，旧包无 receipts 目录也不报错）
                val receiptsDir = java.io.File(context.filesDir, "receipts").apply { mkdirs() }
                java.io.File(tempDir, "receipts").listFiles()
                    ?.filter { it.isFile }
                    ?.forEach { img -> img.copyTo(java.io.File(receiptsDir, img.name), overwrite = true) }
                true
            } finally {
                tempDir.deleteRecursively()
            }
        } else {
            // 旧版 .db：直接流覆盖（凭证图片不受影响）
            context.contentResolver.openInputStream(uri)?.use { input ->
                dbFile.outputStream().use { output -> input.copyTo(output) }
            } ?: return@withContext false
            true
        }
    }
}

/** 重启 app 进程（恢复备份后调用，让所有页面重新加载新数据库） */
internal fun restartApp(context: Context) {
    val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }
    intent?.let(context::startActivity)
    Runtime.getRuntime().exit(0)
}

/**
 * 导出全部交易为 CSV（UTF-8 BOM，Excel 直接打开不乱码）并弹出分享。
 */
internal suspend fun exportTransactionsCsv(context: Context) {
    val f = withContext(Dispatchers.IO) {
        val db = AppDatabase.getInstance(context)
        val txs = db.transactionDao().getAllIncludingUnconfirmed()
        val ledgerNames = db.ledgerDao().getAll().associate { it.id to "${it.icon}${it.name}" }
        val file = java.io.File(context.cacheDir, "bookkeeping_export_${csvFileNameFormat.format(Date())}.csv")
        java.io.PrintWriter(
            java.io.BufferedWriter(java.io.OutputStreamWriter(file.outputStream(), Charsets.UTF_8))
        ).use { w ->
            w.write("\uFEFF") // BOM：Excel 中文兼容
            w.println("日期时间,类型,分类,商户,金额,账本,备注,来源,状态")
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            txs.forEach { tx ->
                fun esc(s: String?) = "\"" + (s ?: "").replace("\"", "\"\"") + "\""
                val type = when (tx.type) {
                    Transaction.Type.EXPENSE -> "支出"
                    Transaction.Type.INCOME -> "收入"
                    else -> "转账"
                }
                val status = when {
                    tx.deletedAt > 0 -> "已删除"
                    tx.confirmed -> "已确认"
                    else -> "待确认"
                }
                w.println(
                    listOf(
                        fmt.format(Date(tx.occurredAt)), type, esc(tx.category), esc(tx.merchant),
                        String.format("%.2f", tx.amount), esc(ledgerNames[tx.ledgerId] ?: ""),
                        esc(tx.note), esc(tx.source), status
                    ).joinToString(",")
                )
            }
        }
        file
    }

    withContext(Dispatchers.Main) {
        val fileUri = androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", f
        )
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, fileUri)
            putExtra(Intent.EXTRA_SUBJECT, "记账助手交易明细导出")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(shareIntent, "分享 CSV 文件").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        android.widget.Toast.makeText(
            context, "已导出 ${f.name}（${f.length() / 1024}KB）→ 选择保存位置",
            android.widget.Toast.LENGTH_LONG
        ).show()
    }
}

// ─── 规则刷新（通知监听 Service 热更新规则） ────────────────────

internal fun refreshServiceRules() {
    try {
        val context = BookkeepingApp.instance
        val intent = Intent(context, NotificationCaptureService::class.java)
            .setAction("com.bookkeeping.app.REFRESH_RULES")
        context.startService(intent)
    } catch (_: Exception) { }
}
