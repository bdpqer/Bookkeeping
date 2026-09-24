package com.bookkeeping.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.bookkeeping.app.data.entity.Ledger

@Dao
interface LedgerDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(ledger: Ledger): Long

    @Update
    suspend fun update(ledger: Ledger)

    @Query("DELETE FROM ledgers WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM ledgers ORDER BY isDefault DESC, id ASC")
    suspend fun getAll(): List<Ledger>

    @Query("SELECT * FROM ledgers WHERE id = :id")
    suspend fun getById(id: Long): Ledger?

    @Query("SELECT * FROM ledgers WHERE isDefault = 1 LIMIT 1")
    suspend fun getDefault(): Ledger?

    @Query("UPDATE ledgers SET isDefault = 0")
    suspend fun clearAllDefault()

    @Query("UPDATE ledgers SET isDefault = 1 WHERE id = :id")
    suspend fun setDefault(id: Long)
}
