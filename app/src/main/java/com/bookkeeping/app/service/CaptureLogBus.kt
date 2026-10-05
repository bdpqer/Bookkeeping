package com.bookkeeping.app.service

import android.util.Log
import com.bookkeeping.app.BookkeepingApp
import com.bookkeeping.app.formatTime

/**
 * 实时捕获通知和短信的统一日志管道。
 * 所有捕获到的原始文本都先送这里，UI 通过 Flow 订阅展示。
 */
object CaptureLogBus {

    /** 调试面板保留的最大条数。纯内存存储，进程被杀即清空，故上限只影响内存占用（约 500 × 数 KB） */
    const val MAX_ENTRIES = 500

    /** 自增序号：为每条记录生成稳定的唯一 key，供 LazyColumn 使用 */
    private val uidSeq = java.util.concurrent.atomic.AtomicLong(0)

    data class CaptureEntry(
        val uid: Long = uidSeq.incrementAndGet(),
        val time: Long,
        val source: String,        // "NOTIFICATION" / "SMS"
        val packageName: String?,  // 通知来源 App 包名
        val sender: String?,       // 短信发件人 / 通知标题
        val title: String?,        // 通知标题
        val text: String?,         // 通知正文
        val fullDump: String,      // 完整字段 dump（调试用）
        val rawText: String        // 原始完整文本（用于后续解析）
    ) {
        val displayTime: String get() = formatTime(time, "HH:mm:ss")
    }

    private val _entries = java.util.concurrent.CopyOnWriteArrayList<CaptureEntry>()
    val entries: List<CaptureEntry> get() = _entries.toList()

    // 监听器也用 COW，保证 add() 里的 forEach 与并发 subscribe/unsubscribe 不冲突
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(List<CaptureEntry>) -> Unit>()

    fun add(entry: CaptureEntry) {
        _entries.add(0, entry)
        // 限制最多 500 条，防止内存膨胀。
        // 注意：只按条数截断，不按时间过期——老记录会一直留到被新记录挤掉为止。
        if (_entries.size > MAX_ENTRIES) _entries.subList(MAX_ENTRIES, _entries.size).clear()
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
