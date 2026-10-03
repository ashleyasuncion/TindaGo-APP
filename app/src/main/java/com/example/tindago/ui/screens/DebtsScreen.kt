package com.example.tindago.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.tindago.data.CustomerDebt
import com.example.tindago.ui.localization.LocalLanguage
import com.example.tindago.ui.localization.t
import androidx.compose.foundation.lazy.rememberLazyListState
import com.example.tindago.ui.components.LocalScreenLazyListState
import com.example.tindago.ui.components.LocalTutorialHighlightState
import com.example.tindago.ui.components.LocalTutorialScrollStateHolder
import com.example.tindago.ui.components.tutorialHighlight
import com.example.tindago.ui.theme.*
import java.text.NumberFormat
import java.util.*

/**
 * DEBTS SCREEN — Customer debt management.
 * Matches debts.html from the web prototype with clickable debtors and paid debts section.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebtsScreen(
    viewModel: AppViewModel,
    onNewDebt: () -> Unit,
    onNavigateToReports: () -> Unit = {},
    onDebtClick: (Int) -> Unit = {},
    onLaunchTutorial: (() -> Unit)? = null
) {
    val langState = LocalLanguage.current
    val lang = langState.value
    val fmt = remember { NumberFormat.getCurrencyInstance(Locale("en", "PH")) }
    val debts by viewModel.debts.collectAsState()
    val highlightState = LocalTutorialHighlightState.current

    val activeDebts = debts.filter { it.remainingBalance > 0 }
    val paidDebts = debts.filter { it.remainingBalance <= 0 }
    val totalOutstanding = viewModel.totalOutstandingDebts

    // Paid debts collapsible state
    var showPaidDebts by remember { mutableStateOf(false) }

    // Payment bottom sheet state
    var showPaymentSheet by remember { mutableStateOf(false) }
    var selectedDebtId by remember { mutableIntStateOf(-1) }
    var paymentAmount by remember { mutableStateOf("") }
    val debtListState = rememberLazyListState()
    val scrollStateHolder = LocalTutorialScrollStateHolder.current
    LaunchedEffect(debtListState) { scrollStateHolder.updateLazyListState(debtListState) }

    CompositionLocalProvider(LocalScreenLazyListState provides debtListState) {
    LazyColumn(
        state = debtListState,
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
            .tutorialHighlight("debtList", highlightState),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // ── Total Outstanding card ──
        item {
            Card(
                modifier = Modifier.fillMaxWidth().tutorialHighlight("totalDebtCard", highlightState),
                colors = CardDefaults.cardColors(containerColor = Green600),
                shape = MaterialTheme.shapes.medium
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "totalOutstanding".t(lang),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f)
                    )
                    Text(
                        fmt.format(totalOutstanding),
                        style = MaterialTheme.typography.displayLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        }

        // ── New Debt button ──
        item {
            Button(
                onClick = onNewDebt,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .tutorialHighlight("newDebtBtn", highlightState),
                shape = MaterialTheme.shapes.medium
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("newDebt".t(lang), style = MaterialTheme.typography.titleSmall)
            }
        }

        // ── Customer Debt List ──
        if (activeDebts.isEmpty()) {
            item {
                Box(modifier = Modifier.fillMaxWidth().padding(top = 32.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("\u2705", style = MaterialTheme.typography.displayMedium)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("noDebts".t(lang), style = MaterialTheme.typography.bodyMedium, color = Gray400)
                    }
                }
            }
        }

        items(activeDebts, key = { it.id }) { debt ->
            DebtCard(
                debt = debt,
                fmt = fmt,
                lang = lang,
                lastActivity = viewModel.getLastActivity(debt),
                onClick = { onDebtClick(debt.id) },
                onPay = {
                    selectedDebtId = debt.id
                    paymentAmount = ""
                    showPaymentSheet = true
                }
            )
        }

        // ── Paid Debts Section (collapsible) ──
        if (paidDebts.isNotEmpty()) {
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Surface),
                    shape = MaterialTheme.shapes.medium,
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Column {
                        // Collapsible header
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showPaidDebts = !showPaidDebts }
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("\u2705", fontSize = 18.sp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    "paidDebts (${paidDebts.size})",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Green600
                                )
                            }
                            Icon(
                                if (showPaidDebts) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = null,
                                tint = Gray400,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        AnimatedVisibility(visible = showPaidDebts) {
                            Column(modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 8.dp)) {
                                paidDebts.forEach { debt ->
                                    PaidDebtRow(
                                        debt = debt,
                                        fmt = fmt,
                                        lang = lang,
                                        lastActivity = viewModel.getLastActivity(debt),
                                        onClick = { onDebtClick(debt.id) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(8.dp)) }
    }
    }

    // ── Payment Bottom Sheet ──
    if (showPaymentSheet) {
        val debt = activeDebts.find { it.id == selectedDebtId }
        if (debt != null) {
            ModalBottomSheet(
                onDismissRequest = { showPaymentSheet = false },
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        // Keyboard avoidance: lift payment input above the IME.
                        .imePadding()
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 32.dp)
                ) {
                    Text(
                        "\uD83D\uDCB0 Magbayad",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )

                    // Customer info
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Amber50),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                modifier = Modifier.size(44.dp),
                                shape = MaterialTheme.shapes.small,
                                color = Amber100
                            ) {
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                    Text("\uD83D\uDC64", fontSize = 22.sp)
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(debt.customerName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                Text(
                                    "Balanse: ${fmt.format(debt.remainingBalance)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Amber700
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Payment input
                    Text("Magkano ang ibabayad?", style = MaterialTheme.typography.labelMedium, color = Gray500)
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = paymentAmount,
                        onValueChange = { paymentAmount = it },
                        leadingIcon = { Text("\u20B1", fontWeight = FontWeight.Bold) },
                        placeholder = { Text("0.00") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        singleLine = true
                    )

                    // Preview
                    val amount = paymentAmount.toDoubleOrNull() ?: 0.0
                    val remaining = debt.remainingBalance - amount
                    if (amount > 0) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.small,
                            colors = CardDefaults.cardColors(
                                containerColor = if (remaining <= 0) Green50 else Amber50
                            )
                        ) {
                            Text(
                                "Matitira pagkatapos: \u20B1${String.format("%,.2f", remaining.coerceAtLeast(0.0))}",
                                modifier = Modifier.padding(12.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = if (remaining <= 0) Green600 else Amber700
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(
                            onClick = { showPaymentSheet = false },
                            modifier = Modifier.weight(1f).height(48.dp),
                            shape = MaterialTheme.shapes.medium
                        ) { Text("cancel".t(lang)) }
                        Button(
                            onClick = {
                                val amt = paymentAmount.toDoubleOrNull()
                                if (amt != null && amt > 0) {
                                    viewModel.recordDebtPayment(debt.id, amt)
                                    showPaymentSheet = false
                                }
                            },
                            enabled = amount > 0,
                            modifier = Modifier.weight(1f).height(48.dp),
                            shape = MaterialTheme.shapes.medium
                        ) { Text("\u2714\uFE0F Bayad Na \u2713", fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }
    }
}

@Composable
private fun DebtCard(
    debt: CustomerDebt,
    fmt: java.text.NumberFormat,
    lang: String,
    lastActivity: String,
    onClick: () -> Unit,
    onPay: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Surface),
        shape = MaterialTheme.shapes.medium,
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(44.dp),
                shape = MaterialTheme.shapes.small,
                color = Amber50
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Text("\uD83D\uDC64", fontSize = 22.sp)
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    debt.customerName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = Gray800
                )
                Text(
                    "lastActivity".t(lang) + " $lastActivity",
                    style = MaterialTheme.typography.bodySmall,
                    color = Gray400
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    fmt.format(debt.remainingBalance),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = Amber600
                )
                Spacer(modifier = Modifier.height(4.dp))
                Button(
                    onClick = onPay,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text("Pay", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun PaidDebtRow(
    debt: CustomerDebt,
    fmt: java.text.NumberFormat,
    lang: String,
    lastActivity: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(36.dp),
            shape = MaterialTheme.shapes.small,
            color = Green50
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Text("\u2705", fontSize = 16.sp)
            }
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                debt.customerName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = Gray700
            )
            Text(
                "Was ${fmt.format(debt.amount)}",
                style = MaterialTheme.typography.bodySmall,
                color = Gray400
            )
        }
        Text(
            lastActivity,
            style = MaterialTheme.typography.labelSmall,
            color = Gray400
        )
    }
}

@Preview(showBackground = true, name = "Debts Screen")
@Composable
fun DebtsScreenPreview() {
    com.example.tindago.ui.theme.TindaGoTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            DebtsScreen(
                viewModel = remember { AppViewModel() },
                onNewDebt = {}
            )
        }
    }
}
