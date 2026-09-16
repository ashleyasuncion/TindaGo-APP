package com.example.tindago.data.ml

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.tindago.ui.localization.t
import com.example.tindago.ui.theme.*

@Composable
fun ForecastDetailCard(
    result: ForecastResult,
    lang: String,
    onRestock: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("\uD83D\uDD2E " + "forecastDetailTitle".t(lang), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Gray800)
                val chipText = when (result.confidence) {
                    ForecastConfidence.HIGH -> "forecastConfidenceHigh".t(lang)
                    ForecastConfidence.MEDIUM -> "forecastConfidenceMedium".t(lang)
                    ForecastConfidence.LOW -> "forecastConfidenceLow".t(lang)
                    ForecastConfidence.INSUFFICIENT -> "forecastInsufficientData".t(lang)
                }
                val chipBg = when (result.confidence) {
                    ForecastConfidence.HIGH -> Green100
                    ForecastConfidence.MEDIUM -> Amber100
                    ForecastConfidence.LOW -> Red100
                    ForecastConfidence.INSUFFICIENT -> Gray100
                }
                val chipFg = when (result.confidence) {
                    ForecastConfidence.HIGH -> Green700
                    ForecastConfidence.MEDIUM -> Amber700
                    ForecastConfidence.LOW -> Red600
                    ForecastConfidence.INSUFFICIENT -> Gray600
                }
                Surface(shape = RoundedCornerShape(8.dp), color = chipBg) {
                    Text(chipText, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = chipFg)
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text("forecastMethod".t(lang), style = MaterialTheme.typography.bodySmall, color = Gray500)
            Spacer(modifier = Modifier.height(12.dp))
            Text("forecastHistory".t(lang), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = Gray700)
            Spacer(modifier = Modifier.height(6.dp))
            ForecastMiniBars(history = result.dailyHistory, dates = result.dailyDates)
            Spacer(modifier = Modifier.height(10.dp))
            ForecastStatsRowSmall(result = result, lang = lang)
            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(10.dp))
            ForecastPredictionBoxSmall(result = result, lang = lang)
            if (result.suggestedRestockQty > 0 && onRestock != null) {
                Spacer(modifier = Modifier.height(10.dp))
                Button(onClick = onRestock, modifier = Modifier.fillMaxWidth()) {
                    Text("restock".t(lang))
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text("forecastHowItWorks".t(lang), style = MaterialTheme.typography.bodySmall, color = Gray500, fontSize = 11.sp)
        }
    }
}
