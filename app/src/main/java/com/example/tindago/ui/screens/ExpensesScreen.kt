package com.example.tindago.ui.screens

import android.app.DatePickerDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.tooling.preview.Preview
import com.example.tindago.data.Expense
import com.example.tindago.data.ExpenseCatalog
import com.example.tindago.data.LocalSnackbarHost
import com.example.tindago.data.LocalSnackbarScope
import com.example.tindago.ui.components.LocalScreenScrollState
import com.example.tindago.ui.components.LocalTutorialHighlightState
import com.example.tindago.ui.components.LocalTutorialScrollStateHolder
import com.example.tindago.ui.components.TutorialIconButton
import com.example.tindago.ui.components.tutorialHighlight
import com.example.tindago.ui.localization.LocalLanguage
import com.example.tindago.ui.localization.Strings
import com.example.tindago.ui.localization.t
import com.example.tindago.ui.theme.*
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Locale

/**
 * EXPENSE LOG — records store operating expenses (rent, utilities, transport,
 * wages, supplies, etc.). Web V2.71 parity (expenses.html): only two inputs are
 * required (amount + category); date defaults to today (editable for backfilling)
 * and the note is optional. Expenses feed Net Profit on Closing + Reports.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpensesScreen(
    viewModel: AppViewModel,
    onBack: () -> Unit,
    onTutorialClick: (() -> Unit)? = null
) {
    val langState = LocalLanguage.current
    val lang = langState.value
    val highlightState = LocalTutorialHighlightState.current
    val snackbarHost = LocalSnackbarHost.current
    val snackbarScope = LocalSnackbarScope.current
    val scrollState = rememberScrollState()

    val expenses by viewModel.expenses.collectAsState()

    var amount by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("") }
    var categoryDropdownExpanded by remember { mutableStateOf(false) }
    var date by remember { mutableStateOf(viewModel.today) }
    var note by remember { mutableStateOf("") }

    val todayTotal = expenses.filter { it.date == viewModel.today }.sumOf { it.amount }
    // Newest first (matches the DAO order and the web list)
    val sorted = expenses.sortedWith(compareByDescending<Expense> { it.date }.thenByDescending { it.timestamp })

    fun save() {
        val amt = amount.toDoubleOrNull() ?: 0.0
        if (amt <= 0) {
            snackbarScope.launch { snackbarHost.showSnackbar("expenseAmountRequired".t(lang)) }
            return
        }
        if (category.isBlank()) {
            snackbarScope.launch { snackbarHost.showSnackbar("expenseCategoryRequired".t(lang)) }
            return
        }
        viewModel.addExpense(date = date, category = category, amount = amt, note = note)
        amount = ""; category = ""; note = ""
        snackbarScope.launch { snackbarHost.showSnackbar("expenseAdded".t(lang)) }
    }

    fun remove(id: Int) {
        viewModel.deleteExpense(id)
        snackbarScope.launch { snackbarHost.showSnackbar("expenseDeleted".t(lang)) }
    }

    Scaffold(
        topBar = {
            // V2.68: subpage rule — Back button present → centered title.
            CenterAlignedTopAppBar(
                title = { Text("expensesTitle".t(lang)) },
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
    val scrollStateHolder = LocalTutorialScrollStateHolder.current
    LaunchedEffect(scrollState) { scrollStateHolder.updateScrollState(scrollState) }
    CompositionLocalProvider(LocalScreenScrollState provides scrollState) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(scrollState)
        ) {
            Text(
                "expensesSubtitle".t(lang),
                style = MaterialTheme.typography.bodySmall,
                color = Gray500,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            // ── Total Today ──
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "expensesTodayTotal".t(lang),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Gray800
                    )
                    Text(
                        "₱${String.format("%,.2f", todayTotal)}",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Red600
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ── Add Expense form ──
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "expenseAddTitle".t(lang),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Green600,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )

                    Text("expenseAmount".t(lang), style = MaterialTheme.typography.labelMedium, color = Gray500)
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = amount,
                        onValueChange = { amount = it.filter { c -> c.isDigit() || c == '.' } },
                        placeholder = { Text("0.00") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        prefix = { Text("₱", fontWeight = FontWeight.Bold) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .tutorialHighlight("expenseAmountField", highlightState),
                        shape = MaterialTheme.shapes.medium
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text("expenseCategory".t(lang), style = MaterialTheme.typography.labelMedium, color = Gray500)
                    Spacer(modifier = Modifier.height(4.dp))
                    ExposedDropdownMenuBox(
                        expanded = categoryDropdownExpanded,
                        onExpandedChange = { categoryDropdownExpanded = !categoryDropdownExpanded },
                        modifier = Modifier
                            .fillMaxWidth()
                            .tutorialHighlight("expenseCategoryField", highlightState)
                    ) {
                        OutlinedTextField(
                            value = if (category.isBlank()) "" else Strings.expenseCategoryLabel(category, lang),
                            onValueChange = {},
                            readOnly = true,
                            placeholder = { Text("expenseCategoryPlaceholder".t(lang), color = Gray400) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = categoryDropdownExpanded) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(),
                            singleLine = true,
                            shape = MaterialTheme.shapes.medium
                        )
                        ExposedDropdownMenu(
                            expanded = categoryDropdownExpanded,
                            onDismissRequest = { categoryDropdownExpanded = false }
                        ) {
                            ExpenseCatalog.CATEGORIES.forEach { key ->
                                DropdownMenuItem(
                                    text = { Text(Strings.expenseCategoryLabel(key, lang)) },
                                    onClick = { category = key; categoryDropdownExpanded = false }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text("expenseDate".t(lang), style = MaterialTheme.typography.labelMedium, color = Gray500)
                    Spacer(modifier = Modifier.height(4.dp))

                    // Date picker state
                    val context = LocalContext.current

                    // Helper to parse date string to Calendar
                    fun parseDateToCalendar(dateStr: String): Calendar {
                        val cal = Calendar.getInstance(Locale.getDefault())
                        try {
                            val parts = dateStr.split("-")
                            if (parts.size == 3) {
                                cal.set(parts[0].toInt(), parts[1].toInt() - 1, parts[2].toInt())
                            }
                        } catch (e: Exception) {
                            // Use current date if parsing fails
                        }
                        return cal
                    }

                    // Format Calendar to yyyy-MM-dd
                    fun formatCalendarToDate(cal: Calendar): String =
                        String.format(Locale.getDefault(), "%04d-%02d-%02d", cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))

                    // Helper to open DatePickerDialog
                    fun openDatePicker() {
                        val cal = parseDateToCalendar(date)
                        DatePickerDialog(
                            context,
                            { _, year, month, dayOfMonth ->
                                val selectedCal = Calendar.getInstance(Locale.getDefault()).apply {
                                    set(year, month, dayOfMonth)
                                }
                                date = formatCalendarToDate(selectedCal)
                            },
                            cal.get(Calendar.YEAR),
                            cal.get(Calendar.MONTH),
                            cal.get(Calendar.DAY_OF_MONTH)
                        ).show()
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .tutorialHighlight("expenseDateField", highlightState)
                    ) {
                        OutlinedTextField(
                            value = date,
                            onValueChange = {},
                            readOnly = true,
                            placeholder = { Text(viewModel.today) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium,
                            trailingIcon = {
                                IconButton(onClick = { openDatePicker() }) {
                                    Icon(Icons.Default.CalendarMonth, contentDescription = "expenseDate".t(lang), tint = Gray500)
                                }
                            }
                        )
                        // Invisible click overlay so tapping anywhere on the field opens the picker
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .clickable { openDatePicker() }
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text("expenseNote".t(lang), style = MaterialTheme.typography.labelMedium, color = Gray500)
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it },
                        placeholder = { Text("expenseNotePlaceholder".t(lang), color = Gray400) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = { save() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .tutorialHighlight("expenseSaveBtn", highlightState),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Text("expenseSave".t(lang), fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ── Expense list ──
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .tutorialHighlight("expenseList", highlightState),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "expenseListTitle".t(lang),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Green600,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    if (sorted.isEmpty()) {
                        Text(
                            "expenseEmpty".t(lang),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Gray400,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    } else {
                        sorted.forEach { e ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        Strings.expenseCategoryLabel(e.category, lang),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Gray800
                                    )
                                    Text(
                                        if (e.note.isBlank()) e.date else "${e.date} · ${e.note}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Gray400
                                    )
                                }
                                Text(
                                    "₱${String.format("%,.2f", e.amount)}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Red600
                                )
                                IconButton(onClick = { remove(e.id) }) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "expenseDelete".t(lang),
                                        tint = Gray400,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            if (sorted.last() != e) {
                                HorizontalDivider(color = Gray100)
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

@Preview(showBackground = true, name = "Expenses Screen")
@Composable
fun ExpensesScreenPreview() {
    com.example.tindago.ui.theme.TindaGoTheme {
        ExpensesScreen(
            viewModel = remember { AppViewModel() },
            onBack = {}
        )
    }
}
