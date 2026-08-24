package com.example.tindago.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.tindago.data.CustomerDebt
import com.example.tindago.data.LocalSnackbarHost
import com.example.tindago.data.LocalSnackbarScope
import com.example.tindago.data.SpecificSale
import com.example.tindago.ui.components.LocalTutorialHighlightState
import com.example.tindago.ui.components.LocalTutorialScrollStateHolder
import com.example.tindago.ui.components.TutorialIconButton
import com.example.tindago.ui.components.tutorialHighlight
import com.example.tindago.ui.localization.LocalLanguage
import com.example.tindago.ui.localization.t
import com.example.tindago.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewDebtScreen(
    viewModel: AppViewModel,
    onBack: () -> Unit,
    onSaved: () -> Unit = {},
    onTutorialClick: (() -> Unit)? = null
) {
    val langState = LocalLanguage.current
    val lang = langState.value
    val highlightState = LocalTutorialHighlightState.current

    val snackbarHost = LocalSnackbarHost.current
    val snackbarScope = LocalSnackbarScope.current

    var customerName by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var phoneNumber by remember { mutableStateOf("") }
    var showSuggestions by remember { mutableStateOf(false) }

    val debts by viewModel.debts.collectAsState()
    val usedNames = remember { viewModel.getUsedCustomerNames() }
    // Build a balance lookup: customer name -> total remaining balance
    val customerBalances = remember(debts) {
        debts.groupBy { it.customerName }
            .mapValues { (_, entries) -> entries.sumOf { it.remainingBalance } }
    }
    val suggestions = remember(customerName, usedNames) {
        if (customerName.length >= 1) {
            usedNames.filter { it.contains(customerName, ignoreCase = true) }.take(5)
        } else emptyList()
    }

    val debtAmount = amount.toDoubleOrNull() ?: 0.0
    val canSave = customerName.isNotBlank() && debtAmount > 0

    // Live credit-limit status for the typed customer + entry amount (web v2.56).
    val creditStatus = if (customerName.isNotBlank()) {
        viewModel.getCreditStatus(customerName, debtAmount)
    } else null

    /** Colour + text for a customer suggestion badge — web suggestionBalanceHtml parity. */
    fun badgeFor(name: String, balance: Double): Pair<String, Color> {
        val limit = viewModel.getEffectiveCreditLimit(name)
        val peso = { v: Double -> "₱" + String.format("%,.2f", v) }
        return if (limit > 0) {
            val txt = "${peso(balance)} / ${peso(limit.toDouble())}"
            val color = when {
                balance == 0.0 -> Green600
                balance >= limit -> Red500
                balance >= limit * 0.8 -> Amber700
                else -> Gray800
            }
            txt to color
        } else {
            if (balance > 0) "${peso(balance)}" to Red500
            else "✓ ₱0.00" to Green600
        }
    }

    /** Localized credit warning message — web creditWarnMessage parity. */
    fun warnText(cs: CreditStatus): String {
        val peso = { v: Double -> "₱" + String.format("%,.2f", v) }
        val key = if (cs.overLimit) {
            if (cs.total > cs.limit) "creditWarnOver" else "creditWarnAtLimit"
        } else "creditWarnNear"
        return when (key) {
            "creditWarnAtLimit" -> "creditWarnAtLimit".t(lang)
                .replace("{name}", customerName)
                .replace("{limit}", peso(cs.limit.toDouble()))
            "creditWarnOver" -> "creditWarnOver".t(lang)
                .replace("{name}", customerName)
                .replace("{total}", peso(cs.total))
                .replace("{limit}", peso(cs.limit.toDouble()))
            else -> "creditWarnNear".t(lang).replace("{limit}", peso(cs.limit.toDouble()))
        }
    }

    /** Web saveNewDebt(force) parity: blocks at/over the credit limit unless forced. */
    fun doSave(force: Boolean) {
        if (!canSave) return
        val name = customerName.trim()
        if (!force) {
            val cs2 = viewModel.getCreditStatus(name, debtAmount)
            if (cs2.overLimit) {
                snackbarScope.launch { snackbarHost.showSnackbar(warnText(cs2)) }
                return
            }
        }
        // Validate phone number if provided
        val normalizedPhone = if (phoneNumber.isNotBlank()) {
            com.example.tindago.data.SmsHelper.normalizePhoneNumber(phoneNumber)
        } else null
        if (phoneNumber.isNotBlank() && normalizedPhone == null) {
            snackbarScope.launch { snackbarHost.showSnackbar("smsPhoneInvalid".t(lang)) }
            return
        }

        // Check if customer already exists
        val existingDebt = viewModel.debts.value.find {
            it.customerName.equals(name, ignoreCase = true)
        }
        if (existingDebt != null) {
            viewModel.addToDebtBalance(existingDebt.id, debtAmount)
            // Update phone number if provided
            if (normalizedPhone != null) {
                viewModel.updateDebtPhoneNumber(existingDebt.id, normalizedPhone)
            }
            // Ledger entry (web saveNewDebt parity: description = "Manual")
            viewModel.addDebtTransaction(existingDebt.id, "debt", "Manual", debtAmount)
        } else {
            val newDebt = viewModel.addDebt(
                CustomerDebt(
                    id = 0,
                    customerName = name,
                    amount = debtAmount,
                    remainingBalance = debtAmount,
                    phoneNumber = normalizedPhone ?: ""
                )
            )
            viewModel.addDebtTransaction(newDebt.id, "debt", "Manual", debtAmount)
        }
        // Also record a SpecificSale so Debt Today on the Day page picks it up
        viewModel.addSpecificSale(
            SpecificSale(
                id = 0,
                date = viewModel.today,
                description = "Manual debt: $name",
                amount = debtAmount,
                quantity = 1,
                customerName = name,
                profit = 0.0
            )
        )
        snackbarScope.launch {
            snackbarHost.showSnackbar("debtSaved".t(lang))
        }
        onSaved()
        onBack()
    }

    val ndScrollState = rememberScrollState()
    val scrollStateHolder = LocalTutorialScrollStateHolder.current
    LaunchedEffect(ndScrollState) { scrollStateHolder.updateScrollState(ndScrollState) }
    Scaffold(
        topBar = {
            // V2.68: subpage rule — Back button present → centered title.
            CenterAlignedTopAppBar(
                title = { Text("newDebtManual".t(lang)) },
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
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(ndScrollState)
        ) {
            // ── Customer Name ───────────────────────────────────────────
            Text("customer".t(lang), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedTextField(
                value = customerName,
                onValueChange = {
                    customerName = it
                    showSuggestions = it.isNotEmpty()
                },
                placeholder = { Text("enterCustomerNameDebt".t(lang)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().tutorialHighlight("newDebtNameField", highlightState),
                leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                trailingIcon = if (customerName.isNotEmpty()) {
                    { IconButton(onClick = { customerName = ""; showSuggestions = false }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear")
                    } }
                } else null,
                shape = MaterialTheme.shapes.medium
            )

            // ── Autocomplete suggestions ────────────────────────────────
            if (showSuggestions && suggestions.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Surface),
                    shape = MaterialTheme.shapes.medium,
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column {
                        suggestions.forEach { name ->
                            val balance = customerBalances[name] ?: 0.0
                            val (badgeText, badgeColor) = badgeFor(name, balance)
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable {
                                    customerName = name; showSuggestions = false
                                }.padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    badgeText,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = badgeColor
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ── Phone Number (SMS feature) ────────────────────────────
            Text("smsPhoneNumber".t(lang), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedTextField(
                value = phoneNumber,
                onValueChange = { phoneNumber = it },
                placeholder = { Text("smsPhonePlaceholder".t(lang)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = { Icon(Icons.Default.Phone, contentDescription = null) },
                supportingText = { Text("smsPhoneHint".t(lang), style = MaterialTheme.typography.bodySmall) },
                shape = MaterialTheme.shapes.medium
            )

            Spacer(modifier = Modifier.height(16.dp))

            // ── Amount ──────────────────────────────────────────────────
            Text("debtAmount".t(lang), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedTextField(
                value = amount,
                onValueChange = { amount = it.filter { c -> c.isDigit() || c == '.' } },
                placeholder = { Text("0.00") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth().tutorialHighlight("newDebtAmountField", highlightState),
                prefix = { Text("₱") },
                shape = MaterialTheme.shapes.medium
            )

            // ── Live credit-limit warning (web v2.56/v2.57 parity) ────────
            val cs = creditStatus
            if (cs != null && (cs.overLimit || cs.nearLimit)) {
                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (cs.overLimit) Red50 else Amber50
                    ),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            warnText(cs),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = if (cs.overLimit) Red700 else Amber800
                        )
                        if (cs.overLimit) {
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = { doSave(force = true) },
                                modifier = Modifier.fillMaxWidth().height(44.dp),
                                shape = MaterialTheme.shapes.medium,
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Red700)
                            ) {
                                Text("creditAllowAnyway".t(lang), fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // ── Info note ───────────────────────────────────────────────
            Spacer(modifier = Modifier.height(16.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Amber50),
                shape = MaterialTheme.shapes.medium
            ) {
                Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
                    Text("📌", style = MaterialTheme.typography.titleLarge)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        "debtNote".t(lang),
                        style = MaterialTheme.typography.bodySmall,
                        color = Amber800
                    )
                }
            }

            // ── Save button ─────────────────────────────────────────────
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = { doSave(force = false) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .tutorialHighlight("newDebtSaveBtn", highlightState),
                enabled = canSave,
                shape = MaterialTheme.shapes.medium
            ) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("saveDebt".t(lang), style = MaterialTheme.typography.titleSmall)
            }
        }
    }
}

@Preview(showBackground = true, name = "New Debt Screen")
@Composable
fun NewDebtScreenPreview() {
    com.example.tindago.ui.theme.TindaGoTheme {
        NewDebtScreen(
            viewModel = remember { AppViewModel() },
            onBack = {}
        )
    }
}
