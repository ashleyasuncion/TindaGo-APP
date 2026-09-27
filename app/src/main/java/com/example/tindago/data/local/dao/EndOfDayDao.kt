package com.example.tindago.data.local.dao

import androidx.room.*
import com.example.tindago.data.local.entity.EndOfDayEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface EndOfDayDao {
    @Query("SELECT * FROM end_of_day_data ORDER BY date DESC LIMIT 1")
    fun getLatest(): Flow<EndOfDayEntity?>

    @Query("SELECT * FROM end_of_day_data WHERE date = :date")
    suspend fun getByDate(date: String): EndOfDayEntity?

    @Query("SELECT * FROM end_of_day_data ORDER BY date DESC")
    suspend fun getAllEntries(): List<EndOfDayEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(data: EndOfDayEntity)

    @Query("DELETE FROM end_of_day_data WHERE date = :date")
    suspend fun deleteByDate(date: String)

    @Query("DELETE FROM end_of_day_data")
    suspend fun deleteAll()
}
