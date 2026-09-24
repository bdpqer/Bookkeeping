package com.bookkeeping.app.service

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 全局数据变更信号。
 * 任何页面/服务增删改交易后调用 notifyDataChanged()，
 * 订阅页（首页/明细页等）通过 LaunchedEffect(DataBus.dataTick) 立即刷新。
 */
object DataBus {
    var dataTick by mutableStateOf(0)
        private set

    fun notifyDataChanged() { dataTick++ }
}
