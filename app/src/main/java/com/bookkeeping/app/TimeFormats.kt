package com.bookkeeping.app

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * 渲染路径复用的时间格式化工具（java.time，minSdk 26+）。
 *
 * 以往各页面在 Composable 内反复 `new SimpleDateFormat(...)`，每次重组都要重新解析 pattern；
 * 这里按 pattern 缓存 [DateTimeFormatter]（不可变、线程安全），跨重组复用。
 */
private val FORMATTER_CACHE = ConcurrentHashMap<String, DateTimeFormatter>()

/** 按 [pattern] 格式化毫秒时间戳（系统默认时区 + 默认 Locale） */
internal fun formatTime(millis: Long, pattern: String): String =
    FORMATTER_CACHE
        .getOrPut(pattern) { DateTimeFormatter.ofPattern(pattern, Locale.getDefault()) }
        .format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

/** 当前时间按 [pattern] 格式化 */
internal fun formatNow(pattern: String): String = formatTime(System.currentTimeMillis(), pattern)
