package com.bookkeeping.app.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.bookkeeping.app.BookkeepingApp
import com.bookkeeping.app.data.AppDatabase
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 每周自动备份 Worker：
 * 打包数据库 + 凭证图片为 zip，写入应用外部私有目录 backup/（无需存储权限），
 * 保留最近 4 份轮换。备份对话框可开关，App 启动时保活调度。
 */
class AutoBackupWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val context = applicationContext
            val db = AppDatabase.getInstance(context)
            // WAL 合并进主文件，保证导出的 .db 完整
            db.openHelper.writableDatabase
                .query(androidx.sqlite.db.SimpleSQLiteQuery("PRAGMA wal_checkpoint(FULL)"))
                .use { it.moveToFirst() }

            val outDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "backup")
            outDir.mkdirs()
            val out = File(
                outDir,
                "bookkeeping_auto_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.zip"
            )
            ZipOutputStream(out.outputStream().buffered()).use { zip ->
                zip.putNextEntry(ZipEntry("bookkeeping.db"))
                context.getDatabasePath("bookkeeping.db").inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                val receipts = File(context.filesDir, "receipts")
                receipts.listFiles()
                    ?.filter { it.isFile && it.length() > 0 }
                    ?.sortedBy { it.name }
                    ?.forEach { img ->
                        zip.putNextEntry(ZipEntry("receipts/${img.name}"))
                        img.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
            }
            // 轮换：只保留最近 4 份
            outDir.listFiles()
                ?.filter { it.name.startsWith("bookkeeping_auto_") }
                ?.sortedByDescending { it.name }
                ?.drop(4)
                ?.forEach { it.delete() }

            prefs(context).edit().putLong(KEY_LAST, System.currentTimeMillis()).apply()
            Log.i(BookkeepingApp.TAG, "✅ 自动备份完成 → ${out.name}")
            Result.success()
        } catch (e: Exception) {
            Log.e(BookkeepingApp.TAG, "自动备份失败", e)
            Result.retry()
        }
    }

    companion object {
        private const val PREFS = "auto_backup_prefs"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_LAST = "last_time"
        private const val UNIQUE_NAME = "auto_backup_weekly"

        private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

        fun lastBackupAt(context: Context): Long = prefs(context).getLong(KEY_LAST, 0L)

        /** 开关：开启即排每周任务，关闭即取消 */
        fun setEnabled(context: Context, on: Boolean) {
            prefs(context).edit().putBoolean(KEY_ENABLED, on).apply()
            if (on) schedule(context) else cancel(context)
        }

        /** App 启动时保活：开关开着就确保任务已注册 */
        fun ensureScheduled(context: Context) {
            if (isEnabled(context)) schedule(context)
        }

        private fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(7, TimeUnit.DAYS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request
            )
        }

        private fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
        }
    }
}
