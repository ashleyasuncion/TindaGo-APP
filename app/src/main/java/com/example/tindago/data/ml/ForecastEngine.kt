package com.example.tindago.data.ml

import com.example.tindago.data.Product
import com.example.tindago.data.SpecificSale
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/**
 * Level 1 — Offline Statistical Demand Forecast
 * No network / no TFLite / pure Kotlin — learns avgDailySales from last 7 days in Room.
 * Algorithm 12 (see 12_Level1_Offline_ML_Demand_Forecast.md).
 */
enum class ForecastConfidence { INSUFFICIENT, LOW, MEDIUM, HIGH }

data class ForecastResult(
    val productId: Int,
    val productName: String,
    val currentStock: Int,
    val avgDaily: Double,
    val emaDaily: Double,
    val trendDaily: Double,
    val predictedDaysUntilOut: Int?,
    val suggestedRestockQty: Int,
    val confidence: ForecastConfidence,
    val dailyHistory: List<Int>,
    val dailyDates: List<String>,
    val totalSold: Int,
    val activeDays: Int,
    val forecastMethod: String
)

object ForecastEngine {
    const val WINDOW_DAYS = 7
    const val LEAD_TIME_DAYS = 7
    const val EMA_ALPHA = 0.5
    const val MIN_AVG_THRESHOLD = 0.15

    fun buildDailyHistory(
        product: Product,
        allSales: List<SpecificSale>,
        todayStr: String,
        windowDays: Int = WINDOW_DAYS
    ): Pair<List<Int>, List<String>> {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val todayDate = try { fmt.parse(todayStr) ?: Date() } catch (_: Exception) { Date() }
        val cal = Calendar.getInstance()
        cal.time = todayDate
        cal.add(Calendar.DAY_OF_YEAR, -(windowDays - 1))
        val dates = mutableListOf<String>()
        repeat(windowDays) {
            dates.add(fmt.format(cal.time))
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        val history = dates.map { date ->
            allSales.filter { it.date == date && it.description.contains(product.name, ignoreCase = true) }
                .sumOf { it.quantity }
        }
        return history to dates
    }

    fun forecastForProduct(
        product: Product,
        allSales: List<SpecificSale>,
        todayStr: String,
        windowDays: Int = WINDOW_DAYS
    ): ForecastResult {
        val (history, dates) = buildDailyHistory(product, allSales, todayStr, windowDays)
        val totalSold = history.sum()
        val activeDays = history.count { it > 0 }
        val sma = if (history.isEmpty()) 0.0 else history.sum().toDouble() / windowDays
        val ema = ema(history, EMA_ALPHA)
        val trend = trendNextDay(history, sma)
        val avgDaily = sma
        val predictedDays: Int? = when {
            avgDaily < MIN_AVG_THRESHOLD -> null
            product.quantity <= 0 -> 0
            else -> ceil(product.quantity / avgDaily).toInt()
        }
        val suggested = if (avgDaily < MIN_AVG_THRESHOLD) 0
        else max(0, ceil(avgDaily * LEAD_TIME_DAYS - product.quantity).toInt())
        val confidence = when {
            totalSold == 0 -> ForecastConfidence.INSUFFICIENT
            activeDays <= 1 -> ForecastConfidence.LOW
            activeDays <= 3 -> ForecastConfidence.MEDIUM
            else -> ForecastConfidence.HIGH
        }
        return ForecastResult(
            productId = product.id,
            productName = product.name,
            currentStock = product.quantity,
            avgDaily = avgDaily,
            emaDaily = ema,
            trendDaily = trend,
            predictedDaysUntilOut = predictedDays,
            suggestedRestockQty = suggested,
            confidence = confidence,
            dailyHistory = history,
            dailyDates = dates,
            totalSold = totalSold,
            activeDays = activeDays,
            forecastMethod = "Statistical ML (7-day Moving Average)"
        )
    }

    fun forecastAll(
        products: List<Product>,
        allSales: List<SpecificSale>,
        todayStr: String
    ): Map<Int, ForecastResult> = products.associate { it.id to forecastForProduct(it, allSales, todayStr) }

    fun urgentRestocks(
        products: List<Product>,
        allSales: List<SpecificSale>,
        todayStr: String,
        thresholdDays: Int = 7,
        limit: Int = 5
    ): List<Pair<Product, ForecastResult>> {
        return products.map { p -> p to forecastForProduct(p, allSales, todayStr) }
            .filter { (_, r) -> r.confidence != ForecastConfidence.INSUFFICIENT && r.predictedDaysUntilOut != null && r.predictedDaysUntilOut <= thresholdDays }
            .sortedBy { it.second.predictedDaysUntilOut }
            .take(limit)
    }

    fun accuracy(predicted: Double, actual: Double): Double {
        if (actual == 0.0) return 0.0
        return (1.0 - abs(predicted - actual) / abs(actual)).coerceIn(0.0, 1.0)
    }

    private fun ema(history: List<Int>, alpha: Double): Double {
        if (history.isEmpty()) return 0.0
        var e = history[0].toDouble()
        for (i in 1 until history.size) e = alpha * history[i] + (1 - alpha) * e
        return e
    }

    private fun trendNextDay(history: List<Int>, fallback: Double): Double {
        if (history.size < 3) return fallback
        val n = history.size.toDouble()
        var sx = 0.0; var sy = 0.0; var sxy = 0.0; var sx2 = 0.0
        for (i in history.indices) {
            val x = i.toDouble(); val y = history[i].toDouble()
            sx += x; sy += y; sxy += x * y; sx2 += x * x
        }
        val denom = n * sx2 - sx * sx
        if (denom == 0.0) return fallback
        val slope = (n * sxy - sx * sy) / denom
        val intercept = (sy - slope * sx) / n
        return max(0.0, intercept + slope * n)
    }
}
