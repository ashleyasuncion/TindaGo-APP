package com.example.tindago.data.local.dao

import androidx.room.*
import com.example.tindago.data.local.entity.SpecificSaleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SpecificSaleDao {
    @Query("SELECT * FROM specific_sales ORDER BY timestamp DESC")
    fun getAllSales(): Flow<List<SpecificSaleEntity>>

    @Query("SELECT * FROM specific_sales WHERE date = :date ORDER BY timestamp DESC")
    fun getSalesByDate(date: String): Flow<List<SpecificSaleEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSale(sale: SpecificSaleEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSales(sales: List<SpecificSaleEntity>)

    @Query("SELECT description FROM specific_sales GROUP BY description ORDER BY SUM(quantity) DESC LIMIT :limit")
    fun getTopSellingDescriptions(limit: Int = 8): Flow<List<String>>

    @Query("DELETE FROM specific_sales")
    suspend fun deleteAll()
}
