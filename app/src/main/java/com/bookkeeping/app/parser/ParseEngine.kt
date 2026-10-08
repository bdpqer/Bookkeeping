package com.bookkeeping.app.parser

import android.util.Log
import com.bookkeeping.app.BookkeepingApp
import com.bookkeeping.app.data.entity.MerchantRule
import com.bookkeeping.app.data.entity.ParseRule
import com.bookkeeping.app.data.entity.Transaction
import com.bookkeeping.app.digitOf
import com.bookkeeping.app.parseChineseNumber
import com.bookkeeping.app.round2

/** 日志标签，与其他模块统一用 BookkeepingApp.TAG */
private const val TAG = BookkeepingApp.TAG

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
        // 整体兜底：用户配置的规则/正则非法时绝不能让通知服务崩溃
        return try {
            parseInternal(text, sourcePackage, sourceSender, sourceChannel, occurredAt)
        } catch (e: Exception) {
            // 兜底保持不变（绝不让通知服务崩溃），但必须留痕：
            // 原先连异常对象都丢掉，用户配了非法正则只会看到"解析失败"，无从排查
            Log.w(TAG, "解析失败（可能是用户配置的正则非法）", e)
            null
        }
    }

    private fun parseInternal(
        text: String,
        sourcePackage: String?,
        sourceSender: String?,
        sourceChannel: String,
        occurredAt: Long
    ): Transaction? {
        // 1. 匹配规则（按 priority 从高到低）
        val matched = rules.firstOrNull { it.isEnabled && matchRule(it, text, sourcePackage, sourceSender) }

        // 2. 提取字段
        val amount = extractAmount(text, matched)
        if (amount == null || amount <= 0) return null  // 没金额 = 没法记账

        val type = determineType(text, matched)
        val merchant = matched?.patternMerchant?.let { extractFirst(text, it) } ?: ""
        val category = guessCategory(text, merchant, sourcePackage)
        // 时间：直接用捕获方传入的事件时间（通知到达/短信日期）；
        // patternTime 字段暂未实现真正的文本时间解析，留着待增强
        val time = occurredAt

        // 3. 判断置信度（amount 在 L46 已确保非空，无需再判）
        val confidence = when {
            matched != null -> Transaction.Confidence.HIGH
            type != Transaction.Type.TRANSFER -> Transaction.Confidence.MEDIUM
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
            // 所有自动捕获（通知/短信）一律先进待确认列表，手动确认后才转正为正式记账；
            // confidence 仅作为待确认列表的参考标记，不再用于自动转正
            confirmed = false,
            confidence = confidence,
            occurredAt = time
        )
    }

    // ─── 规则匹配 ──────────────────────────────────────────────

    private fun matchRule(rule: ParseRule, text: String, pkg: String?, sender: String?): Boolean {
        // 精确匹配包名（通知路径主要靠这条）
        if (pkg != null && rule.channel == pkg) return true
        val kw = rule.matchKeyword
        if (kw.isBlank()) return false
        // 关键词匹配（短信路径没有包名，只能靠关键词）
        if (matchesKeyword(kw, text)) return true
        if (sender != null && matchesKeyword(kw, sender)) return true
        return false
    }

    /**
     * 关键词匹配：先整串包含，再按「|」拆分逐个包含。
     *
     * ⚠️ 不能只写 `target.contains(keyword)`：默认规则的关键词是「工行|95588」
     * 这种多备选写法，而 contains 是字面量匹配，会去找短信里真的出现
     * 「工行|95588」这串字符（含竖线）——永远不成立。短信路径没有包名可精确匹配，
     * 结果就是短信侧所有规则全部失效，只能走无规则的通用兜底。
     */
    private fun matchesKeyword(keyword: String, target: String): Boolean {
        if (target.isBlank()) return false
        if (target.contains(keyword)) return true          // 单值关键词（如「浦发」）
        if (!keyword.contains("|")) return false
        // 多备选：按竖线拆分，任一命中即可（trim 容忍「工行| 95588」这类写法）
        return keyword.split("|").any { part ->
            val p = part.trim()
            p.isNotBlank() && target.contains(p)
        }
    }

    // ─── 字段提取 ──────────────────────────────────────────────

    private fun extractAmount(text: String, rule: ParseRule?): Double? {
        // 优先用规则里的（安全编译，非法正则跳过）
        if (rule != null) {
            safeRegex(rule.patternAmount)?.let { re ->
                findAmount(re, text)?.let { return it }
            }
        }
        // 交易动词锚定：「消费/支出/支付…」紧邻的数字优先取，
        // 否则「您的账户余额1,234.56元，消费100元」会被前面那条余额抢走
        for (re in VERB_AMOUNT_REGEXES) {
            findAmount(re, text)?.let { return it }
        }
        // 锚定模式：货币符号/「元」紧跟数字（预编译）
        for (re in ANCHORED_AMOUNT_REGEXES) {
            findAmount(re, text)?.let { return it }
        }
        // 口语「块」：88块5 / 88块5毛 / 45块8毛2 / 五十八块五（X块Y毛Z = X+Y/10+Z/100）
        COLLOQUIAL_KUAI_REGEX.find(text)?.let { m ->
            val whole = m.groupValues[1].toDoubleOrNull() ?: parseChineseNumber(m.groupValues[1])
            if (whole != null && whole > 0) {
                val mao = digitOf(m.groupValues[2])
                val fen = digitOf(m.groupValues[3])
                return (whole + (mao ?: 0.0) / 10.0 + (fen ?: 0.0) / 100.0).round2()
            }
        }
        // 中文数字 + 元：三十五元 / 六十元
        CN_YUAN_REGEX.find(text)?.let { m ->
            parseChineseNumber(m.groupValues[1])?.let { return it }
        }
        // 松散模式最后兜底：花了50 / 花费50 / 支付50（可能截断「88块5」式小数，故放在口语模式之后）
        for (re in LOOSE_AMOUNT_REGEXES) {
            findAmount(re, text)?.let { return it }
        }
        return null
    }

    /**
     * 取金额：遍历该正则的**所有**匹配，跳过「余额/额度」上下文里的数字，
     * 返回第一个像交易金额的那个（跳过 group 0 整体匹配；兼容多分支 alternation 模式）。
     *
     * ⚠️ 原来是 `re.find()` 只取第一个匹配就返回，于是
     * 「余额1,234.56元，消费100元」会被记成 1234.56 —— 余额数字在文本里更靠前。
     */
    private fun findAmount(re: Regex, text: String): Double? {
        for (match in re.findAll(text)) {
            val raw = match.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: match.value
            val num = raw.replace(",", "").toDoubleOrNull() ?: continue
            if (num <= 0) continue
            // 数字前面紧挨着「余额/可用额度…」→ 不是本次交易金额，跳过继续找
            val prefix = text.substring(maxOf(0, match.range.first - 16), match.range.first)
            if (BALANCE_PREFIX_REGEX.containsMatchIn(prefix)) continue
            return num
        }
        return null
    }

    private fun extractFirst(text: String, pattern: String): String {
        val match = safeRegex(pattern)?.find(text) ?: return ""
        // 取第一个非空捕获组：多分支正则（如「【(.+?)】|商户[：:](\S+)」）
        // 命中后面分支时 groupValues[1] 是空串而不是 null，直接取 [1] 会得到空商户
        return match.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: match.value
    }

    // ─── 类型判断 ──────────────────────────────────────────────

    private fun determineType(text: String, rule: ParseRule?): Transaction.Type {
        // 先从规则判断（安全编译，非法正则跳过该项）
        if (rule != null) {
            if (safeRegex(rule.patternExpense)?.containsMatchIn(text) == true) return Transaction.Type.EXPENSE
            if (safeRegex(rule.patternIncome)?.containsMatchIn(text) == true) return Transaction.Type.INCOME
            if (safeRegex(rule.patternTransfer)?.containsMatchIn(text) == true) return Transaction.Type.TRANSFER
        }
        // 通用兜底（预编译）
        return when {
            COMMON_INCOME_REGEX.containsMatchIn(text) -> Transaction.Type.INCOME
            COMMON_TRANSFER_REGEX.containsMatchIn(text) -> Transaction.Type.TRANSFER
            COMMON_EXPENSE_REGEX.containsMatchIn(text) -> Transaction.Type.EXPENSE
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
            combined.contains("拼多多") || combined.contains("淘宝") || combined.contains("京东")
                || combined.contains("购物")
                -> "购物"
            combined.contains("咖啡") || combined.contains("星巴克") || combined.contains("瑞幸")
                || combined.contains("奶茶") || combined.contains("喜茶") || combined.contains("蜜雪冰城") 
                || combined.contains("美团") || combined.contains("饿了么") || combined.contains("外卖")
                -> "餐饮"
            combined.contains("水费") || combined.contains("电费") || combined.contains("燃气")
                || combined.contains("话费") || combined.contains("宽带")
                -> "居住"
            combined.contains("滴滴") || combined.contains("打车") || combined.contains("地铁")
                || combined.contains("公交") || combined.contains("高铁") || combined.contains("机票")
                -> "交通"
            combined.contains("转账") || combined.contains("互联汇出") || combined.contains("互联汇入")
                || combined.contains("汇出") || combined.contains("汇入") || combined.contains("转出")
                || combined.contains("转入")
                -> "转账"
            combined.contains("工资") || combined.contains("薪") || combined.contains("发放")
                -> "工资"
            combined.contains("红包") || combined.contains("微信红包")
                -> "红包"
            combined.contains("还款") || combined.contains("信用卡")
                -> "还款"
            combined.contains("理财") || combined.contains("基金") || combined.contains("利息")
                -> "理财"
            combined.contains("医院") || combined.contains("药") || combined.contains("挂号")
                -> "医疗"
            else -> "其他"
        }
    }

    // ─── 预定义规则库 ──────────────────────────────────────────

    companion object {
        /** 安全编译正则：非法 pattern 返回 null 而不是抛异常 */
        private fun safeRegex(pattern: String?): Regex? =
            if (pattern.isNullOrBlank()) null
            else try {
                Regex(pattern)
            } catch (e: Exception) {
                Log.w(TAG, "跳过非法正则：$pattern", e)
                null
            }

        /**
         * 余额/额度上下文：数字前面紧挨（中间只隔空格、冒号、数字、逗号、小数点）
         * 这些词时，该数字是账户余额不是交易金额，必须跳过。
         */
        private val BALANCE_PREFIX_REGEX =
            Regex("""(?:余额|可用额度|剩余额度|信用额度|总额度|当前余额|账户余额)[\s:：为是0-9,，.]{0,10}$""")

        /**
         * 交易动词锚定：动词后面 6 个字符内紧跟的数字，优先级高于通用锚定模式。
         * 这样「余额1,234.56元，消费100元」能直接锁定「消费」后面的 100。
         */
        private val VERB_AMOUNT_REGEXES: List<Regex> = listOf(
            Regex("""(?:消费|支出|支付|付款|扣款|扣费|花费|花了|转账|汇出|转出|收入|入账|到账|收款|退款|还款|实付)[^\d¥￥]{0,6}[¥￥]?\s*([\d,]+\.\d{1,2})"""),
            Regex("""(?:消费|支出|支付|付款|扣款|扣费|花费|花了|转账|汇出|转出|收入|入账|到账|收款|退款|还款|实付)[^\d¥￥]{0,6}[¥￥]?\s*(\d+(?:\.\d{1,2})?)""")
        )

        /** 锚定金额模式：货币符号/「元」紧跟数字，优先级最高（预编译，避免每条通知重复编译正则） */
        private val ANCHORED_AMOUNT_REGEXES: List<Regex> = listOf(
            Regex("""[¥￥]\s*([\d,]+\.\d{1,2})"""),           // ¥1,234.56 或 ￥1234.56
            Regex("""[¥￥]\s*(\d+(?:\.\d{1,2})?)"""),         // ¥5 或 ¥5.00
            Regex("""人民币\s*([\d,]+\.\d{1,2})"""),           // 人民币1,234.56
            Regex("""人民币\s*(\d+(?:\.\d{1,2})?)"""),         // 人民币5
            Regex("""金额[：:]\s*([\d,]+\.\d{1,2})"""),        // 金额：1234.56
            Regex("""金额[：:]\s*(\d+(?:\.\d{1,2})?)"""),      // 金额：5
            Regex("""(?:订单金额|实付金额|交易金额)\s*([\d,]+\.\d{1,2})"""), // 订单金额 1,234.56（微信/支付宝长通知）
            Regex("""(?:订单金额|实付金额|交易金额)\s*(\d+(?:\.\d{1,2})?)"""), // 订单金额 5
            Regex("""([\d,]+\.\d{1,2})\s*元"""),               // 1234.56元
            Regex("""(\d+(?:\.\d{1,2})?)\s*元""")              // 5元
        )

        /** 松散金额模式：仅靠上下文词定位，可能截断「88块5」式小数，故放在口语模式之后兜底 */
        private val LOOSE_AMOUNT_REGEXES: List<Regex> = listOf(
            Regex("""(?:花了|花费)\s*(\d+(?:\.\d{1,2})?)"""),  // 花了50 / 花费50
            // 微信/支付宝消费可能有 "付款 ¥XX"（[^¥￥\d] 不吞数字，防「支付时间 2026-09」把 026 当金额）
            Regex("""(?:付款|支付|消费)[^¥￥\d]{0,5}[¥￥]?\s*(\d+(?:\.\d{1,2})?)""")
        )

        /**
         * 口语「块」模式：88块5 / 88块5毛 / 45块8毛2 / 45块8角2分 / 五十八块五 / 六十块 / 88.5块。
         * 毛角分小数必须紧跟「块(钱)」之后（不容空格），防止「50块 3瓶水」误截成 50.3
         */
        private val COLLOQUIAL_KUAI_REGEX =
            Regex("""((?:\d+(?:\.\d{1,2})?)|(?:[零一二两三四五六七八九十百千万]+(?:点[零一二两三四五六七八九十]+)?))\s*块(?:钱)?(?:([0-9零一二两三四五六七八九])(?:[毛角]([0-9零一二两三四五六七八九])分?)?)?""")

        /** 中文数字 + 元：三十五元 / 六十元（块系列由 COLLOQUIAL_KUAI_REGEX 覆盖） */
        private val CN_YUAN_REGEX =
            Regex("""([零一二两三四五六七八九十百千万]+(?:点[零一二两三四五六七八九十]+)?)\s*元""")

        /** 通用类型判断正则（预编译） */
        private val COMMON_INCOME_REGEX = Regex("""收入|入账|汇款转入|转入|到账|退款|获得""")
        private val COMMON_TRANSFER_REGEX = Regex("""转账|互联汇出|互联汇入""")
        private val COMMON_EXPENSE_REGEX = Regex("""支出|消费|扣款|支付|汇出|转出|付款|扣费|还款|花费|花了""")

        /**
         * 商户提取：银行短信/通知通常是「【XX银行】」署名，或「商户：XXX」。
         *
         * ⚠️ 之前没给任何规则配 patternMerchant，商户名恒为 ""，而
         * TransactionDao.findDuplicate 是按 (金额 + 类型 + **商户** + 时间窗) 判重的，
         * 结果就是 3 分钟内同金额的两笔真实消费，第二笔被当成重复静默丢弃。
         */
        private const val BANK_MERCHANT_PATTERN =
            """【(.+?)】|商户[：:]\s*(\S+)|(?:收款方|付款方|对方|交易对方)[：: ]\s*(\S+)"""

        /** 钱包类（微信/支付宝）：优先取收款方/商户名，其次【】署名 */
        private const val WALLET_MERCHANT_PATTERN =
            """(?:收款方|付款方|商户|商家|对方)[：: ]\s*(\S+)|【(.+?)】"""

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
                patternMerchant = BANK_MERCHANT_PATTERN,
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
                patternMerchant = BANK_MERCHANT_PATTERN,
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
                patternMerchant = BANK_MERCHANT_PATTERN,
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
                patternMerchant = WALLET_MERCHANT_PATTERN,
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
                patternMerchant = WALLET_MERCHANT_PATTERN,
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
                patternMerchant = BANK_MERCHANT_PATTERN,
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
                patternMerchant = BANK_MERCHANT_PATTERN,
                priority = 10
            )
        )
    }
}
