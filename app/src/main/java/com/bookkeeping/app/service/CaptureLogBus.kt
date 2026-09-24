package com.bookkeeping.app.service

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.bookkeeping.app.BookkeepingApp

/**
 * 实时捕获通知和短信的统一日志管道。
 * 所有捕获到的原始文本都先送这里，UI 通过 Flow 订阅展示。
 */
object CaptureLogBus {

    data class CaptureEntry(
        val time: Long,
        val source: String,        // "NOTIFICATION" / "SMS"
        val packageName: String?,  // 通知来源 App 包名
        val sender: String?,       // 短信发件人 / 通知标题
        val title: String?,        // 通知标题
        val text: String?,         // 通知正文
        val fullDump: String,      // 完整字段 dump（调试用）
        val rawText: String        // 原始完整文本（用于后续解析）
    ) {
        val displayTime: String get() = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date(time))
    }

    private val _entries = java.util.concurrent.CopyOnWriteArrayList<CaptureEntry>()
    val entries: List<CaptureEntry> get() = _entries.toList()

    private val listeners = mutableListOf<(List<CaptureEntry>) -> Unit>()

    @Synchronized
    fun add(entry: CaptureEntry) {
        _entries.add(0, entry)
        // 限制最多 200 条，防止内存膨胀
        if (_entries.size > 200) _entries.subList(200, _entries.size).clear()
        val snapshot = _entries.toList()
        listeners.forEach { it(snapshot) }
        Log.d(BookkeepingApp.TAG, "📥 ${entry.source} [${entry.displayTime}] ${entry.packageName ?: entry.sender}: ${entry.rawText.take(80)}")
    }

    fun subscribe(listener: (List<CaptureEntry>) -> Unit): () -> Unit {
        listeners.add(listener)
        listener(_entries.toList())
        return { listeners.remove(listener) }
    }

    fun clear() {
        _entries.clear()
        listeners.forEach { it(emptyList()) }
    }
}
