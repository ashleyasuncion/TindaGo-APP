package com.example.tindago.data.local.dao

import androidx.room.*
import com.example.tindago.data.local.entity.SmsLogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SmsLogDao {
    @Query("SELECT * FROM sms_log ORDER BY timestamp DESC")
    fun getAllLogs(): Flow<List<SmsLogEntity>>

    @Query("SELECT * FROM sms_log WHERE debtId = :debtId ORDER BY timestamp DESC")
    fun getLogsByDebtId(debtId: Int): Flow<List<SmsLogEntity>>

    @Query("SELECT * FROM sms_log WHERE debtId = :debtId AND type = :type ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestLogByDebtAndType(debtId: Int, type: String): SmsLogEntity?

    @Query("SELECT * FROM sms_log WHERE debtId = :debtId AND type = :type AND timestamp > :since")
    suspend fun getLogsSince(debtId: Int, type: String, since: Long): List<SmsLogEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: SmsLogEntity)

    @Query("DELETE FROM sms_log WHERE debtId = :debtId")
    suspend fun deleteLogsByDebtId(debtId: Int)

    @Query("DELETE FROM sms_log")
    suspend fun deleteAll()
}
