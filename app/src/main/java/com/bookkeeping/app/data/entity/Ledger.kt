package com.bookkeeping.app.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 账本：日常账/工作账/旅行账/家庭账...
 * 不同账本独立统计。
 */
@Entity(tableName = "ledgers", indices = [
    Index(value = ["name"], unique = true)
])
data class Ledger(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** 账本名称 */
    val name: String,

    /** Emoji 图标 */
    val icon: String = "📒",

    /** 备注 */
    val note: String = "",

    /** 是否为默认账本 */
    val isDefault: Boolean = false,

    /** 创建时间 */
    val createdAt: Long = System.currentTimeMillis()
)
