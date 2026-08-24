package com.example.tindago.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.tooling.preview.Preview
import com.example.tindago.data.formatTimeAgo
import com.example.tindago.ui.components.LocalScreenScrollState
import com.example.tindago.ui.components.LocalTutorialHighlightState
import com.example.tindago.ui.components.LocalTutorialScrollStateHolder
import com.example.tindago.ui.components.tutorialHighlight
import com.example.tindago.ui.localization.LocalLanguage
import com.example.tindago.ui.localization.t
import com.example.tindago.ui.theme.*
import com.example.tindago.ui.theme.TindaGoTheme
import java.text.SimpleDateFormat
import java.util.*

/**
 * DAY MODE — Second of three daily moments.
 * Matches day.html from the web prototype exactly.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayModeScreen(
    viewModel: AppViewModel,
    onCloseStore: () -> Unit,
    onNavigateToInventory: () -> Unit,
    onNavigateToExpenses: () -> Unit = {},
    onOpenSaleSheet: () -> Unit,
    onLaunchTutorial: () -> Unit
) {
    val langState = LocalLanguage.current
    val lang = langState.value
    val scrollState = rememberScrollState()
    val highlightState = LocalTutorialHighlightState.current

    val specificSales by viewModel.specificSales.collectAsState()
    val debts by viewModel.debts.collectAsState()
    val expenses by viewModel.expenses.collectAsState()
    // Observable current date — recomposes this screen on midnight rollover / resume
    // so the header date and today-based stats stay correct in real time.
    val currentDate by viewModel.currentDate.collectAsState()

    val today = viewModel.today
    val todaySales = specificSales.filter { it.date == today }
    // Cash Sales Today = actual recorded cash sales (web parity: the web's
    // getTodayEarnings() sums only cash sales, never a seeded/manual DailyEntry.
    // A stale DailyEntry.earnings (e.g. the old 1,250 sample seed) must not
    // override real sales or show money before any sale is recorded).
    val todayEarnings = viewModel.todayRecordedSales
    val todayUtangTotal = viewModel.specificSales.value.filter { it.date == today && it.customerName != null }.sumOf { it.amount }

    val dateFormat = remember { SimpleDateFormat("MMMM d, yyyy", Locale.getDefault()) }
    // Display the (possibly dev-overridden) app date so the header matches simulated day
    val todayFormatted = remember(currentDate, viewModel.today) {
        val parsed = try {
            SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(viewModel.today)
        } catch (e: Exception) { null }
        dateFormat.format(parsed ?: Date())
    }

    // Collapsible transactions state
    var transactionsExpanded by remember { mutableStateOf(true) }

    // Provide scroll state for tutorial auto-scroll
    val scrollStateHolder = LocalTutorialScrollStateHolder.current
    LaunchedEffect(scrollState) { scrollStateHolder.updateScrollState(scrollState) }
    CompositionLocalProvider(LocalScreenScrollState provides scrollState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        // ── Header ──
        Spacer(modifier = Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "dayModeTitle".t(lang),
                    style = MaterialTheme.typography.titleMedium,
                    color = Gray500,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    todayFormatted,
                    style = MaterialTheme.typography.bodySmall,
                    color = Gray400,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ── Stats grid ──
        // Fixed-height Row (150dp) gives every card equal height.
        // Box(contentAlignment = Center) inside each card reliably
        // centers content vertically — no more IntrinsicSize.Min issues.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp)
                .tutorialHighlight("dayStatsGrid", highlightState),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            DayStatCard(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                icon = "\uD83D\uDCB5",
                iconBg = Green100,
                label = "recordedSalesToday".t(lang),
                value = "\u20B1${String.format("%,.2f", todayEarnings)}"
            )
            DayStatCard(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                icon = "\uD83D\uDCE6",
                iconBg = Blue50,
                label = "itemsSold".t(lang),
                value = "${todaySales.sumOf { it.quantity }}"
            )
            DayStatCard(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                icon = "\uD83D\uDCB0",
                iconBg = Amber100,
                label = "debtToday".t(lang),
                value = "\u20B1${String.format("%,.2f", todayUtangTotal)}"
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // ── Collapsible Transaction Feed ──
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .tutorialHighlight("dayTxFeed", highlightState),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Column {
                // Header (clickable to toggle)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { transactionsExpanded = !transactionsExpanded }
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "dayTransactionsLabel".t(lang),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Gray700
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Icon(
                        if (transactionsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = Gray400,
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Transaction list (collapsible)
                AnimatedVisibility(
                    visible = transactionsExpanded,
                    enter = expandVertically(expandFrom = Alignment.Top),
                    exit = shrinkVertically(shrinkTowards = Alignment.Top)
                ) {
                    Column(modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 14.dp)) {
                        if (todaySales.isEmpty()) {
                            Text(
                                "noTransactions".t(lang),
                                style = MaterialTheme.typography.bodyMedium,
                                color = Gray400,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        } else {
                            todaySales.take(20).forEach { sale ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        modifier = Modifier.size(40.dp),
                                        shape = RoundedCornerShape(20.dp),
                                        color = if (sale.customerName != null) Amber100 else Green100
                                    ) {
                                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                            Text(
                                                if (sale.customerName != null) "\uD83D\uDCB0" else "\uD83D\uDCB5",
                                                fontSize = 16.sp
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(14.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            sale.description,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = Gray800
                                        )
                                        Row {
                                            if (sale.customerName != null) {
                                                Text(
                                                    sale.customerName,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = Amber700
                                                )
                                                Text(" \u2022 ", style = MaterialTheme.typography.bodySmall, color = Gray300)
                                            }
                                            Text(
                                                formatTimeAgo(sale.timestamp, lang),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = Gray400
                                            )
                                            if (sale.quantity > 1) {
                                                Text(" \u2022 x${sale.quantity}", style = MaterialTheme.typography.bodySmall, color = Gray400)
                                            }
                                        }
                                    }
                                    Text(
                                        "\u20B1${String.format("%,.2f", sale.amount)}",
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = Gray800
                                    )
                                }
                                if (todaySales.last() != sale) {
                                    HorizontalDivider(color = Gray100, modifier = Modifier.padding(vertical = 4.dp))
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(28.dp))

        // ── Store Expenses entry point (web V2.71 parity) ──
        OutlinedButton(
            onClick = onNavigateToExpenses,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Green600)
        ) {
            Text(
                "dayExpensesBtn".t(lang),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center
            )
            val todayExpensesTotal = expenses.filter { it.date == today }.sumOf { it.amount }
            Text(
                "₱${String.format("%,.2f", todayExpensesTotal)}",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = Red600
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // ── Close Store button ──
        Button(
            onClick = onCloseStore,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Amber700,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        ) {
            Text(
                "\uD83C\uDF19 ${"closeStore".t(lang)}",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
    }
}

@Composable
private fun DayStatCard(
    modifier: Modifier = Modifier,
    icon: String,
    iconBg: Color,
    label: String,
    value: String
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        // Box fills the entire Card height (150dp from Row),
        // then contentAlignment = Center vertically centers the Column.
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Surface(
                    modifier = Modifier.size(40.dp),
                    shape = RoundedCornerShape(10.dp),
                    color = iconBg
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Text(icon, fontSize = 18.sp)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = Gray500,
                    textAlign = TextAlign.Center
                )
                Text(
                    value,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = Gray800,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Preview(showBackground = true, name = "Day Mode Screen")
@Composable
fun DayModeScreenPreview() {
    TindaGoTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            DayModeScreen(
                viewModel = remember { AppViewModel() },
                onCloseStore = {},
                onNavigateToInventory = {},
                onOpenSaleSheet = {},
                onLaunchTutorial = {}
            )
        }
    }
}
