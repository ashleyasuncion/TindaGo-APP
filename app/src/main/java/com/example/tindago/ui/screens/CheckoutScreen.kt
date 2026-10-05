package com.example.tindago.ui.screens

import androidx.activity.compose.BackHandler
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.example.tindago.ui.components.SelectedProductDisplay
import com.example.tindago.ui.components.SupportAppHeader
import com.example.tindago.ui.components.tutorialHighlight
import com.example.tindago.ui.localization.LocalLanguage
import com.example.tindago.ui.localization.Strings
import com.example.tindago.ui.localization.t
import com.example.tindago.ui.theme.*
import com.example.tindago.ui.theme.TindaGoTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

import com.example.tindago.ui.util.performHapticFeedback

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
    blockedMessage: String? = null,
    // Stage 3 - tutorial overlay <-> wizard alignment. While the checkout
    // tutorial replays on this page this is the active tutorial step index
    // (-1 = not running); the screen then drives its 4-step wizard to the
    // section the tutorial describes (the web checkout is a single page, but
    // the mobile wizard only composes one step at a time).
    tutorialStepIndex: Int = -1
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
    var selectedProductId by rememberSaveable { mutableIntStateOf(-1) }
    var quantity by remember { mutableIntStateOf(1) }
    var customerName by remember { mutableStateOf("") }
    var customerPhone by remember { mutableStateOf("") }
    var showSuggestions by remember { mutableStateOf(false) }
    var isEditingQty by remember { mutableStateOf(false) }
    var qtyText by remember { mutableStateOf("1") }
    // Wizard step state (1..4) — mirrors web v2.64 checkout wizard
    var step by rememberSaveable { mutableIntStateOf(1) }

    // Stage 3 - tutorial overlay <-> wizard alignment: while the checkout
    // tutorial replays, follow its step index so the highlighted section is
    // actually composed (the tutorial backdrop already blocks manual changes).
    // Tutorial index -> wizard step mirrors the web highlight order:
    //   0-1 (search / add-to-cart)   -> 1
    //   2   (cart)                   -> 2
    //   3   (payment)                -> 3
    //   4-5 (complete / replay hint) -> 4
    // The shopper's step when the tutorial starts is remembered and restored
    // when it ends, so a replay never strands the wizard on a section that was
    // never reached (the web tutorial never moves the wizard at all).
    var stepBeforeTutorial by remember { mutableIntStateOf(1) }
    var tutorialWasActive by remember { mutableStateOf(false) }
    LaunchedEffect(tutorialStepIndex) {
        val isTutorialActive = tutorialStepIndex >= 0
        if (isTutorialActive && !tutorialWasActive) stepBeforeTutorial = step
        if (isTutorialActive) {
            val target = tutorialStepIndex.coerceIn(1, 4)
            if (step != target) {
                step = target
                scrollState.scrollTo(0)
            }
        } else if (tutorialWasActive && step != stepBeforeTutorial) {
            step = stepBeforeTutorial
            scrollState.scrollTo(0)
        }
        tutorialWasActive = isTutorialActive
    }
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
        step = 1
    }

    fun toast(msg: String) {
        snackbarScope.launch { snackbarHostState.showSnackbar(msg) }
    }

    /** Web canGoNextCheckoutStep + showCheckoutStep parity: validate the move,
     *  toast the same errors the web wizard toasts, then advance. */
    fun canGoNext(target: Int): Boolean {
        if (target > step) {
            if (target >= 2 && cart.isEmpty()) return false
            if (target >= 4 && payment == "credit" && customerName.isBlank()) return false
        }
        return true
    }

    fun navigateToStep(target: Int) {
        val t = target.coerceIn(1, 4)
        if (!canGoNext(t)) {
            if (t >= 2 && cart.isEmpty()) toast("cartEmpty".t(lang))
            else if (t >= 4 && payment == "credit") toast("noCustomerCredit".t(lang))
            return
        }
        step = t
        focusManager.clearFocus()
        snackbarScope.launch { scrollState.scrollTo(0) }
    }

    /** Opens the GCash app directly if installed; falls back to https://www.gcash.com. */
    fun openGcashApp(total: Double) {
        val pm = smsContext.packageManager
        try {
            pm.getLaunchIntentForPackage("com.globe.gcash.android")?.let {
                smsContext.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            }
            throw Exception("GCash not installed")
        } catch (_: Exception) {
            try {
                smsContext.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://www.gcash.com"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .addCategory(Intent.CATEGORY_BROWSABLE)
                )
            } catch (_: Exception) {
                // No handler at all — the "GCash — ₱..." toast is the fallback.
            }
        }
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
            if (payment == "gcash") {
                // Web openGcashPayment parity: open GCash with the total, toast
                // "GCash — ₱X.XX" (the saleCompleted toast follows in the queue).
                openGcashApp(cartTotal)
                toast("payGcash".t(lang) + " — ₱" + String.format("%,.2f", cartTotal))
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
    // Web closeSaleSheet parity: step back through wizard before leaving entirely
    fun leave() {
        if (step > 1) {
            navigateToStep(step - 1)
            return
        }
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

                    // Progress stepper (always visible — web checkout-stepper parity)
                    CheckoutStepper(step, lang)
                    Spacer(modifier = Modifier.height(16.dp))

                    // ── STEP 1 — Select Products (web checkoutStep1 parity) ──
                    if (step == 1) {
                    // ── Product search ──
                    Text(
                        "addSpecificSale".t(lang),
                        style = MaterialTheme.typography.labelMedium,
                        color = Gray500
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
                        lang = lang,
                        // Stage 3 parity: the web highlights #checkoutSearchControl
                        // (the whole search control), not just the label above it.
                        modifier = Modifier.tutorialHighlight("checkoutSearch", highlightState)
                    )

                    // Product suggestions — filtered by drill-down (subcategory ?: category) ∩ search.
                    // Shows when a category/subcategory is active even with empty query.
                    // Out-of-stock rows greyed and unselectable (v2.58).
                    val view = LocalView.current
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
                                                    performHapticFeedback(view)
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

                    // Selected product display (web v2.63 selectedProductDisplay parity):
                    // name, category - subcategory + brand/unit, price, a stock
                    // badge (ok/low/out) and a clear (x) button that deselects the
                    // product and clears the search input.
                    SelectedProductDisplay(
                        product = selectedProduct,
                        onClear = {
                            selectedProductId = -1
                            productQuery = ""
                            quantity = 1
                            qtyText = "1"
                            showSuggestions = false
                        },
                        lang = lang
                    )

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

                    // Step 1 nav — web btnStep1Next parity (disabled while cart empty)
                    WizardNavRow(
                        prevLabel = "close".t(lang),
                        onPrev = { leave() },
                        nextLabel = "next".t(lang),
                        onNext = { navigateToStep(2) },
                        nextEnabled = cart.isNotEmpty()
                    )
                    } // end step 1

                    // ── STEP 2 — Review Cart (web checkoutStep2 parity) ──
                    if (step == 2) {
                    Text(
                        "reviewCartTitle".t(lang),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Gray700
                    )
                    Spacer(modifier = Modifier.height(8.dp))
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
                        Text(
                            "cartEmptyHint".t(lang),
                            style = MaterialTheme.typography.bodySmall,
                            color = Gray400
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

                    // Step 2 total (live) — web cartStep2Total parity
                    SaleTotalCard(cartTotal, lang)

                    Spacer(modifier = Modifier.height(20.dp))

                    // Step 2 nav — web btnStep2Next parity (disabled while cart empty)
                    WizardNavRow(
                        prevLabel = "prev".t(lang),
                        onPrev = { navigateToStep(1) },
                        nextLabel = "next".t(lang),
                        onNext = { navigateToStep(3) },
                        nextEnabled = cart.isNotEmpty()
                    )
                    } // end step 2

                    // ── STEP 3 — Select Payment (web checkoutStep3 parity) ──
                    // Complete-button gate shared with Step 4 — declared here so
                    // both sibling step blocks can read it (same page-level state
                    // backs both steps on the web too).
                    val utangReady = payment != "credit" || (isNameValid(customerName) && isPhoneValid(customerPhone))
                    if (step == 3) {
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
                            label = "payGcash".t(lang),
                            selected = payment == "gcash",
                            onClick = { viewModel.setSalePayment("gcash") },
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

                    // GCash hint (web gcashHint parity — shown on step 3 here, step 4 confirm below)
                    if (payment == "gcash") {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "gcashHint".t(lang),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = Green600
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

                    // ── STEP 4 is below — the wizard owns its Total + Complete ──
                    // (utangReady above is shared with Step 4's Complete button.)
                    // Step 3 nav — web btnStep3Next parity (name gate handled in navigateToStep)
                    Spacer(modifier = Modifier.height(20.dp))
                    WizardNavRow(
                        prevLabel = "prev".t(lang),
                        onPrev = { navigateToStep(2) },
                        nextLabel = "next".t(lang),
                        onNext = { navigateToStep(4) },
                        nextEnabled = payment != "credit" || customerName.isNotBlank()
                    )
                    } // end step 3

                    // ── STEP 4 — Confirm Sale (web checkoutStep4 parity) ──
                    if (step == 4) {
                    ConfirmSummaryCard(
                        cart = cart,
                        lang = lang,
                        payment = payment,
                        customerName = customerName,
                        customerPhone = customerPhone,
                        cartTotal = cartTotal
                    )

                    Spacer(modifier = Modifier.height(12.dp))

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
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(
                            onClick = { navigateToStep(3) },
                            modifier = Modifier.weight(0.5f).heightIn(min = 50.dp),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Text("prev".t(lang), maxLines = 1)
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
                    } // end step 4
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

/** 4-dot progress stepper + step label — web checkout-stepper parity. */
@Composable
private fun CheckoutStepper(step: Int, lang: String) {
    val labels = listOf("stepProducts", "stepCart", "stepPayment", "stepConfirm")
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            for (i in 1..4) {
                val done = i < step
                val active = i == step
                Surface(
                    shape = CircleShape,
                    color = when {
                        active || done -> Green600
                        else -> Gray200
                    }
                ) {
                    Box(
                        modifier = Modifier.size(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (done) "✓" else "$i",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (active || done) Color.White else Gray500
                        )
                    }
                }
                if (i < 4) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 4.dp)
                            .height(2.dp)
                            .background(if (i < step) Green600 else Gray200)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            labels[step - 1].t(lang),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = Gray600
        )
    }
}

/** Back / Next button row at the bottom of each wizard step. */
@Composable
private fun WizardNavRow(
    prevLabel: String,
    onPrev: () -> Unit,
    nextLabel: String,
    onNext: () -> Unit,
    nextEnabled: Boolean = true
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedButton(
            onClick = onPrev,
            modifier = Modifier.weight(0.5f).heightIn(min = 50.dp),
            shape = RoundedCornerShape(12.dp),
            contentPadding = PaddingValues(horizontal = 8.dp)
        ) {
            Text(prevLabel, maxLines = 1)
        }
        Button(
            onClick = onNext,
            enabled = nextEnabled,
            modifier = Modifier
                .weight(1.5f)
                .heightIn(min = 50.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Green600),
            contentPadding = PaddingValues(horizontal = 8.dp)
        ) {
            Text(nextLabel, fontWeight = FontWeight.Bold, maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

/** Green total card — web sale-total-display parity (steps 2 + 4). */
@Composable
private fun SaleTotalCard(cartTotal: Double, lang: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Green50)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("saleTotalLabel".t(lang), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "₱${String.format("%,.2f", cartTotal)}",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Green600
            )
        }
    }
}

/** Step-4 review card — web updateConfirmSummary parity. */
@Composable
private fun ConfirmSummaryCard(
    cart: List<AppViewModel.CartLine>,
    lang: String,
    payment: String,
    customerName: String,
    customerPhone: String,
    cartTotal: Double
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("confirmSummary".t(lang), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            if (cart.isEmpty()) {
                Text("confirmEmpty".t(lang), style = MaterialTheme.typography.bodySmall, color = Gray400)
            } else {
                cart.forEach { line ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "${line.name} × ${line.qty}",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "₱${String.format("%,.2f", line.subtotal)}",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    HorizontalDivider(color = Gray100)
                }
                Spacer(modifier = Modifier.height(8.dp))
                val payLabel = when (payment) {
                    "gcash" -> "payGcash".t(lang)
                    "credit" -> "payCredit".t(lang)
                    else -> "payCash".t(lang)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("confirmPaymentLabel".t(lang), style = MaterialTheme.typography.bodySmall, color = Gray500)
                    Text(payLabel, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                }
                if (payment == "credit" && customerName.isNotBlank()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("confirmCustomerLabel".t(lang), style = MaterialTheme.typography.bodySmall, color = Gray500)
                        Text(customerName, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                    }
                    if (customerPhone.isNotBlank()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("confirmPhoneLabel".t(lang), style = MaterialTheme.typography.bodySmall, color = Gray500)
                            Text(customerPhone, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                if (payment == "gcash") {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("gcashHint".t(lang), style = MaterialTheme.typography.bodySmall, color = Green600)
                }
            }
        }
    }
    Spacer(modifier = Modifier.height(12.dp))
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Green50)
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("saleTotalLabel".t(lang), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "₱${String.format("%,.2f", cartTotal)}",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Green600
            )
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
