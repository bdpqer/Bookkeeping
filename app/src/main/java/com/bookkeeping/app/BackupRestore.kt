package com.bookkeeping.app

import android.content.Context
import android.content.Intent
import androidx.room.withTransaction
import android.util.Log
import com.bookkeeping.app.data.AppDatabase
import com.bookkeeping.app.data.entity.Transaction
import com.bookkeeping.app.service.NotificationCaptureService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

// ─── CSV 导出 + 分享 ──────────────────────────────────────────

/**
 * CSV/文件名的时间格式化。
 *
 * 原先是两个全局 `SimpleDateFormat`——非线程安全，导出与导入并发时会静默产出错乱日期。
 * 这里改用 java.time（minSdk 26+），DateTimeFormatter 不可变且线程安全。
 */
private val CSV_TIME_FMT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
private val CSV_TIME_FMTS = listOf(CSV_TIME_FMT, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
private val FILE_NAME_FMT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")

private fun formatCsvTime(millis: Long): String =
    CSV_TIME_FMT.format(LocalDateTime.ofInstant(
        java.time.Instant.ofEpochMilli(millis), ZoneId.systemDefault()
    ))

private fun currentFileStamp(): String = FILE_NAME_FMT.format(LocalDateTime.now())

/**
 * 解析 CSV 里的时间；失败返回 null（由调用方决定跳过还是回退）。
 * 兼容两种写法：本类导出的 `yyyy-MM-dd HH:mm:ss`，以及中文对账 CSV 的 `yyyy-MM-dd HH:mm`
 * （Excel 里手工编辑过的表多半没有秒，缺秒不该导致整行被跳过）。
 */
private fun parseCsvTime(text: String): Long? {
    val t = text.trim()
    for (fmt in CSV_TIME_FMTS) {
        try {
            return LocalDateTime.parse(t, fmt)
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        } catch (_: Exception) { /* 试下一种 */ }
    }
    return null
}

/**
 * CSV 列序。按表头名字段解析，而不是写死 0..11 —— 两种导出格式列数与列序都不同：
 *  - 英文机读格式（本类 exportAndShareCsv）：`id,type,amount,category,merchant,source,account,note,is_manual,confidence,occurred_at,raw_text`
 *  - 中文对账格式（exportTransactionsCsv）：`日期时间,类型,分类,商户,金额,账本,备注,来源,状态`
 * 认不出的表头返回 null，调用方整份放弃——宁可明确失败，也不要按错列序把数据写乱。
 */
private class CsvCols(
    val id: Int, val type: Int, val amount: Int, val category: Int, val merchant: Int,
    val source: Int, val account: Int, val note: Int, val isManual: Int,
    val confidence: Int, val occurredAt: Int, val rawText: Int,
    val minSize: Int
) {
    companion object {
        private val ZH = mapOf(
            "日期时间" to "occurred_at", "类型" to "type", "分类" to "category", "商户" to "merchant",
            "金额" to "amount", "账本" to "account", "备注" to "note", "来源" to "source", "状态" to "is_manual"
        )

        fun parseCsvHeader(header: String): CsvCols? {
            val h = parseCsvLine(header).map { it.trim() }
            // 先按英文表头名映射，中文表头则先译名，两者得到同一套内部列序
            val canon =             if (h.contains("occurred_at")) h else h.map { ZH[it] ?: it }
            val occurred = canon.indexOf("occurred_at")
            val amount = canon.indexOf("amount")
            val type = canon.indexOf("type")
            if (occurred < 0 || amount < 0 || type < 0) return null
            fun opt(name: String) = canon.indexOf(name).takeIf { it >= 0 } ?: -1
            return CsvCols(
                id = opt("id"), type = type, amount = amount,
                // 缺失列一律保留 -1：下面统一由 cell() 兜底成空串。
                // 早先这里用 coerceAtLeast(0) 会把缺失列悄悄指向第 0 列，
                // 于是「分类/商户/备注」读到的是 id 或日期列，产生静默脏数据
                category = opt("category"),
                merchant = opt("merchant"),
                source = opt("source"), account = opt("account"),
                note = opt("note"),
                isManual = opt("is_manual"),
                confidence = opt("confidence"),
                occurredAt = occurred, rawText = opt("raw_text"),
                minSize = maxOf(occurred, amount, type) + 1
            )
        }
    }
}

internal suspend fun exportAndShareCsv(context: Context) {
    val db = AppDatabase.getInstance(context)
    val transactions = withContext(Dispatchers.IO) { db.transactionDao().getAll() }

    if (transactions.isEmpty()) {
        withContext(Dispatchers.Main) {
            android.widget.Toast.makeText(context, "还没有任何记录可以导出", android.widget.Toast.LENGTH_SHORT).show()
        }
        return
    }

    // 整段（含拼接与写文件）都在 IO 线程：万条记录时在主线程拼数 MB 字符串会卡住 UI
    val cacheFile = withContext(Dispatchers.IO) {
        val csv = buildString {
            append("\uFEFF") // BOM：Excel 中文兼容（导入端 parseCsvLine 已做 trimStart('\uFEFF')）
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
                    csvEscape(tx.note),
                    if (tx.isManual) "1" else "0",
                    tx.confidence.name,
                    formatCsvTime(tx.occurredAt),
                    csvEscape(tx.rawText)
                ).joinToString(","))
            }
        }
        val f = java.io.File(context.cacheDir, "bookkeeping_${currentFileStamp()}.csv")
        f.writeText(csv, Charsets.UTF_8)
        f
    }

    withContext(Dispatchers.Main) {
        shareCsvFile(
            context, cacheFile,
            "记账记录 ${transactions.size} 条",
            "已生成 ${transactions.size} 条记录 → 请选择分享目标"
        )
    }
}

