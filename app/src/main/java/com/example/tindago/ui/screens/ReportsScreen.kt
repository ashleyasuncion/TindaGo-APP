package com.example.tindago.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.tooling.preview.Preview
import com.example.tindago.data.StockStatus
import com.example.tindago.data.ml.ForecastEngine
import com.example.tindago.data.formatTimeAgo
import com.example.tindago.ui.components.LocalScreenScrollState
import com.example.tindago.ui.components.TutorialIconButton
import com.example.tindago.ui.components.LocalTutorialHighlightState
import com.example.tindago.ui.components.LocalTutorialScrollStateHolder
import com.example.tindago.ui.components.tutorialHighlight
import com.example.tindago.ui.localization.LocalLanguage
import com.example.tindago.ui.localization.t
import com.example.tindago.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * REPORTS SCREEN — Shows period-based sales summary with bar chart, KPI grid
 * with period-over-period comparison, utang/receivables summary with aging,
 * and CSV export. Web v2.55 parity (summary line, vs badges, utang section).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportsScreen(
    viewModel: AppViewModel,
    onBack: () -> Unit,
    onTutorialClick: (() -> Unit)? = null
) {
    val langState = LocalLanguage.current
    val lang = langState.value
    val scrollState = rememberScrollState()
    val highlightState = LocalTutorialHighlightState.current
    val scrollStateHolder = LocalTutorialScrollStateHolder.current
    LaunchedEffect(scrollState) { scrollStateHolder.updateScrollState(scrollState) }
    val context = LocalContext.current

    val specificSales by viewModel.specificSales.collectAsState()
    val products by viewModel.products.collectAsState()
    val reportPeriod by viewModel.reportPeriod.collectAsState()

    var selectedPeriod by remember { mutableStateOf(reportPeriod) }

    // All report numbers come from the shared engine (dev-date aware)
    val stats = viewModel.computeReportStats(selectedPeriod)
    val totalSales = stats.sales.sumOf { it.amount }
    val totalProfit = stats.sales.sumOf { it.profit }
    val totalItems = stats.sales.sumOf { it.quantity }
    val cashSales = stats.sales.filter { it.customerName == null }.sumOf { it.amount }
    val utangSales = totalSales - cashSales

    // Best-selling products
    val productSales = stats.sales.groupBy { it.description }
        .mapValues { (_, sales) -> sales.sumOf { it.quantity } }
        .entries.sortedByDescending { it.value }.take(5)

    // Low stock items
    val urgentForecasts = remember(products, specificSales, viewModel.today) { ForecastEngine.urgentRestocks(products, specificSales, viewModel.today, thresholdDays = 14, limit = 5) }
    val lowItems = products.filter { it.status != StockStatus.PLENTY }

    // Weekly chart data (last 7 days) — anchored to the (possibly overridden) app date
    val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    val todayDate = try { fmt.parse(viewModel.today) ?: Date() } catch (e: Exception) { Date() }
    val last7Days = (6 downTo 0).map { daysAgo ->
        val d = Date(todayDate.time - daysAgo * 24L * 60 * 60 * 1000)
        val dateStr = fmt.format(d)
        val dayLabel = SimpleDateFormat("E", Locale.getDefault()).format(d)
        dayLabel to specificSales.filter { it.date == dateStr }.sumOf { it.amount }
    }

    val maxChartValue = last7Days.maxOfOrNull { it.second } ?: 1.0

    // CSV export (web Export CSV parity — writes the current period's report)
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri != null) {
            try {
                val csv = viewModel.exportReportCsv(selectedPeriod)
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(csv.toByteArray(Charsets.UTF_8))
                }
                Toast.makeText(context, "exportReportDone".t(lang), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "exportReportError".t(lang), Toast.LENGTH_SHORT).show()
            }
        }
    }

    Scaffold(
        topBar = {
            // V2.68: subpage rule — Back button present → centered title.
            CenterAlignedTopAppBar(
                title = { Text("reportsTitle".t(lang), style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { exportLauncher.launch("tindago-report-$selectedPeriod.csv") }) {
                        Icon(
                            Icons.Default.Share,
                            contentDescription = "exportReport".t(lang),
                            tint = Color.White
                        )
                    }
                    if (onTutorialClick != null) TutorialIconButton(onClick = onTutorialClick)
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Green600,
                    titleContentColor = Color.White,
                    // Must be set explicitly — otherwise the back arrow inherits
                    // LocalContentColor (dark) instead of white, unlike every other
                    // screen's header (Settings/Inventory/Debts/Help all set it).
                    navigationIconContentColor = Color.White
                )
            )
        }
    ) { padding ->
    CompositionLocalProvider(LocalScreenScrollState provides scrollState) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(scrollState)
        ) {
            // Period toggles
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .tutorialHighlight("reportPeriodToggle", highlightState),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("day" to "day".t(lang), "week" to "week".t(lang), "month" to "month".t(lang)).forEach { (value, label) ->
                    FilterChip(
                        selected = selectedPeriod == value,
                        onClick = {
                            selectedPeriod = value
                            viewModel.setReportPeriod(value)
                        },
                        label = { Text(label, fontWeight = if (selectedPeriod == value) FontWeight.Bold else FontWeight.Normal) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Auto plain-language summary line (zero interaction)
            val periodLabel = when (selectedPeriod) {
                "week" -> "week".t(lang)
                "month" -> "month".t(lang)
                else -> "day".t(lang)
            }
            val summaryText = fmt("reportSummaryLine", lang, mapOf(
                "period" to periodLabel,
                "sales" to peso(totalSales),
                "profit" to peso(totalProfit),
                "vs" to vsBadge(totalSales, stats.prevSalesTotal, lang),
                "owed" to if (stats.outstandingUtang > 0) {
                    fmt("reportOwed", lang, mapOf("owed" to peso(stats.outstandingUtang)))
                } else ""
            ))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Green50),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Text(
                    summaryText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Green800,
                    lineHeight = 20.sp,
                    modifier = Modifier.padding(12.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // KPI grid (2 rows × 3 tiles) — Sales & Profit carry vs-previous badges
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .tutorialHighlight("reportSummaryCards", highlightState),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                KpiTile(
                    title = "totalSales".t(lang),
                    value = peso(totalSales),
                    color = Green600,
                    bgColor = Green50,
                    badge = vsBadge(totalSales, stats.prevSalesTotal, lang),
                    modifier = Modifier.weight(1f)
                )
                KpiTile(
                    title = "reportsProfit".t(lang),
                    value = peso(totalProfit),
                    color = Blue600,
                    bgColor = Blue50,
                    badge = vsBadge(totalProfit, stats.prevProfitTotal, lang),
                    modifier = Modifier.weight(1f)
                )
                KpiTile(
                    title = "itemsSold".t(lang),
                    value = totalItems.toString(),
                    color = Amber700,
                    bgColor = Amber50,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                KpiTile(
                    title = "transactions".t(lang),
                    value = stats.sales.size.toString(),
                    color = Gray700,
                    bgColor = Gray100,
                    modifier = Modifier.weight(1f)
                )
                KpiTile(
                    title = "cashSales".t(lang),
                    value = peso(cashSales),
                    color = Green700,
                    bgColor = Green100,
                    modifier = Modifier.weight(1f)
                )
                KpiTile(
                    title = "utangSales".t(lang),
                    value = peso(utangSales),
                    color = Red600,
                    bgColor = Red50,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            // V2.71: Expenses + Net Profit tiles (Net Profit may be negative on bad days)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                KpiTile(
                    title = "reportsExpenses".t(lang),
                    value = peso(stats.expenses),
                    color = Red600,
                    bgColor = Red50,
                    modifier = Modifier.weight(1f)
                )
                KpiTile(
                    title = "reportsNetProfit".t(lang),
                    value = peso(stats.netProfit),
                    color = if (stats.netProfit < 0) Red600 else Blue600,
                    bgColor = if (stats.netProfit < 0) Red50 else Blue50,
                    badge = vsBadge(stats.netProfit, stats.prevNetProfit, lang),
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Sales bar chart (last 7 days)
            if (selectedPeriod == "week" || selectedPeriod == "day") {
                Text(
                    "weeklyTrend".t(lang),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = Gray800,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        // Bar chart using Canvas
                        Canvas(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp)
                        ) {
                            val barWidth = size.width / (last7Days.size * 2 + 1)
                            val chartHeight = size.height - 30f

                            last7Days.forEachIndexed { index, (_, value) ->
                                val barHeight = ((value / maxChartValue) * chartHeight).toFloat().coerceAtLeast(4f)
                                val x = barWidth * (index * 2 + 1)
                                val y = size.height - 20f - barHeight

                                drawRect(
                                    color = if (value > 0) Green600 else Gray200,
                                    topLeft = Offset(x, y),
                                    size = Size(barWidth, barHeight),
                                    alpha = 0.8f
                                )
                            }

                            // Baseline
                            drawLine(
                                color = Color(0xFFCBD5E1),
                                start = Offset(0f, size.height - 20f),
                                end = Offset(size.width, size.height - 20f),
                                strokeWidth = 1f
                            )
                        }

                        // Day labels
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            last7Days.forEach { (label, _) ->
                                Text(
                                    label.take(3),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Gray500,
                                    textAlign = TextAlign.Center,
                                    fontSize = 10.sp
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }

            // Utang / receivables (collapsible) — outstanding, debtors, collected, aging
            var showUtang by remember { mutableStateOf(true) }
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "utangReport".t(lang),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = Gray800
                        )
                        TextButton(onClick = { showUtang = !showUtang }) {
                            Text(if (showUtang) "▲" else "▼", color = Gray500)
                        }
                    }
                    if (showUtang) {
                        ReportRow(
                            "outstandingUtang".t(lang),
                            peso(stats.outstandingUtang),
                            if (stats.outstandingUtang > 0) Red600 else Green600
                        )
                        ReportRow("activeDebtors".t(lang), stats.activeDebtors.toString(), Gray800)
                        ReportRow("collected".t(lang), peso(stats.collectedThisPeriod), Green600)
                        // Over-limit debtors (web v2.56/v2.57 parity) — shown only when > 0
                        val overLimitCount = viewModel.getOverLimitDebtorCount()
                        if (overLimitCount > 0) {
                            ReportRow("overLimitDebtors".t(lang), overLimitCount.toString(), Red600)
                        }
                        Text(
                            "debtAging".t(lang),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = Gray400,
                            modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
                        )
                        ReportRow(
                            "debtAge30".t(lang),
                            bucketText(stats.aging[0], lang),
                            if (stats.aging[0].amount > 0) Green600 else Gray400
                        )
                        ReportRow(
                            "debtAge60".t(lang),
                            bucketText(stats.aging[1], lang),
                            if (stats.aging[1].amount > 0) Amber700 else Gray400
                        )
                        ReportRow(
                            "debtAge60Plus".t(lang),
                            bucketText(stats.aging[2], lang),
                            if (stats.aging[2].amount > 0) Red600 else Gray400
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Best-selling products
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .tutorialHighlight("reportBestSellers", highlightState),
                shape = RoundedCornerShape(12.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "bestSelling".t(lang),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Gray800,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    if (productSales.isEmpty()) {
                        Text(
                            "noData".t(lang),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Gray500
                        )
                    } else {
                        productSales.forEachIndexed { index, (name, qty) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row {
                                    Text(
                                        "#${index + 1}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = Amber700
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(name, style = MaterialTheme.typography.bodyMedium, color = Gray800)
                                }
                                Text(
                                    "x$qty",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Green600
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Recent transactions (collapsible)
            var showTransactions by remember { mutableStateOf(true) }
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .tutorialHighlight("reportRecentTx", highlightState),
                shape = RoundedCornerShape(12.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "recentTransactions".t(lang),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = Gray800
                        )
                        TextButton(onClick = { showTransactions = !showTransactions }) {
                            Text(if (showTransactions) "▲" else "▼", color = Gray500)
                        }
                    }
                    if (showTransactions) {
                        if (stats.sales.isEmpty()) {
                            Text(
                                "noTransactions".t(lang),
                                style = MaterialTheme.typography.bodyMedium,
                                color = Gray500
                            )
                        } else {
                            stats.sales.sortedByDescending { it.timestamp }.take(15).forEach { sale ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 3.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(sale.description, style = MaterialTheme.typography.bodySmall, color = Gray800)
                                        Text(
                                            formatTimeAgo(sale.timestamp, lang),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Gray400
                                        )
                                    }
                                    Text(
                                        "₱${String.format("%,.2f", sale.amount)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Gray800
                                    )
                                }
                                HorizontalDivider(color = Gray100)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // -- Forecast urgent (14d) --
            if (urgentForecasts.isNotEmpty()) {
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = Surface)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("\uD83D\uDD2E Restock Soon (14d Forecast)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Gray800)
                        Spacer(modifier = Modifier.height(8.dp))
                        urgentForecasts.forEach { (product, result) ->
                            val days = result.predictedDaysUntilOut ?: 99
                            val dotColor = when { days <= 3 -> Red600; days <= 7 -> Amber600; else -> Green600 }
                            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Canvas(modifier = Modifier.size(10.dp)) { drawCircle(color = dotColor) }
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(product.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = Gray800)
                                Text("${days}d", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = dotColor)
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }
            // Low stock items
            var showLowStock by remember { mutableStateOf(true) }
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .tutorialHighlight("reportLowStock", highlightState),
                shape = RoundedCornerShape(12.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "lowStockItems".t(lang),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = Gray800
                        )
                        TextButton(onClick = { showLowStock = !showLowStock }) {
                            Text(if (showLowStock) "▲" else "▼", color = Gray500)
                        }
                    }
                    if (showLowStock) {
                        if (lowItems.isEmpty()) {
                            Text(
                                "noLowStockItems".t(lang),
                                style = MaterialTheme.typography.bodyMedium,
                                color = Gray500
                            )
                        } else {
                            lowItems.take(8).forEach { p ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(p.name, style = MaterialTheme.typography.bodySmall, color = Gray800)
                                    Text(
                                        if (p.quantity <= 0) (if (lang == "fil") "\u2014 Walang stock" else "\u2014 No stock")
                                        else (if (lang == "fil") "\u2014 ${p.quantity} na lang" else "\u2014 ${p.quantity} left"),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (p.quantity <= 0) Red500 else Amber700,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
    }
}

@Preview(showBackground = true, name = "Reports Screen")
@Composable
fun ReportsScreenPreview() {
    com.example.tindago.ui.theme.TindaGoTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            ReportsScreen(
                viewModel = remember { AppViewModel() },
                onBack = {}
            )
        }
    }
}

@Composable
private fun KpiTile(
    title: String,
    value: String,
    color: Color,
    bgColor: Color,
    modifier: Modifier = Modifier,
    badge: String = ""
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                title,
                style = MaterialTheme.typography.bodySmall,
                color = Gray500,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 15.sp
            )
            if (badge.isNotEmpty()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    badge,
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 10.sp,
                    color = color
                )
            }
        }
    }
}

@Composable
private fun ReportRow(label: String, value: String, color: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Gray800)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = color
        )
    }
}

// ── Helpers (web v2.55 parity) ────────────────────────────────────────

/** Translate a key and substitute {placeholders} (mirrors web t(key, reps)). */
private fun fmt(key: String, lang: String, reps: Map<String, String>): String {
    var s = key.t(lang)
    reps.forEach { (k, v) -> s = s.replace("{$k}", v) }
    return s
}

/** "▲ +{pct}% vs previous" / "▼ {pct}% vs previous" — empty when no previous data. */
private fun vsBadge(cur: Double, prev: Double, lang: String): String {
    if (prev <= 0) return ""
    val p = Math.round(((cur - prev) / prev) * 100)
    return if (p >= 0) {
        fmt("reportVsUp", lang, mapOf("pct" to p.toString()))
    } else {
        fmt("reportVsDown", lang, mapOf("pct" to Math.abs(p).toString()))
    }
}

private fun peso(v: Double): String = "₱${String.format("%,.2f", v)}"

/** Aging bucket display: "₱amount (count)" or localized no-data text. */
private fun bucketText(bucket: AgingBucket, lang: String): String =
    if (bucket.amount > 0) "${peso(bucket.amount)} (${bucket.count})" else "noData".t(lang)
