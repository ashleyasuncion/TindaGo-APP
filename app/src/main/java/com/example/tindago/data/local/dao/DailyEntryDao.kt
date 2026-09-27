package com.example.tindago.data.local.dao

import androidx.room.*
import com.example.tindago.data.local.entity.DailyEntryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DailyEntryDao {
    @Query("SELECT * FROM daily_entries ORDER BY date DESC LIMIT 1")
    fun getLatestEntry(): Flow<DailyEntryEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEntry(entry: DailyEntryEntity)

    @Query("SELECT * FROM daily_entries WHERE date = :date")
    suspend fun getByDate(date: String): DailyEntryEntity?

    @Query("SELECT * FROM daily_entries ORDER BY date DESC")
    suspend fun getAllEntries(): List<DailyEntryEntity>

    @Query("DELETE FROM daily_entries")
    suspend fun deleteAll()
}
