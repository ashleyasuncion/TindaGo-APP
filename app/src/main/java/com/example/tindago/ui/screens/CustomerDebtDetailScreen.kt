package com.example.tindago.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.tooling.preview.Preview
import com.example.tindago.data.CustomerDebt
import com.example.tindago.data.DebtPayment
import com.example.tindago.data.LocalSnackbarHost
import com.example.tindago.data.LocalSnackbarScope
import com.example.tindago.data.SpecificSale
import com.example.tindago.ui.components.LocalScreenScrollState
import com.example.tindago.ui.components.LocalTutorialHighlightState
import com.example.tindago.ui.components.LocalTutorialScrollStateHolder
import com.example.tindago.ui.components.TutorialIconButton
import com.example.tindago.ui.components.tutorialHighlight
import com.example.tindago.ui.localization.LocalLanguage
import com.example.tindago.ui.localization.t
import com.example.tindago.ui.theme.*
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomerDebtDetailScreen(
    viewModel: AppViewModel,
    debtId: Int = 0,
    onBack: () -> Unit,
    onRecordPayment: (Int) -> Unit = {},
    onTutorialClick: (() -> Unit)? = null
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val langState = LocalLanguage.current
    val lang = langState.value
    val highlightState = LocalTutorialHighlightState.current
    val fmt = remember { NumberFormat.getCurrencyInstance(Locale("en", "PH")) }
    val snackbarHost = LocalSnackbarHost.current
    val snackbarScope = LocalSnackbarScope.current
    val debts by viewModel.debts.collectAsState()
    val allPayments by viewModel.payments.collectAsState()
    val debtTransactions by viewModel.debtTransactions.collectAsState()

    val debt = debts.find { it.id == debtId }
    val payments = allPayments.filter { it.debtId == debtId }.sortedBy { it.timestamp }
    val isSettled = debt?.remainingBalance != null && debt.remainingBalance <= 0

    // Credit-limit edit state (web v2.56 parity)
    var editingCreditLimit by remember { mutableStateOf(false) }
    var creditLimitInput by remember { mutableStateOf("") }

    // Phone number edit state (SMS feature)
    var editingPhone by remember { mutableStateOf(false) }
    var phoneInput by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            // V2.68: subpage rule — Back button present → centered title.
            CenterAlignedTopAppBar(
                title = { Text(debt?.customerName ?: "customerDebt".t(lang)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (onTutorialClick != null) TutorialIconButton(onClick = onTutorialClick)
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Green600,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { padding ->
        if (debt == null) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("👤", style = MaterialTheme.typography.displayLarge)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Customer not found", style = MaterialTheme.typography.bodyMedium, color = Gray400)
                }
            }
            return@Scaffold
        }

        // Build chronological transaction list
        data class Transaction(
            val date: Long,
            val type: String, // "debt", "payment", "initial"
            val description: String,
            val amount: Double,
            val isPositive: Boolean // true = added to balance, false = deducted
        )

        /** Relative date for history rows — mirrors the web's formatDateSafe():
         *  "Today" / "Yesterday" / "MMM d" (hardcoded English, matching the web). */
        fun formatWebDate(ts: Long): String {
            val diffDays = ((System.currentTimeMillis() - ts) / (1000L * 60 * 60 * 24)).toInt()
            return when (diffDays) {
                0 -> "Today"
                1 -> "Yesterday"
                else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(ts))
            }
        }

        val transactions = mutableListOf<Transaction>()

        // 1. Debt-add ledger entries (web transactions[] parity). Debts without
        //    ledger entries (pre-migration data) fall back to a single initial
        //    row — the web's legacy-migration behavior.
        val debtTxs = debtTransactions.filter { it.debtId == debtId }
        if (debtTxs.isEmpty()) {
            transactions.add(Transaction(
                date = debt.createdAt,
                type = "initial",
                description = "initialDebt".t(lang),
                amount = debt.amount,
                isPositive = true
            ))
        } else {
            debtTxs.forEach { tx ->
                transactions.add(Transaction(
                    date = tx.timestamp,
                    type = tx.type,
                    description = tx.description ?: "initialDebt".t(lang),
                    amount = tx.amount,
                    isPositive = true
                ))
            }
        }

        // 2. Payments (DebtPayment is the payment ledger)
        payments.forEach { payment ->
            transactions.add(Transaction(
                date = payment.timestamp,
                type = "payment",
                description = payment.note ?: "payment".t(lang),
                amount = payment.amount,
                isPositive = false
            ))
        }

        // Sort by date ascending
        transactions.sortBy { it.date }

        val cddScrollState = rememberScrollState()
        val scrollStateHolder = LocalTutorialScrollStateHolder.current
        LaunchedEffect(cddScrollState) { scrollStateHolder.updateScrollState(cddScrollState) }
        CompositionLocalProvider(LocalScreenScrollState provides cddScrollState) {
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(cddScrollState)
        ) {
            // ── Balance Card ────────────────────────────────────────────
            Card(
                modifier = Modifier.fillMaxWidth().tutorialHighlight("cddBalanceCard", highlightState),
                colors = CardDefaults.cardColors(
                    containerColor = if (isSettled) Green50 else Amber50
                ),
                shape = MaterialTheme.shapes.medium
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "currentBalance".t(lang),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isSettled) Green600 else Amber800
                    )
                    Text(
                        fmt.format(debt.remainingBalance),
                        style = MaterialTheme.typography.displayLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (isSettled) Green600 else Amber600
                    )
                    if (isSettled) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("fullySettled".t(lang), style = MaterialTheme.typography.bodySmall, color = Green600)
                    } else {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "lastActivity".t(lang) + " ${viewModel.getLastActivity(debt)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Gray500
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ── Phone Number Card (SMS feature) ────────────────────────
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Surface),
                shape = MaterialTheme.shapes.medium,
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "smsPhoneNumber".t(lang),
                                style = MaterialTheme.typography.labelSmall,
                                color = Gray400
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            if (debt.phoneNumber.isNotBlank()) {
                                Text(
                                    debt.phoneNumber,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Green600
                                )
                            } else {
                                Text(
                                    "smsPhonePlaceholder".t(lang),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Gray400
                                )
                            }
                        }
                        TextButton(onClick = {
                            editingPhone = !editingPhone
                            phoneInput = debt.phoneNumber
                        }) {
                            Text(
                                if (editingPhone) "creditLimitCancel".t(lang)
                                else "creditLimitEdit".t(lang),
                                color = Green700
                            )
                        }
                    }
                    if (editingPhone) {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = phoneInput,
                            onValueChange = { phoneInput = it },
                            label = { Text("smsPhoneNumber".t(lang)) },
                            placeholder = { Text("smsPhonePlaceholder".t(lang)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "smsPhoneHint".t(lang),
                            style = MaterialTheme.typography.bodySmall,
                            color = Gray400
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                val normalized = if (phoneInput.isNotBlank()) {
                                    com.example.tindago.data.SmsHelper.normalizePhoneNumber(phoneInput)
                                } else null
                                if (phoneInput.isNotBlank() && normalized == null) {
                                    snackbarScope.launch { snackbarHost.showSnackbar("smsPhoneInvalid".t(lang)) }
                                    return@Button
                                }
                                viewModel.updateDebtPhoneNumber(debt.id, normalized ?: "")
                                editingPhone = false
                                snackbarScope.launch {
                                    snackbarHost.showSnackbar("smsPhoneSaved".t(lang))
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Text("creditLimitSave".t(lang), fontWeight = FontWeight.Bold)
                        }
                    }

                    // SMS Actions — Send Receipt / Send Reminder
                    if (debt.phoneNumber.isNotBlank() && !isSettled) {
                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider(color = Gray100)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            "smsSectionTitle".t(lang),
                            style = MaterialTheme.typography.labelSmall,
                            color = Gray400
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Send Receipt button
                            OutlinedButton(
                                onClick = {
                                    val storeName = viewModel.getStoreName()
                                    val msg = com.example.tindago.data.SmsHelper.buildReceiptMessage(
                                        customerName = debt.customerName,
                                        amount = debt.amount,
                                        storeName = storeName,
                                        totalBalance = debt.remainingBalance,
                                        lang = lang
                                    )
                                    com.example.tindago.data.SmsHelper.sendSmsIntent(
                                        context,
                                        debt.phoneNumber,
                                        msg
                                    )
                                    snackbarScope.launch { snackbarHost.showSnackbar("smsSent".t(lang)) }
                                },
                                modifier = Modifier.weight(1f).height(44.dp),
                                shape = MaterialTheme.shapes.medium
                            ) {
                                Icon(Icons.Default.Receipt, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("smsSendReceipt".t(lang), style = MaterialTheme.typography.bodySmall)
                            }
                            // Send Reminder button
                            OutlinedButton(
                                onClick = {
                                    val storeName = viewModel.getStoreName()
                                    val daysSinceCreation = ((System.currentTimeMillis() - debt.createdAt) / (1000L * 60 * 60 * 24)).toInt()
                                    val totalPaid = debt.amount - debt.remainingBalance
                                    val limit = viewModel.getEffectiveCreditLimit(debt.customerName)
                                    val remainingCredit = (limit - debt.remainingBalance).coerceAtLeast(0.0)
                                    val msg = com.example.tindago.data.SmsHelper.buildReminderMessage(
                                        customerName = debt.customerName,
                                        storeName = storeName,
                                        totalBalance = debt.remainingBalance,
                                        daysOpen = daysSinceCreation,
                                        totalPaid = totalPaid,
                                        effectiveCreditLimit = limit,
                                        remainingCredit = remainingCredit,
                                        lang = lang
                                    )
                                    com.example.tindago.data.SmsHelper.sendSmsIntent(
                                        context,
                                        debt.phoneNumber,
                                        msg
                                    )
                                    snackbarScope.launch { snackbarHost.showSnackbar("smsSent".t(lang)) }
                                },
                                modifier = Modifier.weight(1f).height(44.dp),
                                shape = MaterialTheme.shapes.medium
                            ) {
                                Icon(Icons.Default.Notifications, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("smsSendReminder".t(lang), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ── Credit Limit Card (web v2.56 parity) ──────────────────────
            val hasCustomLimit = debt.creditLimit != null
            val effectiveLimit = if (hasCustomLimit) debt.creditLimit!! else viewModel.getDefaultCreditLimit()
            Card(
                modifier = Modifier.fillMaxWidth().tutorialHighlight("cddCreditLimit", highlightState),
                colors = CardDefaults.cardColors(containerColor = Surface),
                shape = MaterialTheme.shapes.medium,
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                "creditLimitLabel".t(lang),
                                style = MaterialTheme.typography.labelSmall,
                                color = Gray400
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            if (effectiveLimit > 0) {
                                Text(
                                    fmt.format(effectiveLimit.toDouble()),
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = Green600
                                )
                            } else {
                                Text(
                                    "creditLimitNone".t(lang),
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = Gray500
                                )
                            }
                            if (!hasCustomLimit && effectiveLimit > 0) {
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    "creditLimitUsesDefault".t(lang) + " (${fmt.format(viewModel.getDefaultCreditLimit().toDouble())})",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Gray400
                                )
                            }
                        }
                        TextButton(onClick = {
                            editingCreditLimit = !editingCreditLimit
                            creditLimitInput = effectiveLimit.toString()
                        }) {
                            Text(
                                if (editingCreditLimit) "creditLimitCancel".t(lang)
                                else "creditLimitEdit".t(lang),
                                color = Green700
                            )
                        }
                    }
                    if (editingCreditLimit) {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = creditLimitInput,
                            onValueChange = { creditLimitInput = it.filter { c -> c.isDigit() } },
                            label = { Text("creditLimitLabel".t(lang)) },
                            prefix = { Text("₱") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number
                            ),
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "defaultCreditLimitHint".t(lang),
                            style = MaterialTheme.typography.bodySmall,
                            color = Gray400
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                val value = creditLimitInput.toIntOrNull()
                                if (value != null) {
                                    viewModel.updateDebtCreditLimit(debt.id, value.coerceIn(0, 10000))
                                } else {
                                    // Empty/invalid input → use default (clear custom)
                                    viewModel.updateDebtCreditLimit(debt.id, null)
                                }
                                editingCreditLimit = false
                                snackbarScope.launch {
                                    snackbarHost.showSnackbar("creditLimitSaved".t(lang))
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Text("creditLimitSave".t(lang), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ── Debt History with Running Balance ────────────────────────
            Card(
                modifier = Modifier.fillMaxWidth().tutorialHighlight("cddLedger", highlightState),
                colors = CardDefaults.cardColors(containerColor = Surface),
                shape = MaterialTheme.shapes.medium,
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.History, contentDescription = null, tint = Green600, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("debtHistory".t(lang), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(12.dp))

                    // Header row — mirrors web .debt-history-header (28px | 1fr | auto | auto)
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Spacer(modifier = Modifier.width(28.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Description",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = Gray400,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "Amount",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = Gray400,
                            textAlign = TextAlign.End,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                        Text(
                            "Balance",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = Gray400,
                            textAlign = TextAlign.End,
                            modifier = Modifier.width(64.dp).padding(start = 8.dp)
                        )
                    }

                    HorizontalDivider(color = Gray100)

                    // Transaction rows with running balance
                    var runningBalance = 0.0
                    transactions.forEachIndexed { index, tx ->
                        runningBalance += if (tx.isPositive) tx.amount else -tx.amount
                        TransactionRow(
                            date = formatWebDate(tx.date),
                            // Deliberately unlocalized — matches web's hardcoded "Added"/"Payment" tags
                            typeLabel = if (tx.isPositive) "Added" else "Payment",
                            description = tx.description,
                            amount = tx.amount,
                            isPositive = tx.isPositive,
                            runningBalance = runningBalance,
                            fmt = fmt
                        )
                        // Divider between rows only (web: .debt-history-row:not(:last-child))
                        if (index < transactions.lastIndex) {
                            HorizontalDivider(color = Gray100)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ── Summary row ──────────────────────────────────────────────
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Surface),
                shape = MaterialTheme.shapes.medium,
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("totalDebt".t(lang), style = MaterialTheme.typography.labelSmall, color = Gray400)
                        Text(fmt.format(debt.amount), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Red600)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("totalCollected".t(lang), style = MaterialTheme.typography.labelSmall, color = Gray400)
                        val totalCollected = payments.sumOf { it.amount }
                        Text(fmt.format(totalCollected), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Green600)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ── Record Payment Button ───────────────────────────────────
            if (!isSettled) {
                Button(
                    onClick = { onRecordPayment(debt.id) },
                    modifier = Modifier.fillMaxWidth().height(56.dp).tutorialHighlight("cddRecordPaymentBtn", highlightState),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Icon(Icons.Default.Payment, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("recordPayment".t(lang), style = MaterialTheme.typography.titleSmall)
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
    }
}

@Composable
private fun TransactionRow(
    date: String,
    typeLabel: String,
    description: String,
    amount: Double,
    isPositive: Boolean,
    runningBalance: Double,
    fmt: java.text.NumberFormat
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Icon — web .debt-history-icon (28px column, 16px, centered)
        Text(
            if (isPositive) "\uD83D\uDFE2" else "\uD83D\uDFE0",
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(28.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        // Description + relative "date • type" sub-line — web .debt-history-info
        Column(modifier = Modifier.weight(1f)) {
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = Gray800
            )
            Text(
                "$date • $typeLabel",
                fontSize = 10.sp,
                color = Gray400,
                modifier = Modifier.padding(top = 1.dp)
            )
        }
        // Amount — web .debt-history-amount (right-aligned, 600 weight, red/green)
        Text(
            (if (isPositive) "+" else "-") + fmt.format(amount),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = if (isPositive) Red600 else Green600,
            textAlign = TextAlign.End,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 8.dp)
        )
        // Running balance — web .debt-history-running (right-aligned, 600, text color, min 60px)
        Text(
            fmt.format(runningBalance),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = Gray800,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(64.dp).padding(start = 8.dp)
        )
    }
}

@Preview(showBackground = true, name = "Customer Debt Detail")
@Composable
fun CustomerDebtDetailScreenPreview() {
    com.example.tindago.ui.theme.TindaGoTheme {
        CustomerDebtDetailScreen(
            viewModel = remember { AppViewModel() },
            debtId = 1,
            onBack = {}
        )
    }
}
