package com.bookkeeping.app.service

import android.util.Log
import com.bookkeeping.app.BookkeepingApp
import com.bookkeeping.app.formatTime

/**
 * 实时捕获通知和短信的统一日志管道。
 * 所有捕获到的原始文本都先送这里，UI 通过 Flow 订阅展示。
 *
 * ⚠️ 写入位置在 [NotificationCaptureService.onNotificationPosted] 的**协程之外**，
 * 无条件执行——不受捕获侧节流表保护。因此噪声（迅雷进度、USB 状态、
 * NFC 提示…）会持续涌入，靠 [MAX_ENTRIES] 截断只会把真正有价值的记录挤出去。
 * 这里用「按归一化键聚合」解决：同一语义的通知合并成一条，只累加次数。
 */
object CaptureLogBus {

    /** 调试面板保留的最大条数。纯内存存储，进程被杀即清空，故上限只影响内存占用 */
    const val MAX_ENTRIES = 500

    /** 同一聚合键最多保留的历史明细条数，防止单个高频源把内存吃光 */
    const val MAX_HISTORY = 12

    // ─── 归一化规则（与捕获侧节流键同一套逻辑）───────────────────────

    /** 把连续数字（含小数）统一替换成 #，缓存避免每次重建 */
    private val NORMALIZE_DIGITS = Regex("\\d+(?:[.,]\\d+)?")

    /**
     * 面板聚合键 = 来源标识 | 来源包/发件人 | 归一化文本。
     * 迅雷的「3个任务正在下载（总速度:48KB/s）」/「47」/「49」…塌成同一个键，
     * 于是 26 条噪声合并成 1 行「×26 次」，而不是占掉 26 个名额。
     */
    private fun aggregateKey(source: String, pkgOrSender: String, rawText: String): String =
        "$source|$pkgOrSender|${NORMALIZE_DIGITS.replace(rawText, "#")}"

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
        val rawText: String,       // 原始完整文本（用于后续解析）
        /** 同一聚合键下累计出现的次数（>=1） */
        val count: Int = 1,
        /** 首次出现时间，面板展示「首次 … 最近 …」用 */
        val firstTime: Long = time,
        /** 保留的历史明细（时间 + 原文），展开时可见，最多 [MAX_HISTORY] 条 */
        val history: List<Pair<Long, String>> = emptyList()
    ) {
        val displayTime: String get() = formatTime(time, "HH:mm:ss")
        val displayFirstTime: String get() = formatTime(firstTime, "HH:mm:ss")
        val isAggregated: Boolean get() = count > 1
    }

    private val _entries = java.util.concurrent.CopyOnWriteArrayList<CaptureEntry>()
    val entries: List<CaptureEntry> get() = _entries.toList()

    // 监听器用 COW，保证 notifyListeners 里的 forEach 与并发 subscribe/unsubscribe 不冲突
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(List<CaptureEntry>) -> Unit>()

    /** 聚合键 -> 当前持有该键的 entry.uid，用于把新记录并到已有行而不是新增一行 */
    private val keyToUid = java.util.HashMap<String, Long>()

    fun add(entry: CaptureEntry) {
        val key = aggregateKey(
            entry.source,
            entry.packageName ?: entry.sender.orEmpty(),
            entry.rawText
        )
        synchronized(this) {
            val existingUid: Long? = keyToUid[key]
            val idx: Int = existingUid?.let { uid -> _entries.indexOfFirst { e -> e.uid == uid } } ?: -1
            if (idx >= 0) {
                val old = _entries[idx]
                // 旧行的 time/rawText 转入历史，新记录成为这一行的当前值（置顶）
                val history = (old.history + (old.time to old.rawText)).takeLast(MAX_HISTORY)
                _entries.removeAt(idx)
                val newer = entry.copy(
                    count = old.count + 1,
                    firstTime = old.firstTime,
                    history = history
                )
                _entries.add(0, newer)
                keyToUid[key] = newer.uid
            } else {
                _entries.add(0, entry)
                keyToUid[key] = entry.uid
            }
        }

        // 限制最多 MAX_ENTRIES 条，防止内存膨胀。只按条数截断，不按时间过期
        if (_entries.size > MAX_ENTRIES) {
            val overflow: List<CaptureEntry> = _entries.subList(MAX_ENTRIES, _entries.size).toList()
            _entries.subList(MAX_ENTRIES, _entries.size).clear()
            val staleUids = overflow.map { e -> e.uid }.toSet()
            keyToUid.entries.removeAll { kv -> staleUids.contains(kv.value) }
        }

        notifyListeners()
        Log.d(
            BookkeepingApp.TAG,
            "📥 ${entry.source} [${entry.displayTime}] " +
                "${entry.packageName ?: entry.sender}: ${entry.rawText.take(80)}"
        )
    }

    private fun notifyListeners() {
        val snapshot = _entries.toList()
        listeners.forEach { it(snapshot) }
    }

    fun subscribe(listener: (List<CaptureEntry>) -> Unit): () -> Unit {
        listeners.add(listener)
        listener(_entries.toList())
        return { listeners.remove(listener) }
    }

    fun clear() {
        synchronized(this) {
            _entries.clear()
            keyToUid.clear()
        }
        notifyListeners()
    }
}