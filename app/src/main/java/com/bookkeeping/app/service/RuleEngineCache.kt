package com.bookkeeping.app.service

import android.content.Context
import android.util.Log
import com.bookkeeping.app.BookkeepingApp
import com.bookkeeping.app.data.AppDatabase
import com.bookkeeping.app.parser.ParseEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 解析引擎规则缓存：短信与通知两条自动记账路径共用。
 *
 * 两条路径原本各自从 DB 读一遍 ParseRule + MerchantRule 构造 [ParseEngine]：
 *  - 通知侧有缓存，用户改规则后热刷新
 *  - 短信侧每条短信都同步跑 2 条查询（且不带缓存）
 *
 * 统一到这一个单例：默认用内置规则兜底，首次需要时从 DB 加载；
 * 用户在设置页改完规则调 [refresh] 即可让两条路径同时生效。
 */
object RuleEngineCache {

    /** 无 DB 时的兜底引擎（纯内置规则） */
    private val fallback = ParseEngine()

    @Volatile
    private var cached: ParseEngine? = null

    /** 防止并发 refresh 时后写覆盖先写，中间态用旧规则解析 */
    private val mutex = Mutex()

    /** 取当前引擎；未加载过时同步加载一次。失败降级为内置规则，绝不抛异常打断自动记账 */
    suspend fun get(context: Context): ParseEngine {
        cached?.let { return it }
        return load(context) ?: fallback
    }

    /** 用户修改规则后调用：重新从 DB 加载并替换缓存 */
    suspend fun refresh(context: Context): ParseEngine = load(context) ?: cached ?: fallback

    private suspend fun load(context: Context): ParseEngine? = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val db = AppDatabase.getInstance(context.applicationContext)
                val rules = db.parseRuleDao().getEnabled()
                val merchantRules = db.merchantRuleDao().getEnabled()
                ParseEngine(rules, merchantRules).also {
                    cached = it
                    Log.d(
                        BookkeepingApp.TAG,
                        "🔄 解析规则已加载 ParseRule=${rules.size} MerchantRule=${merchantRules.size}"
                    )
                }
            } catch (e: Exception) {
                Log.w(BookkeepingApp.TAG, "规则加载失败，降级为内置规则", e)
                null
            }
        }
    }
}