private fun csvEscape(s: Any?): String {
    val str = s?.toString() ?: ""
    val escaped = str.replace("\"", "\"\"").replace("\n", " ").replace("\r", " ")
    return "\"$escaped\""
}

/**
 * 文件分享公共尾部：FileProvider URI + 系统分享面板 + 提示。
 * CSV 导出与 zip 备份共用，仅 mime / 文案不同。须在主线程调用。
 */
private fun shareFile(
    context: Context,
    file: java.io.File,
    mime: String,
    subject: String,
    chooserTitle: String,
    toast: String
) {
    val fileUri = androidx.core.content.FileProvider.getUriForFile(
        context, "${context.packageName}.fileprovider", file
    )
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, fileUri)
        putExtra(Intent.EXTRA_SUBJECT, subject)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(
        Intent.createChooser(shareIntent, chooserTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
    android.widget.Toast.makeText(context, toast, android.widget.Toast.LENGTH_LONG).show()
}

/** CSV 分享（须在主线程调用） */
private fun shareCsvFile(context: Context, file: java.io.File, subject: String, toast: String) =
    shareFile(context, file, "text/csv", subject, "分享 CSV", toast)

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
 * 返回 (新增条数, 跳过条数)。
 *
 * 三点改进：
 *  1. 流式逐行读取，不再 `readBytes()` 整个读进内存（大文件 OOM）
 *  2. 整个导入包在一个事务里：原先逐行独立提交，万行 CSV = 万次 fsync，
 *     且中途崩溃会留下半导入状态
 *  3. 尊重 CSV 里的 confidence 列决定 confirmed，不再一律硬编码 true
 */
internal suspend fun importCsvFromUri(context: Context, uri: android.net.Uri): Pair<Int, Int> {
    val db = AppDatabase.getInstance(context)
    val dao = db.transactionDao()
    return withContext(Dispatchers.IO) {
        val defaultLedgerId = db.ledgerDao().getDefault()?.id
            ?: db.ledgerDao().getAll().firstOrNull()?.id
        var imported = 0
        var skipped = 0
        val pending = mutableListOf<Transaction>()

        // 阶段 1：流式解析（不写库）
        context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { reader ->
            var index = 0
            // 表头决定列序：英文机读格式（本类 exportAndShareCsv 的产物，可完整回导）
            // 与中文对账格式（exportTransactionsCsv 的产物，供 Excel 看）两种都支持，
            // 否则用户在「导出 CSV」里拿到的那份文件在「导入」入口会全军覆没。
            var cols: CsvCols? = null
            reader.forEachLine { rawLine ->
                val i = index++
                val line = rawLine.trim().trimStart('\uFEFF')
                if (line.isEmpty()) return@forEachLine
                if (i == 0) {
                    cols = CsvCols.parseCsvHeader(line)
                    if (cols == null) { skipped++; return@forEachLine } // 认不出的表头，整份放弃而不是乱导
                    return@forEachLine
                }
                val c = cols ?: return@forEachLine

                val f = parseCsvLine(line)
                if (f.size < c.minSize) { skipped++; return@forEachLine }
                // 安全取值：可选列可能缺失（index = -1），也可能遇到被截断的行。
                // 中文导出格式没有 id 列，早先直接写 f[c.id] 就是 f[-1]，必崩
                fun cell(idx: Int) = if (idx >= 0) f.getOrElse(idx) { "" } else ""

                val amount = f[c.amount].trim().toDoubleOrNull()
                if (amount == null) { skipped++; return@forEachLine }
                val type = try {
                    Transaction.Type.valueOf(f[c.type].trim().uppercase())
                } catch (_: Exception) {
                    when (f[c.type].trim()) {   // 中文格式存的是「支出/收入/转账」
                        "收入" -> Transaction.Type.INCOME
                        "转账" -> Transaction.Type.TRANSFER
                        else -> Transaction.Type.EXPENSE
                    }
                }
                val confidence = try {
                    Transaction.Confidence.valueOf(cell(c.confidence).trim().uppercase())
                } catch (_: Exception) { Transaction.Confidence.HIGH }
                // 时间解析失败时如实计入跳过，而不是静默改成「导入时刻」让用户困惑
                val occurredAt = parseCsvTime(f[c.occurredAt])
                if (occurredAt == null) { skipped++; return@forEachLine }

                pending.add(
                    Transaction(
                        id = cell(c.id).trim().toLongOrNull() ?: 0L,
                        amount = amount,
                        type = type,
                        category = cell(c.category).trim(),
                        merchant = cell(c.merchant).trim(),
                        source = cell(c.source).trim().ifEmpty { "CSV导入" },
                        accountId = cell(c.account).trim().toLongOrNull(),
                        // CSV 不含账本列，补默认账本，避免导入后账本视图立即为空
                        ledgerId = defaultLedgerId,
                        note = cell(c.note).trim(),
                        rawText = cell(c.rawText),
                        isManual = cell(c.isManual).trim() == "1",
                        // 低置信度记录回到待确认队列，而不是绕过确认直接入账
                        confirmed = confidence != Transaction.Confidence.LOW,
                        confidence = confidence,
                        occurredAt = occurredAt
                    )
                )
            }
        } ?: return@withContext 0 to 0

        // 阶段 2：单事务批量写入
        if (pending.isNotEmpty()) {
            db.withTransaction {
                pending.forEach { tx ->
                    // IGNORE 策略：id 已存在时返回 -1，自动跳过重复
                    if (dao.insertIfNew(tx) == -1L) skipped++ else imported++
                }
            }
        }
        imported to skipped
    }
}

/** SQLite 文件头（用于校验备份文件合法性） */
private const val SQLITE_MAGIC = "SQLite format 3\u0000"

/** 只读文件前 16 字节判断是否 SQLite（原先 readBytes() 会把整个库读进内存） */
private fun hasSqliteMagic(file: java.io.File): Boolean = try {
    java.io.RandomAccessFile(file, "r").use { raf ->
        val buf = ByteArray(16)
        raf.read(buf) == 16 && String(buf, Charsets.US_ASCII) == SQLITE_MAGIC
    }
} catch (_: Exception) {
    false
}

/**
 * zip 条目名是否安全（不会写到目标目录之外）。
 * 归一化后必须仍在根目录内，挡住 `..`、绝对路径与 Windows 反斜杠写法。
 */
private fun isSafeEntryName(name: String): Boolean {
    if (name.isBlank() || name.contains('\u0000')) return false
    val root = java.nio.file.Paths.get("/").normalize()
    val resolved = root.resolve(name.replace('\\', '/')).normalize()
    return resolved.startsWith(root) && !resolved.toString().contains("..")
}

/**
 * 打包数据库（先合并 WAL 日志）+ 凭证图片到 destFile。
 * 手动备份与每周自动备份共用。zip 结构：bookkeeping.db + receipts/{txId}.jpg
 */
internal suspend fun createBackupZip(context: Context, destFile: java.io.File) = withContext(Dispatchers.IO) {
    val db = AppDatabase.getInstance(context)
    // 先把 WAL 日志合并进主文件，确保导出的 .db 是完整数据
    db.openHelper.writableDatabase
        .query(androidx.sqlite.db.SimpleSQLiteQuery("PRAGMA wal_checkpoint(FULL)"))
        .use { it.moveToFirst() }

    val dbFile = context.getDatabasePath("bookkeeping.db")
    ZipOutputStream(destFile.outputStream().buffered()).use { zip ->
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
}

/**
 * 备份：checkpoint WAL 后把数据库 + 凭证图片打包成 zip，并弹出系统分享面板。
 * zip 结构：bookkeeping.db + receipts/{txId}.jpg...
 */
internal suspend fun backupDatabase(context: Context) {
    val cacheFile = java.io.File(
        context.cacheDir,
        "bookkeeping_backup_${currentFileStamp()}.zip"
    )
    createBackupZip(context, cacheFile)

    withContext(Dispatchers.Main) {
        shareFile(
            context = context,
            file = cacheFile,
            mime = "application/octet-stream",
            subject = "记账助手备份",
            chooserTitle = "分享备份文件",
            toast = "备份已生成（${"%.2f".format(cacheFile.length() / 1024f / 1024f)}MB，含数据库+凭证图片）→ 请选择保存位置（微信/网盘/文件管理器）"
        )
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
                            // 路径穿越防护：用 Path.normalize() 校验落点确实在 tempDir 内。
                            // 原来的 `!name.contains("..") && !name.startsWith("/")` 挡不住
                            // Windows 风格的 `C:\...` 与反斜杠分隔符。
                            if (isSafeEntryName(entry.name)) {
                                val out = java.io.File(tempDir, entry.name)
                                if (entry.isDirectory) {
                                    out.mkdirs()
                                } else {
                                    out.parentFile?.mkdirs()
                                    out.outputStream().use { zis.copyTo(it) }
                                }
                            } else {
                                Log.w(BookkeepingApp.TAG, "跳过可疑 zip 条目：${entry.name}")
                            }
                            zis.closeEntry()
                            entry = zis.nextEntry
                        }
                    }
                } ?: return@withContext false

                val newDb = java.io.File(tempDir, "bookkeeping.db")
                if (!newDb.exists() || newDb.length() < 16 || !hasSqliteMagic(newDb)) {
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
        val file = java.io.File(context.cacheDir, "bookkeeping_export_${currentFileStamp()}.csv")
        java.io.PrintWriter(
            java.io.BufferedWriter(java.io.OutputStreamWriter(file.outputStream(), Charsets.UTF_8))
        ).use { w ->
            w.write("\uFEFF") // BOM：Excel 中文兼容
            w.println("日期时间,类型,分类,商户,金额,账本,备注,来源,状态")
            txs.forEach { tx ->
                w.println(
                    listOf(
                        formatTime(tx.occurredAt, "yyyy-MM-dd HH:mm"),
                        when (tx.type) {
                            Transaction.Type.EXPENSE -> "支出"
                            Transaction.Type.INCOME -> "收入"
                            else -> "转账"
                        },
                        csvEscape(tx.category), csvEscape(tx.merchant),
                        String.format("%.2f", tx.amount),
                        csvEscape(ledgerNames[tx.ledgerId] ?: ""),
                        csvEscape(tx.note), csvEscape(tx.source),
                        when {
                            tx.deletedAt > 0 -> "已删除"
                            tx.confirmed -> "已确认"
                            else -> "待确认"
                        }
                    ).joinToString(",")
                )
            }
        }
        file
    }

    withContext(Dispatchers.Main) {
        shareCsvFile(
            context, f,
            "记账助手交易明细导出",
            "已导出 ${f.name}（${f.length() / 1024}KB）→ 选择保存位置"
        )
    }
}

// ─── 规则刷新（通知监听 Service 热更新规则） ────────────────────

internal fun refreshServiceRules() {
    try {
        // Service 声明了 android:permission（系统签名权限），同进程 startService 会
        // SecurityException 且被吞掉，改用进程内回调钩子直接触发规则重载；
        // 钩子为 null 说明 Service 未在运行，无需刷新（下次创建时会自动从 DB 加载）。
        NotificationCaptureService.rulesRefresher?.invoke()
    } catch (e: Exception) {
        // 原先完全静默吞掉，规则热刷新失败无从排查
        Log.w(BookkeepingApp.TAG, "refreshServiceRules 失败", e)
    }
}
