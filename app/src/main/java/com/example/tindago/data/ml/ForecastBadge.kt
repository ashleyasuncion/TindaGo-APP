package com.example.tindago.data.ml

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.tindago.ui.localization.t
import com.example.tindago.ui.theme.*

@Composable
fun ForecastBadge(
    result: ForecastResult,
    lang: String,
    compact: Boolean = false,
    modifier: Modifier = Modifier
) {
    if (result.confidence == ForecastConfidence.INSUFFICIENT && compact) return
    val days = result.predictedDaysUntilOut
    val text: String
    val bg: Color
    val fg: Color
    when {
        result.confidence == ForecastConfidence.INSUFFICIENT -> {
            text = "\uD83D\uDD2E " + "forecastInsufficientData".t(lang)
            bg = Gray100; fg = Gray600
            if (compact) return
        }
        result.avgDaily < ForecastEngine.MIN_AVG_THRESHOLD -> {
            text = "\uD83D\uDD2E " + "forecastNoDemand".t(lang)
            bg = Gray100; fg = Gray600
        }
        result.currentStock <= 0 -> {
            text = "\uD83D\uDD2E " + "forecastOutOfStock".t(lang)
            bg = Red100; fg = Red600
        }
        days == null -> {
            text = "\uD83D\uDD2E " + "forecastNoDemand".t(lang)
            bg = Gray100; fg = Gray600
        }
        days == 0 -> {
            text = "\uD83D\uDD2E " + "forecastOutToday".t(lang)
            bg = Red100; fg = Red600
        }
        else -> {
            val daysText = if (compact) {
                "forecastDaysLeftShort".t(lang).replace("{n}", days.toString())
            } else {
                "forecastDaysLeft".t(lang).replace("{n}", days.toString())
            }
            val avgText = String.format(java.util.Locale.US, "%.1f", result.avgDaily)
            text = if (compact) "\uD83D\uDD2E $daysText \u2022 Avg $avgText/day"
            else "\uD83D\uDD2E $daysText \u2022 Avg $avgText/day"
            bg = when {
                days <= 3 -> Red100
                days <= 7 -> Amber100
                else -> Green100
            }
            fg = when {
                days <= 3 -> Red600
                days <= 7 -> Amber700
                else -> Green700
            }
        }
    }
    androidx.compose.material3.Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = bg
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            fontSize = if (compact) 11.sp else 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = fg
        )
    }
}
