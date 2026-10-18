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

    @Query("DELETE FROM installment_plans WHERE id = :id")
    suspend fun delete(id: Long)

    /** 账本级联删除。ledgerId 可空，所以要同时覆盖 NULL（无账本的分期不归任何账本，保留） */
    @Query("DELETE FROM installment_plans WHERE ledgerId = :ledgerId")
    suspend fun deleteByLedger(ledgerId: Long)
}
