package com.example.tindago.data.ml

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.tindago.ui.localization.t
import com.example.tindago.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
fun ForecastMiniBars(
    history: List<Int>,
    dates: List<String>,
    modifier: Modifier = Modifier
) {
    val max = (history.maxOrNull() ?: 0).coerceAtLeast(1)
    Row(
        modifier = modifier.fillMaxWidth().height(72.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        history.forEachIndexed { idx, v ->
            val hFraction = if (max == 0) 0.05f else (v.toFloat() / max.toFloat()).coerceAtLeast(0.05f)
            val barColor = when {
                v == 0 -> Gray300
                v <= max * 0.33 -> Color(0xFF60A5FA)
                v <= max * 0.66 -> Color(0xFFF59E0B)
                else -> Green600
            }
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Canvas(modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    val barH = size.height * hFraction
                    drawRoundRect(
                        color = barColor,
                        topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - barH),
                        size = androidx.compose.ui.geometry.Size(size.width, barH)
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                val label = try {
                    val inFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                    val outFmt = SimpleDateFormat("MM/dd", Locale.getDefault())
                    outFmt.format(inFmt.parse(dates[idx]) ?: java.util.Date())
                } catch (_: Exception) { dates[idx].takeLast(5) }
                Text(label, fontSize = 9.sp, color = Gray500)
            }
        }
    }
}

@Composable
fun ForecastStatsRowSmall(result: ForecastResult, lang: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Avg ${String.format(Locale.US, "%.1f", result.avgDaily)}/day", style = MaterialTheme.typography.bodySmall, color = Gray700)
        Text("EMA ${String.format(Locale.US, "%.1f", result.emaDaily)}", style = MaterialTheme.typography.bodySmall, color = Gray700)
        Text("Trend ${String.format(Locale.US, "%.1f", result.trendDaily)}", style = MaterialTheme.typography.bodySmall, color = Gray700)
    }
}

@Composable
fun ForecastPredictionBoxSmall(result: ForecastResult, lang: String, modifier: Modifier = Modifier) {
    val days = result.predictedDaysUntilOut
    val text = when {
        result.confidence == ForecastConfidence.INSUFFICIENT -> "forecastInsufficientData".t(lang)
        result.avgDaily < ForecastEngine.MIN_AVG_THRESHOLD -> "forecastNoDemand".t(lang)
        result.currentStock <= 0 -> "forecastOutOfStock".t(lang)
        days == null -> "forecastNoDemand".t(lang)
        days == 0 -> "forecastOutToday".t(lang)
        else -> "forecastDaysLeft".t(lang).replace("{n}", days.toString())
    }
    Column(modifier = modifier) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Gray800)
        if (result.suggestedRestockQty > 0) {
            Text(
                "forecastSuggestedRestock".t(lang).replace("{n}", result.suggestedRestockQty.toString()) + " " + "forecastFor7Days".t(lang),
                style = MaterialTheme.typography.bodySmall, color = Gray600
            )
        }
        Text("forecastConfidence${result.confidence.name.lowercase().replaceFirstChar { it.uppercase() }}".t(lang), style = MaterialTheme.typography.bodySmall, color = Gray500)
    }
}
