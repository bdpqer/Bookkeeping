package com.bookkeeping.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.bookkeeping.app.data.entity.ParseRule

@Dao
interface ParseRuleDao {

    @Query("SELECT * FROM parse_rules WHERE isEnabled = 1 ORDER BY priority DESC")
    suspend fun getEnabled(): List<ParseRule>

    @Query("SELECT * FROM parse_rules ORDER BY priority DESC")
    suspend fun getAll(): List<ParseRule>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(rule: ParseRule): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(rules: List<ParseRule>)

    @Update
    suspend fun update(rule: ParseRule)

    @Query("SELECT COUNT(*) FROM parse_rules")
    suspend fun count(): Int

    @Query("DELETE FROM parse_rules")
    suspend fun clear()

    @Query("DELETE FROM parse_rules WHERE id = :id")
    suspend fun deleteById(id: Long)
}
