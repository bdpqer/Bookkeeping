package com.bookkeeping.app

/** 金额输入允许的字符：仅保留数字与小数点 */
fun String.keepAmountChars(): String = filter { it.isDigit() || it == '.' }

/**
 * 金额输入净化：仅允许数字与最多一个小数点，整数部分最多 9 位、小数最多 2 位。
 *
 * @return 净化后的文本；出现多个小数点（非法输入）时返回 null，
 *         调用方应丢弃本次输入，即 `amount = sanitizeAmountInput(input) ?: return@xxx`。
 */
fun sanitizeAmountInput(input: String): String? {
    val filtered = input.keepAmountChars()
    val parts = filtered.split('.')
    return when {
        filtered.count { it == '.' } > 1 -> null
        parts.size == 2 && parts[1].length > 2 -> parts[0] + "." + parts[1].take(2)
        parts[0].length > 9 -> parts[0].take(9) + (if (parts.size == 2) "." + parts[1].take(2) else "")
        else -> filtered
    }
}
