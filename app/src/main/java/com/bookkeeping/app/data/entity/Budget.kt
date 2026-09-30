package com.bookkeeping.app.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 按账本预算（v11）。
 * 每个账本一条记录；无记录 = 该账本不设预算（不提醒）。
 * notifiedMonth：该账本本月是否已提醒（yyyy-MM），替代原全局 SharedPreferences 节流。
 */
@Entity(tableName = "budgets", indices = [
    Index(value = ["ledgerId"], unique = true)
])
data class Budget(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** 所属账本 id（1:1，unique） */
    val ledgerId: Long,

    /** 该账本每月预算金额（<=0 视为未设置，不存行） */
    val monthlyAmount: Double,

    /** 该账本已提醒的月份 "yyyy-MM"，null = 本月未提醒 */
    val notifiedMonth: String? = null,

    val updatedAt: Long = System.currentTimeMillis()
)
