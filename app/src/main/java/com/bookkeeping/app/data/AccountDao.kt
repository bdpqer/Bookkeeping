package com.bookkeeping.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.bookkeeping.app.data.entity.Account

@Dao
interface AccountDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(account: Account): Long

    @Query("DELETE FROM accounts WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM accounts WHERE isEnabled = 1 ORDER BY id")
    suspend fun getAll(): List<Account>

    @Query("SELECT * FROM accounts ORDER BY id")
    suspend fun getAllIncludingDisabled(): List<Account>

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun getById(id: Long): Account?

    /** 更新账户余额（收入 +，支出 -） */
    @Query("UPDATE accounts SET balance = balance + :delta WHERE id = :id")
    suspend fun adjustBalance(id: Long, delta: Double)

    /** 直接写入余额（按交易重算时用：增量累加一旦漂移只能整体覆盖） */
    @Query("UPDATE accounts SET balance = :value WHERE id = :id")
    suspend fun setBalance(id: Long, value: Double)
}
