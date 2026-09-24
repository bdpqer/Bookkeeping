package com.bookkeeping.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.bookkeeping.app.data.entity.MerchantRule

@Dao
interface MerchantRuleDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(rule: MerchantRule): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rules: List<MerchantRule>)

    @Update
    suspend fun update(rule: MerchantRule)

    @Query("DELETE FROM merchant_rules WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM merchant_rules")
    suspend fun clear()

    @Query("SELECT * FROM merchant_rules WHERE isEnabled = 1 ORDER BY id ASC")
    suspend fun getEnabled(): List<MerchantRule>

    @Query("SELECT * FROM merchant_rules ORDER BY id ASC")
    suspend fun getAll(): List<MerchantRule>

    @Query("SELECT COUNT(*) FROM merchant_rules")
    suspend fun count(): Int
}
