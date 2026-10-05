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

    @Query("SELECT * FROM recurring_items ORDER BY nextRunAt ASC")
    suspend fun getAll(): List<RecurringItem>

    @Query("SELECT * FROM recurring_items WHERE isEnabled = 1 AND nextRunAt <= :now")
    suspend fun getDue(now: Long): List<RecurringItem>

    /** 启用项中最近的一次运行时间；无启用项时返回 null（调度用，避免把全表拉进内存） */
    @Query("SELECT MIN(nextRunAt) FROM recurring_items WHERE isEnabled = 1")
    suspend fun nextEnabledRunAt(): Long?
}
