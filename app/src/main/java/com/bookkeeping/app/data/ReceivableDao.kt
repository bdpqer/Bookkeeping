package com.bookkeeping.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.bookkeeping.app.data.entity.Receivable

@Dao
interface ReceivableDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(r: Receivable): Long

    @Update
    suspend fun update(r: Receivable)

    @Query("DELETE FROM receivables WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM receivables ORDER BY dueDate ASC")
    suspend fun getAll(): List<Receivable>

    @Query("SELECT * FROM receivables WHERE direction = :direction ORDER BY dueDate ASC")
    suspend fun getByDirection(direction: String): List<Receivable>

    @Query("SELECT * FROM receivables WHERE status = :status ORDER BY dueDate ASC")
    suspend fun getByStatus(status: String): List<Receivable>

    @Query("SELECT * FROM receivables WHERE direction = :direction AND status = :status ORDER BY dueDate ASC")
    suspend fun getByDirectionAndStatus(direction: String, status: String): List<Receivable>

    @Query("SELECT * FROM receivables WHERE id = :id")
    suspend fun getById(id: Long): Receivable?

    @Query("UPDATE receivables SET status = 'DONE' WHERE id = :id")
    suspend fun markDone(id: Long)

    @Query("SELECT COUNT(*) FROM receivables WHERE direction = :direction AND status = 'PENDING'")
    suspend fun countPending(direction: String): Int
}
