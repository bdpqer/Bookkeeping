package com.bookkeeping.app.parser

import com.bookkeeping.app.data.entity.MerchantRule
import com.bookkeeping.app.data.entity.ParseRule
import com.bookkeeping.app.data.entity.Transaction

/**
 * 解析引擎：把短信/通知的原始文本 → Transaction。
 *
 * 流程：匹配规则 → 提取字段 → 判断置信度 → 组装 Transaction
 */
class ParseEngine(
    private val rules: List<ParseRule> = DEFAULT_RULES,
    private val merchantRules: List<MerchantRule> = emptyList()
) {

    fun parse(
        rawText: String,
        sourcePackage: String? = null,
        sourceSender: String? = null,
        sourceChannel: String = "未知",
        occurredAt: Long = System.currentTimeMillis()
    ): Transaction? {
        val text = rawText.trim()
        if (text.isBlank()) return null

        // 1. 匹配规则（按 priority 从高到低）
        val matched = rules.firstOrNull { it.isEnabled && matchRule(it, text, sourcePackage, sourceSender) }

        // 2. 提取字段
        val amount = extractAmount(text, matched)
        if (amount == null || amount <= 0) return null  // 没金额 = 没法记账

        val type = determineType(text, matched)
        val merchant = matched?.patternMerchant?.let { extractFirst(text, it) } ?: ""
        val category = guessCategory(text, merchant, sourcePackage)
        val time = matched?.patternTime?.let { extractTime(text, it) } ?: occurredAt

        // 3. 判断置信度
        val confidence = when {
            matched != null -> Transaction.Confidence.HIGH
            amount != null && type != Transaction.Type.TRANSFER -> Transaction.Confidence.MEDIUM
            else -> Transaction.Confidence.LOW
        }

        return Transaction(
            amount = amount,
            type = type,
            category = category,
            merchant = merchant,
            source = sourceChannel,
            rawText = text,
            isManual = false,
            confirmed = confidence == Transaction.Confidence.HIGH,
            confidence = confidence,
            occurredAt = time
        )
    }

    // ─── 规则匹配 ──────────────────────────────────────────────

    private fun matchRule(rule: ParseRule, text: String, pkg: String?, sender: String?): Boolean {
        // 精确匹配包名
        if (pkg != null && rule.channel == pkg) return true
        // 关键词匹配
        if (rule.matchKeyword.isNotBlank() && text.contains(rule.matchKeyword)) return true
        if (sender != null && rule.matchKeyword.isNotBlank() && sender.contains(rule.matchKeyword)) return true
        return false
    }

    // ─── 字段提取 ──────────────────────────────────────────────

    private fun extractAmount(text: String, rule: ParseRule?): Double? {
        val patterns = buildList {
            // 优先用规则里的
            if (rule != null) add(rule.patternAmount)
            // 通用兜底模式
            add("""[¥￥]\s*([\d,]+\.\d{1,2})""")           // ¥1,234.56 或 ￥1234.56
            add("""[¥￥]\s*(\d+(?:\.\d{1,2})?)""")         // ¥5 或 ¥5.00
            add("""人民币\s*([\d,]+\.\d{1,2})""")           // 人民币1,234.56
            add("""人民币\s*(\d+(?:\.\d{1,2})?)""")         // 人民币5
            add("""金额[：:]\s*([\d,]+\.\d{1,2})""")        // 金额：1234.56
            add("""金额[：:]\s*(\d+(?:\.\d{1,2})?)""")      // 金额：5
            add("""([\d,]+\.\d{1,2})\s*元""")               // 1234.56元
            add("""(\d+(?:\.\d{1,2})?)\s*元""")             // 5元
            // 微信/支付宝消费可能有 "付款 ¥XX"
            add("""(?:付款|支付|消费)[^¥￥]{0,5}[¥￥]?\s*(\d+(?:\.\d{1,2})?)""")
        }
        for (p in patterns) {
            val match = Regex(p).find(text)
            if (match != null) {
                val num = match.groupValues[1].replace(",", "").toDoubleOrNull()
                if (num != null && num > 0) return num
            }
        }
        return null
    }

    private fun extractFirst(text: String, pattern: String): String {
        val match = Regex(pattern).find(text) ?: return ""
        return match.groupValues.getOrNull(1) ?: match.value
    }

    private fun extractTime(text: String, pattern: String): Long {
        val match = Regex(pattern).find(text) ?: return System.currentTimeMillis()
        // 简单处理：直接返回匹配文本作为时间（实际应该 parse 成时间戳）
        // 先返回当前时间，后续有需要再增强
        return System.currentTimeMillis()
    }

    // ─── 类型判断 ──────────────────────────────────────────────

    private fun determineType(text: String, rule: ParseRule?): Transaction.Type {
        // 先从规则判断
        if (rule != null) {
            if (Regex(rule.patternExpense).containsMatchIn(text)) return Transaction.Type.EXPENSE
            if (Regex(rule.patternIncome).containsMatchIn(text)) return Transaction.Type.INCOME
            if (Regex(rule.patternTransfer).containsMatchIn(text)) return Transaction.Type.TRANSFER
        }
        // 通用兜底
        return when {
            Regex("""收入|入账|汇款转入|转入|到账|退款|获得""").containsMatchIn(text) -> Transaction.Type.INCOME
            Regex("""转账|互联汇出|互联汇入""").containsMatchIn(text) -> Transaction.Type.TRANSFER
            Regex("""支出|消费|扣款|支付|汇出|转出|付款|扣费|还款""").containsMatchIn(text) -> Transaction.Type.EXPENSE
            else -> Transaction.Type.EXPENSE  // 默认支出
        }
    }

    // ─── 分类猜测（查 DB 商家规则 → 硬编码兜底） ──────────────────

    private fun guessCategory(text: String, merchant: String, pkg: String?): String {
        val combined = "$text $merchant".lowercase()

        // 1. 先查用户配置的商家规则（DB 里的 MerchantRule）
        merchantRules.forEach { rule ->
            if (combined.contains(rule.keyword.lowercase())) {
                return rule.category
            }
        }

        // 2. 硬编码兜底（保留向后兼容 + 初始无规则时也能用）
        return when {
            combined.contains("转账") || combined.contains("互联汇出") || combined.contains("互联汇入")
                || combined.contains("汇出") || combined.contains("汇入") || combined.contains("转出")
                || combined.contains("转入")
                -> "转账"
            combined.contains("咖啡") || combined.contains("星巴克") || combined.contains("瑞幸")
                || combined.contains("奶茶") || combined.contains("喜茶") || combined.contains("蜜雪冰城")
                -> "餐饮/饮品"
            combined.contains("美团") || combined.contains("饿了么") || combined.contains("外卖")
                -> "餐饮/外卖"
            combined.contains("滴滴") || combined.contains("打车") || combined.contains("地铁")
                || combined.contains("公交") || combined.contains("高铁") || combined.contains("机票")
                -> "交通"
            combined.contains("拼多多") || combined.contains("淘宝") || combined.contains("京东")
                || combined.contains("购物")
                -> "购物"
            combined.contains("工资") || combined.contains("薪") || combined.contains("发放")
                -> "工资"
            combined.contains("红包") || combined.contains("微信红包")
                -> "红包"
            combined.contains("还款") || combined.contains("信用卡")
                -> "还款"
            combined.contains("理财") || combined.contains("基金") || combined.contains("利息")
                -> "理财"
            combined.contains("水费") || combined.contains("电费") || combined.contains("燃气")
                || combined.contains("话费") || combined.contains("宽带")
                -> "居住"
            combined.contains("医院") || combined.contains("药") || combined.contains("挂号")
                -> "医疗"
            else -> "其他"
        }
    }

    // ─── 预定义规则库 ──────────────────────────────────────────

    companion object {
        /** 预定义正则规则（硬编码在代码里，后续可迁移到 DB 让用户配置） */
        val DEFAULT_RULES: List<ParseRule> = listOf(

            // 🟢 浦发银行 App 通知
            ParseRule(
                channel = "cn.com.spdb.mobilebank.per",
                channelName = "浦发银行",
                matchKeyword = "浦发",
                patternAmount = """人民币\s*([\d,]+\.\d{1,2})|人民币\s*(\d+(?:\.\d{1,2})?)|([\d,]+\.\d{1,2})""",
                patternExpense = "支出|汇出|扣款",
                patternIncome = "收入|入账|汇入",
                patternTransfer = "转账|互联汇出|互联汇入",
                priority = 100
            ),

            // 🟢 工商银行
            ParseRule(
                channel = "com.icbc",
                channelName = "工商银行",
                matchKeyword = "工行|95588",
                patternAmount = """人民币\s*([\d,]+\.\d{1,2})|¥\s*([\d,]+\.\d{1,2})|([\d,]+\.\d{1,2})""",
                patternExpense = "支出|消费|扣款|支出",
                patternIncome = "收入|入账|汇入",
                priority = 90
            ),

            // 🟢 招商银行
            ParseRule(
                channel = "cmb.pb",
                channelName = "招商银行",
                matchKeyword = "招行|95555",
                patternAmount = """人民币\s*([\d,]+\.\d{1,2})|([\d,]+\.\d{1,2})""",
                patternExpense = "支出|消费|扣款",
                patternIncome = "收入|入账",
                priority = 90
            ),

            // 🟢 微信支付（消费通知——如果有金额的话）
            ParseRule(
                channel = "com.tencent.mm",
                channelName = "微信支付",
                matchKeyword = "微信支付|付款|消费",
                patternAmount = """¥\s*([\d,]+\.\d{1,2})|¥\s*(\d+(?:\.\d{1,2})?)""",
                patternExpense = "付款|支付|消费",
                patternIncome = "收款|到账|红包|转账收入",
                priority = 80
            ),

            // 🟢 支付宝
            ParseRule(
                channel = "com.eg.android.AlipayGphone",
                channelName = "支付宝",
                matchKeyword = "支付宝|花呗|余额宝",
                patternAmount = """¥\s*([\d,]+\.\d{1,2})|¥\s*(\d+(?:\.\d{1,2})?)""",
                patternExpense = "付款|支付|消费|扣款",
                patternIncome = "收入|退款|到账",
                priority = 80
            ),

            // 🟢 云闪付
            ParseRule(
                channel = "com.unionpay",
                channelName = "云闪付",
                matchKeyword = "云闪付|银联",
                patternAmount = """¥\s*([\d,]+\.\d{1,2})|([\d,]+\.\d{1,2})""",
                patternExpense = "消费|支付|扣款",
                patternIncome = "收入|入账",
                priority = 70
            ),

            // 🟢 银行短信通用模板（最低优先级兜底）
            ParseRule(
                channel = "bank_sms",
                channelName = "银行短信",
                matchKeyword = "尾号|账户|卡于|消费|收入|支出|入账|扣款",
                patternAmount = """([\d,]+\.\d{1,2})\s*元|人民币\s*([\d,]+\.\d{1,2})|¥\s*([\d,]+\.\d{1,2})""",
                patternExpense = "支出|消费|扣款|汇出|转出",
                patternIncome = "收入|入账|汇入|转入|到账",
                patternTransfer = "转账|互联汇出|互联汇入",
                priority = 10
            )
        )
    }
}
