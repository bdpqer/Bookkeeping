package com.bookkeeping.app.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 商家/关键词 → 分类的自动映射规则。
 * 比如："星巴克" → "餐饮/饮品"、"滴滴" → "交通"
 */
@Entity(tableName = "merchant_rules", indices = [
    Index(value = ["keyword"]),
    Index(value = ["isEnabled"])
])
data class MerchantRule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** 关键词（忽略大小写，模糊匹配 merchant + rawText） */
    val keyword: String,

    /** 匹配到的分类 */
    val category: String,

    /** 备注/说明 */
    val note: String = "",

    /** 是否启用 */
    val isEnabled: Boolean = true
)
