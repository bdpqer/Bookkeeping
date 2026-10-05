package com.bookkeeping.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.bookkeeping.app.data.entity.MerchantRule

@Dao
interface MerchantRuleDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rules: List<MerchantRule>)

    @Query("SELECT * FROM merchant_rules WHERE isEnabled = 1 ORDER BY id ASC")
    suspend fun getEnabled(): List<MerchantRule>

    @Query("SELECT COUNT(*) FROM merchant_rules")
    suspend fun count(): Int
}
