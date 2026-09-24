package com.bookkeeping.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.bookkeeping.app.data.entity.InstallmentPlan

@Dao
interface InstallmentDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(plan: InstallmentPlan): Long

    @Update
    suspend fun update(plan: InstallmentPlan)

    @Query("SELECT * FROM installment_plans ORDER BY createdAt DESC")
    suspend fun getAll(): List<InstallmentPlan>

    @Query("SELECT * FROM installment_plans WHERE status = 'ACTIVE' ORDER BY firstDate ASC")
    suspend fun getActive(): List<InstallmentPlan>

    @Query("SELECT * FROM installment_plans WHERE id = :id")
    suspend fun getById(id: Long): InstallmentPlan?

    @Query("DELETE FROM installment_plans WHERE id = :id")
    suspend fun delete(id: Long)
}
