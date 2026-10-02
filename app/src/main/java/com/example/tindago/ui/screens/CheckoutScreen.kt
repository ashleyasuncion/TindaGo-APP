package com.example.tindago.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.tooling.preview.Preview
import com.example.tindago.data.LocalSnackbarHost
import com.example.tindago.data.LocalSnackbarScope
import com.example.tindago.data.Product
import com.example.tindago.ui.components.CategorySearchField
import com.example.tindago.ui.components.LocalScreenScrollState
import com.example.tindago.ui.components.LocalTutorialHighlightState
import com.example.tindago.ui.components.LocalTutorialScrollStateHolder
import com.example.tindago.ui.components.SupportAppHeader
import com.example.tindago.ui.components.tutorialHighlight
import com.example.tindago.ui.localization.LocalLanguage
import com.example.tindago.ui.localization.Strings
import com.example.tindago.ui.localization.t
import com.example.tindago.ui.theme.*
import com.example.tindago.ui.theme.TindaGoTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * CHECKOUT — standalone multi-item sale page (web v2.63/v2.64 parity).
 *
 * Replaces the old single-item sale bottom sheet. The store owner builds a
 * CART of products, chooses Cash or Utang once, and completes the whole
 * purchase as ONE transaction: shared transactionId on every sale row, a
 * single debt entry per credit purchase, per-line ledger entries, and one
 * stock-deduction pass.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheckoutScreen(
    viewModel: AppViewModel,
    onBack: () -> Unit,
    onTutorialClick: () -> Unit,
    // Entry-guard message (day not open / stale day) from the NavGraph — shown
    // on this screen's own snackbar host before backing out.
    blockedMessage: String? = null
) {
    val langState = LocalLanguage.current
    val lang = langState.value
    val scrollState = rememberScrollState()
    val smsContext = LocalContext.current
    val highlightState = LocalTutorialHighlightState.current
    val scrollStateHolder = LocalTutorialScrollStateHolder.current
    LaunchedEffect(scrollState) { scrollStateHolder.updateScrollState(scrollState) }

    val products by viewModel.products.collectAsState()
    val debts by viewModel.debts.collectAsState()
    val cart by viewModel.saleCart.collectAsState()
    val payment by viewModel.salePayment.collectAsState()
    val quickSell by viewModel.quickSellProducts.collectAsState()
    val recentDebtors by viewModel.recentDebtors.collectAsState()

    // Checkout is a standalone route (not wrapped in MainScaffold), so it owns
    // its own snackbar host + scope and provides them for the whole screen.
    val snackbarHostState = remember { SnackbarHostState() }
    val snackbarScope = rememberCoroutineScope()

    // Show the entry-guard message (if any), then back out (web parity: the
    // Day/Closing guards toast + redirect when the day isn't open).
    LaunchedEffect(blockedMessage) {
        if (blockedMessage != null) {
            snackbarScope.launch { snackbarHostState.showSnackbar(blockedMessage) }
            delay(900)
            onBack()
        }
    }

    // ── Stage 0 constants: 50-char name / 11-digit PH mobile / always mandatory for Utang ──
    val NAME_MAX = 50
    val PHONE_MAX = 11
    // Letters incl. Filipino diacritics, space, hyphen, apostrophe, period — 2..50 chars
    val nameRegex = remember { Regex("^[A-Za-z\u00C0-\u024F '.\\-]{2,50}$") }

    // Form state
    var productQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("") }
    var selectedSubcategory by remember { mutableStateOf("") }
    var selectedProductId by remember { mutableIntStateOf(-1) }
    var quantity by remember { mutableIntStateOf(1) }
    var customerName by remember { mutableStateOf("") }
    var customerPhone by remember { mutableStateOf("") }
    var showSuggestions by remember { mutableStateOf(false) }
    var isEditingQty by remember { mutableStateOf(false) }
    var qtyText by remember { mutableStateOf("1") }
    // Inline validation errors — shown as supportingText when Utang is selected
    var nameError by remember { mutableStateOf<String?>(null) }
    var phoneError by remember { mutableStateOf<String?>(null) }
    // Discard-confirm dialog for leaving with a non-empty cart
    var showDiscardDialog by remember { mutableStateOf(false) }
    // SMS receipt prompt after credit sale
    var showSmsReceiptDialog by remember { mutableStateOf(false) }
    var smsReceiptCustomerName by remember { mutableStateOf("") }
    var smsReceiptPhone by remember { mutableStateOf("") }
    var smsReceiptAmount by remember { mutableStateOf(0.0) }

    val focusManager = LocalFocusManager.current
    val selectedProduct = products.find { it.id == selectedProductId }
    val isQtySelectorDisabled = selectedProductId < 0

    // index.html Section 2B parity: filteredProducts = search ∩ (subcategory ?: category)
    // subcategory wins over category; search covers ALL identity fields including subcategory labels.
    val filteredProducts = viewModel.getCheckoutFilteredProducts(productQuery, selectedCategory, selectedSubcategory)
        .sortedBy { if (it.quantity <= 0) 1 else 0 }
        .take(8)

    // Customer suggestions with balance/limit badges (web v2.56 parity)
    val usedCustomerNames = remember(debts) { debts.map { it.customerName }.distinct() }
    val filteredCustomers = usedCustomerNames.filter {
        it.contains(customerName, ignoreCase = true)
    }.take(5)
    val customerBalances = remember(debts) {
        debts.groupBy { it.customerName }
            .mapValues { (_, entries) -> entries.sumOf { it.remainingBalance } }
    }

    val cartTotal = viewModel.getCartTotal()
    // Credit-limit status uses the CART TOTAL (web v2.63 parity)
    val creditStatus: CreditStatus? =
        if (payment == "credit" && customerName.isNotBlank()) {
            viewModel.getCreditStatus(customerName, cartTotal)
        } else null

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

    fun finishEditing() {
        val parsed = qtyText.toIntOrNull()
        val maxQty = selectedProduct?.quantity ?: Int.MAX_VALUE
        quantity = when {
            parsed == null || parsed < 1 -> 1
            parsed > maxQty -> maxQty
            else -> parsed
        }
        qtyText = quantity.toString()
        isEditingQty = false
        focusManager.clearFocus()
    }

    fun resetForm() {
        productQuery = ""
        selectedProductId = -1
        quantity = 1
        customerName = ""
        customerPhone = ""
        showSuggestions = false
        isEditingQty = false
        qtyText = "1"
        nameError = null
        phoneError = null
    }

    fun toast(msg: String) {
        snackbarScope.launch { snackbarHostState.showSnackbar(msg) }
    }

    // Stage 1: strict validators — shared with Stages 2-4
    fun isNameValid(v: String): Boolean {
        val t = v.trim(); return t.length in 2..NAME_MAX && nameRegex.matches(t)
    }
    fun isPhoneValid(v: String): Boolean = Regex("^09\\d{9}$").matches(v)

    /** Stage 1: Utang ALWAYS requires 50-char name + 11-digit 09XXXXXXXXX phone. */
    fun completeSale(force: Boolean) {
        if (cart.isEmpty()) {
            toast("cartEmpty".t(lang))
            return
        }
        if (payment == "credit") {
            val trimmedName = customerName.trim()
            // 1) Name mandatory
            if (trimmedName.isEmpty()) {
                nameError = "nameRequired".t(lang); phoneError = null
                toast("nameRequired".t(lang)); return
            }
            // 2) Name 2..50 + charset
            if (!isNameValid(customerName)) {
                nameError = "nameInvalid".t(lang); toast("nameInvalid".t(lang)); return
            }
            nameError = null
            // 3) Phone ALWAYS mandatory for Utang
            if (customerPhone.isBlank()) {
                phoneError = "phoneRequired".t(lang)
                toast("phoneRequired".t(lang)); return
            }
            // 4) Phone exactly 11 digits 09XXXXXXXXX — digits-only + prefix already enforced in field
            if (!isPhoneValid(customerPhone)) {
                phoneError = "smsPhoneInvalid".t(lang)
                toast("smsPhoneInvalid".t(lang)); return
            }
            phoneError = null
            // 5) Credit-limit gate (web parity — banner + Allow anyway)
            if (!force) {
                val cs = viewModel.getCreditStatus(trimmedName, cartTotal)
                if (cs.overLimit) return
            }
        } else {
            nameError = null; phoneError = null
        }
        val trimmedName = customerName.trim()
        val normalizedPhone = if (payment == "credit") customerPhone.trim() else null

        val ok = viewModel.completeSale(trimmedName, normalizedPhone, force)
        if (ok) {
            if (payment == "credit" && normalizedPhone != null) {
                smsReceiptCustomerName = trimmedName
                smsReceiptPhone = normalizedPhone
                smsReceiptAmount = cartTotal
                showSmsReceiptDialog = true
            }
            toast("saleCompleted".t(lang))
            resetForm()
        }
    }

    fun addSelectedToCart() {
        val product = selectedProduct ?: return
        if (viewModel.addToCart(product, quantity)) {
            toast("addedToCart".t(lang))
            resetForm()
        }
    }

    // Leaving with a non-empty cart asks first (web leaveCheckout parity)
    fun leave() {
        if (cart.isNotEmpty()) showDiscardDialog = true else onBack()
    }

    BackHandler { leave() }

    CompositionLocalProvider(
        LocalSnackbarHost provides snackbarHostState,
        LocalSnackbarScope provides snackbarScope
    ) {
        Scaffold(
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            topBar = {
                SupportAppHeader(
                    title = "checkoutTitle".t(lang),
                    onBackClick = { leave() },
                    onTutorialClick = onTutorialClick
                )
            }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                CompositionLocalProvider(LocalScreenScrollState provides scrollState) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(scrollState)
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 16.dp)
                ) {
                    Spacer(modifier = Modifier.height(16.dp))

                    // ── Product search ──
                    Text(
                        "addSpecificSale".t(lang),
                        style = MaterialTheme.typography.labelMedium,
                        color = Gray500,
                        modifier = Modifier.tutorialHighlight("checkoutSearch", highlightState)
                    )
                    Spacer(modifier = Modifier.height(4.dp))

                    // ── Quick-Sell (Phase 4.1) — 1-tap bestsellers above search ──
                    if (quickSell.isNotEmpty()) {
                        Text(
                            "mabilisangBenta".t(lang),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(quickSell, key = { it.id }) { product ->
                                SuggestionChip(
                                    onClick = {
                                        if (viewModel.addToCart(product, 1)) {
                                            snackbarScope.launch { snackbarHostState.showSnackbar("addedToCart".t(lang)) }
                                        }
                                    },
                                    label = { Text(product.name + " · ₱" + String.format("%.2f", product.sellingPrice) + " (" + product.quantity + ")") }
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                    }

                    // index.html Sections 2A+2B+2C morphing drill-down field
                    CategorySearchField(
                        searchQuery = productQuery,
                        onSearchQueryChange = {
                            productQuery = it
                            showSuggestions = true
                            selectedProductId = -1
                        },
                        selectedCategory = selectedCategory,
                        selectedSubcategory = selectedSubcategory,
                        onSelectCategory = { cat ->
                            selectedCategory = cat
                            selectedSubcategory = ""
                            showSuggestions = true
                            selectedProductId = -1
                        },
                        onSelectSubcategory = { sub ->
                            selectedSubcategory = sub
                            showSuggestions = true
                            selectedProductId = -1
                        },
                        onClearCategoryFilter = {
                            selectedCategory = ""
                            selectedSubcategory = ""
                            showSuggestions = true
                        },
                        lang = lang
                    )

                    // Product suggestions — filtered by drill-down (subcategory ?: category) ∩ search.
                    // Shows when a category/subcategory is active even with empty query.
                    // Out-of-stock rows greyed and unselectable (v2.58).
                    if (showSuggestions && selectedProductId < 0 && filteredProducts.isNotEmpty() && (productQuery.isNotEmpty() || selectedCategory.isNotBlank())) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                        ) {
                            Column {
                                filteredProducts.forEach { p ->
                                    val outOfStock = p.quantity <= 0
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .alpha(if (outOfStock) 0.5f else 1f)
                                            .clickable {
                                                if (!outOfStock) {
                                                    productQuery = p.name
                                                    selectedProductId = p.id
                                                    showSuggestions = false
                                                }
                                            }
                                            .padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(p.name, style = MaterialTheme.typography.bodyMedium)
                                            val subline = Strings.productSubline(p, lang)
                                            if (subline.isNotEmpty()) {
                                                Text(
                                                    subline,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    fontWeight = FontWeight.Medium,
                                                    color = Green700,
                                                    modifier = Modifier.padding(top = 1.dp)
                                                )
                                            }
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                when {
                                                    p.quantity <= 0 -> "\u26D4 ${"noStock".t(lang)}"
                                                    p.quantity <= 5 -> "\u26A0\uFE0F ${p.quantity} left"
                                                    else -> "\u2705 ${p.quantity} left"
                                                },
                                                style = MaterialTheme.typography.bodySmall,
                                                color = when {
                                                    p.quantity <= 0 -> Red500
                                                    p.quantity <= 5 -> Amber700
                                                    else -> Green600
                                                }
                                            )
                                        }
                                        Text(
                                            "\u20B1${String.format("%,.2f", p.sellingPrice)}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = Green600
                                        )
                                    }
                                    HorizontalDivider()
                                }
                            }
                        }
                    }

                    // Selected product summary
                    if (selectedProduct != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            colors = CardDefaults.cardColors(containerColor = Green50)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text(selectedProduct.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                    val selectedSubline = Strings.productSubline(selectedProduct, lang)
                                    if (selectedSubline.isNotEmpty()) {
                                        Text(
                                            selectedSubline,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.Medium,
                                            color = Green700
                                        )
                                    }
                                    Text(
                                        "\u20B1${String.format("%,.2f", selectedProduct.sellingPrice)} ${"eachLabel".t(lang)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Gray500
                                    )
                                }
                                Text(
                                    "${"stockLabel".t(lang)} ${selectedProduct.quantity}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (selectedProduct.quantity <= 5) Red500 else Green600
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // ── Quantity selector ──
                    Text("quantity".t(lang), style = MaterialTheme.typography.labelMedium, color = Gray500)
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        val alpha = if (isQtySelectorDisabled) 0.45f else 1f
                        FilledTonalIconButton(
                            onClick = { if (quantity > 1) quantity-- },
                            enabled = !isQtySelectorDisabled && quantity > 1,
                            modifier = Modifier.alpha(alpha)
                        ) {
                            Text("\u2212", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        }
                        if (isEditingQty && !isQtySelectorDisabled) {
                            var hasBeenFocused by remember { mutableStateOf(false) }
                            val focusRequester = remember { FocusRequester() }
                            LaunchedEffect(Unit) { focusRequester.requestFocus() }
                            OutlinedTextField(
                                value = qtyText,
                                onValueChange = { qtyText = it.filter { c -> c.isDigit() } },
                                modifier = Modifier
                                    .width(80.dp)
                                    .focusRequester(focusRequester)
                                    .onFocusChanged {
                                        if (it.isFocused) hasBeenFocused = true
                                        if (!it.isFocused && hasBeenFocused) finishEditing()
                                    },
                                singleLine = true,
                                textStyle = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.Bold,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                ),
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Number,
                                    imeAction = ImeAction.Done
                                ),
                                keyboardActions = KeyboardActions(onDone = { finishEditing() })
                            )
                        } else {
                            Text(
                                if (isQtySelectorDisabled) "--" else "$quantity",
                                modifier = Modifier
                                    .width(60.dp)
                                    .padding(horizontal = 16.dp)
                                    .clickable(enabled = !isQtySelectorDisabled) {
                                        isEditingQty = true
                                        qtyText = quantity.toString()
                                    },
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                color = if (isQtySelectorDisabled) Gray300 else Color.Unspecified
                            )
                        }
                        FilledTonalIconButton(
                            onClick = {
                                if (selectedProduct != null && quantity < selectedProduct.quantity) quantity++
                            },
                            enabled = !isQtySelectorDisabled && selectedProduct != null && quantity < selectedProduct.quantity,
                            modifier = Modifier.alpha(alpha)
                        ) {
                            Text("+", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    val selProduct = selectedProduct
                    if (selProduct != null) {
                        if (selProduct.quantity <= 0) {
                            Text("\u26D4 ${"noStock".t(lang)}", style = MaterialTheme.typography.bodySmall, color = Red500)
                        } else if (quantity == selProduct.quantity) {
                            Text("\u2705 Available: ${selProduct.quantity} (max)", style = MaterialTheme.typography.bodySmall, color = Green600)
                        } else {
                            Text("\u2705 Available: ${selProduct.quantity}", style = MaterialTheme.typography.bodySmall, color = Green600)
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // ── Add to Cart ──
                    Button(
                        onClick = { addSelectedToCart() },
                        enabled = selectedProduct != null && quantity > 0,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .tutorialHighlight("checkoutAddCart", highlightState),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Green600)
                    ) {
                        Text("\uD83D\uDED2 ${"addToCart".t(lang)}", fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // ── Cart section ──
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${"cartTitle".t(lang)}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = Gray700
                        )
                        if (cart.isNotEmpty()) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = Green600
                            ) {
                                Text(
                                    "${viewModel.getCartLineCount()}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    if (cart.isEmpty()) {
                        Text(
                            "cartEmpty".t(lang),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Gray400,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .tutorialHighlight("checkoutCart", highlightState)
                        ) {
                            cart.forEachIndexed { index, line ->
                                CartLineRow(
                                    line = line,
                                    lang = lang,
                                    onAdjust = { delta -> viewModel.cartAdjustQty(line.productId, delta) },
                                    onRemove = {
                                        viewModel.cartRemoveLine(line.productId)
                                        toast("itemRemoved".t(lang))
                                    }
                                )
                                if (index < cart.lastIndex) {
                                    HorizontalDivider(color = Gray100, modifier = Modifier.padding(vertical = 4.dp))
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // ── Payment method ──
                    Text("paymentMethod".t(lang), style = MaterialTheme.typography.labelMedium, color = Gray500)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PaymentChoiceButton(
                            label = "payCash".t(lang),
                            selected = payment == "cash",
                            onClick = { viewModel.setSalePayment("cash") },
                            modifier = Modifier.weight(1f)
                        )
                        PaymentChoiceButton(
                            label = "payCredit".t(lang),
                            selected = payment == "credit",
                            onClick = { viewModel.setSalePayment("credit") },
                            modifier = Modifier
                                .weight(1f)
                                .tutorialHighlight("checkoutPayCredit", highlightState)
                        )
                    }

                    // ── Customer name (only for utang) ──
                    if (payment == "credit") {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("saleCustomerLabel".t(lang), style = MaterialTheme.typography.labelMedium, color = Gray500)
                        Spacer(modifier = Modifier.height(4.dp))
                        // ── Smart Utang (Phase 4.3) ─ recent debtor shortcuts ──
                        if (recentDebtors.isNotEmpty()) {
                            Text("recentDebtors".t(lang), style = MaterialTheme.typography.labelMedium, color = Color.Gray)
                            Spacer(modifier = Modifier.height(4.dp))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(recentDebtors, key = { it.id }) { debtor ->
                                    SuggestionChip(
                                        onClick = { customerName = debtor.customerName },
                                        label = { Text(debtor.customerName + " : P" + String.format("%.2f", debtor.remainingBalance)) }
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        OutlinedTextField(
                            value = customerName,
                            onValueChange = {
                                if (it.length <= NAME_MAX) customerName = it
                                if (nameError != null) nameError = null
                            },
                            placeholder = { Text("customerPlaceholder".t(lang)) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            singleLine = true,
                            isError = payment == "credit" && nameError != null,
                            supportingText = {
                                if (payment == "credit") {
                                    Text(
                                        nameError ?: "${customerName.length}/$NAME_MAX",
                                        color = if (nameError != null) MaterialTheme.colorScheme.error else Gray500
                                    )
                                }
                            },
                            keyboardOptions = KeyboardOptions(capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() })
                        )

                        // Phone number field (SMS feature) — 11 digits digits-only, always mandatory for Utang
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("smsPhoneNumber".t(lang), style = MaterialTheme.typography.labelMedium, color = Gray500)
                        Spacer(modifier = Modifier.height(4.dp))
                        OutlinedTextField(
                            value = customerPhone,
                            onValueChange = {
                                val digits = it.filter { c -> c.isDigit() }
                                if (digits.length <= PHONE_MAX) customerPhone = digits
                                if (phoneError != null) phoneError = null
                            },
                            placeholder = { Text("smsPhonePlaceholder".t(lang)) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            singleLine = true,
                            isError = payment == "credit" && phoneError != null,
                            supportingText = {
                                if (payment == "credit") {
                                    Text(
                                        phoneError ?: "${customerPhone.length}/$PHONE_MAX",
                                        color = if (phoneError != null) MaterialTheme.colorScheme.error else Gray500
                                    )
                                }
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                            leadingIcon = { Icon(Icons.Default.Phone, contentDescription = null, modifier = Modifier.size(18.dp)) }
                        )
                        if (customerName.isNotEmpty() && filteredCustomers.isNotEmpty()) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                            ) {
                                Column {
                                    filteredCustomers.forEach { name ->
                                        val balance = customerBalances[name] ?: 0.0
                                        val (badgeText, badgeColor) = badgeFor(name, balance)
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable { customerName = name }
                                                .padding(12.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                            Text(
                                                badgeText,
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = FontWeight.SemiBold,
                                                color = badgeColor
                                            )
                                        }
                                        HorizontalDivider()
                                    }
                                }
                            }
                        }

                        // ── Live credit-limit warning (web v2.56/v2.57 parity) ──
                        val cs = creditStatus
                        if (cs != null && (cs.overLimit || cs.nearLimit)) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (cs.overLimit) Red50 else Amber50
                                )
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
                                            onClick = { completeSale(force = true) },
                                            modifier = Modifier.fillMaxWidth().height(44.dp),
                                            shape = RoundedCornerShape(10.dp),
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Red700)
                                        ) {
                                            Text("creditAllowAnyway".t(lang), fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // ── Total ──
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Green50)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("total".t(lang), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(
                                "\u20B1${String.format("%,.2f", cartTotal)}",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = Green600
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // ── Actions ──
                    // V2.68: "Kumpletuhin ang Benta" needs more room than the
                    // default 50/50 split + 24dp button padding allows, so the
                    // Complete Sale button takes the wide share (1.5 vs 0.5)
                    // and both buttons use tighter horizontal padding (8.dp).
                    // At the default text scale this keeps the Filipino label
                    // on ONE line at every supported width (320dp+). The font
                    // itself is left to the theme (respects the app's text-size
                    // setting); heightIn + maxLines=2 let Extra Large text wrap
                    // gracefully inside a slightly taller button instead of
                    // clipping, and Close maxes at one line.
                    val utangReady = payment != "credit" || (isNameValid(customerName) && isPhoneValid(customerPhone))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(
                            onClick = { leave() },
                            modifier = Modifier.weight(0.5f).heightIn(min = 50.dp),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Text("close".t(lang), maxLines = 1)
                        }
                        Button(
                            onClick = { completeSale(force = false) },
                            enabled = cart.isNotEmpty() && utangReady,
                            modifier = Modifier
                                .weight(1.5f)
                                .heightIn(min = 50.dp)
                                .tutorialHighlight("checkoutComplete", highlightState),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Green600),
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Text(
                                "\u2714\uFE0F ${"completeSale".t(lang)}",
                                fontWeight = FontWeight.Bold,
                                maxLines = 2,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }
            }
        }
        }
    }

    // Discard-confirm dialog (web closeSaleSheet/leaveCheckout parity)
    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text("discardCart".t(lang), fontWeight = FontWeight.Bold) },
            text = { Text("discardCartMsg".t(lang)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDiscardDialog = false
                        viewModel.clearCart()
                        resetForm()
                        onBack()
                    }
                ) {
                    Text("discardCart".t(lang), color = Red700, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) {
                    Text("cancel".t(lang), color = Gray600)
                }
            }
        )
    }

    // SMS receipt prompt after credit sale
    if (showSmsReceiptDialog) {
        AlertDialog(
            onDismissRequest = { showSmsReceiptDialog = false },
            icon = { Icon(Icons.Default.Sms, contentDescription = null, tint = Green700) },
            title = { Text("smsReceiptTitle".t(lang), fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(
                        "smsReceiptMsg".t(lang)
                            .replace("{name}", smsReceiptCustomerName)
                            .replace("{amount}", "₱" + String.format(java.util.Locale.US, "%,.2f", smsReceiptAmount)),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        smsReceiptPhone,
                        style = MaterialTheme.typography.bodySmall,
                        color = Gray400
                    )
                }
            },
            confirmButton = {                    TextButton(
                    onClick = {
                        showSmsReceiptDialog = false
                        val smsCtx = smsContext
                        val msg = com.example.tindago.data.SmsHelper.buildReceiptMessage(
                            smsReceiptCustomerName, smsReceiptAmount, viewModel.getStoreName(),
                            smsReceiptAmount, lang
                        )
                        com.example.tindago.data.SmsHelper.sendSmsIntent(smsCtx, smsReceiptPhone, msg)
                    }
                ) {
                    Text("smsReceiptSend".t(lang), color = Green700, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSmsReceiptDialog = false }) {
                    Text("smsReceiptSkip".t(lang), color = Gray600)
                }
            }
        )
    }
}

/** One cart line: name + brand·size subline, qty stepper, subtotal, remove. */
@Composable
private fun CartLineRow(
    line: AppViewModel.CartLine,
    lang: String,
    onAdjust: (Int) -> Unit,
    onRemove: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(line.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = Gray800)
            val subline = Strings.productSubline(
                Product(
                    id = line.productId,
                    name = line.name,
                    quantity = 0,
                    costPrice = 0.0,
                    sellingPrice = line.sellingPrice,
                    unit = line.unit,
                    brand = line.brand,
                    packageSize = line.packageSize
                ),
                lang
            )
            if (subline.isNotEmpty()) {
                Text(
                    subline,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = Gray400
                )
            }
            Text(
                "\u20B1${String.format("%,.2f", line.sellingPrice)} ${"eachLabel".t(lang)}",
                style = MaterialTheme.typography.bodySmall,
                color = Gray500
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledTonalIconButton(onClick = { onAdjust(-1) }, enabled = line.qty > 1, modifier = Modifier.size(32.dp)) {
                Text("\u2212", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            Text(
                "${line.qty}",
                modifier = Modifier.width(44.dp),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            FilledTonalIconButton(onClick = { onAdjust(1) }, modifier = Modifier.size(32.dp)) {
                Text("+", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            "\u20B1${String.format("%,.2f", line.subtotal)}",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = Green600
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            "\u2716",
            modifier = Modifier
                .clickable(onClick = onRemove)
                .padding(6.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = Red500
        )
    }
}

/** Cash / Utang choice chip — web payment-toggle parity. */
@Composable
private fun PaymentChoiceButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (selected) {
        Button(
            onClick = onClick,
            modifier = modifier.height(46.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Green600)
        ) {
            Text(label, fontWeight = FontWeight.Bold)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier.height(46.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(label, color = Gray700)
        }
    }
}

@Preview(showBackground = true, name = "Checkout Screen")
@Composable
fun CheckoutScreenPreview() {
    TindaGoTheme {
        CheckoutScreen(
            viewModel = remember { AppViewModel() },
            onBack = {},
            onTutorialClick = {}
        )
    }
}
