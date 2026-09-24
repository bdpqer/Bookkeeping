package com.bookkeeping.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.bookkeeping.app.data.entity.RecurringItem

@Dao
interface RecurringDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: RecurringItem): Long

    @Update
    suspend fun update(item: RecurringItem)

    @Query("DELETE FROM recurring_items WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM recurring_items WHERE isEnabled = 1 ORDER BY nextRunAt ASC")
    suspend fun getAllEnabled(): List<RecurringItem>

    @Query("SELECT * FROM recurring_items ORDER BY nextRunAt ASC")
    suspend fun getAll(): List<RecurringItem>

    @Query("SELECT * FROM recurring_items WHERE isEnabled = 1 AND nextRunAt <= :now")
    suspend fun getDue(now: Long): List<RecurringItem>

    @Query("SELECT * FROM recurring_items WHERE id = :id")
    suspend fun getById(id: Long): RecurringItem?
}
