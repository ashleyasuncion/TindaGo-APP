package com.example.tindago.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
import com.example.tindago.data.Product
import com.example.tindago.data.StockStatus
import com.example.tindago.data.ml.ForecastDetailCard
import com.example.tindago.data.ml.ForecastEngine
import com.example.tindago.ui.components.LocalScreenScrollState
import com.example.tindago.ui.components.LocalTutorialHighlightState
import com.example.tindago.ui.components.LocalTutorialScrollStateHolder
import com.example.tindago.ui.components.TutorialIconButton
import com.example.tindago.ui.components.tutorialHighlight
import com.example.tindago.ui.localization.LocalLanguage
import com.example.tindago.ui.localization.t
import androidx.compose.ui.tooling.preview.Preview
import com.example.tindago.ui.theme.*
import com.example.tindago.ui.theme.TindaGoTheme
import com.example.tindago.data.LocalSnackbarHost
import com.example.tindago.data.LocalSnackbarScope
import java.text.NumberFormat
import java.util.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ProductDetailScreen(
    viewModel: AppViewModel,
    productId: Int = 0,
    onBack: () -> Unit,
    onEdit: (Int) -> Unit = {},
    onRestock: (Int) -> Unit = {},
    onDeleted: () -> Unit = {},
    onTutorialClick: (() -> Unit)? = null
) {
    val langState = LocalLanguage.current
    val lang = langState.value
    val highlightState = LocalTutorialHighlightState.current
    val fmt = remember { NumberFormat.getCurrencyInstance(Locale("en", "PH")) }

    val products by viewModel.products.collectAsState()
    val product = products.find { it.id == productId }

    var deductQty by remember { mutableStateOf("") }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val snackbarHost = LocalSnackbarHost.current
    val snackbarScope = LocalSnackbarScope.current

    Scaffold(
        topBar = {
            // V2.68: subpage rule — Back button present → centered title.
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        product?.name ?: "Product",
                        style = MaterialTheme.typography.titleLarge
                    )
                },
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
        if (product == null) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("📦", style = MaterialTheme.typography.displayLarge)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Product not found", style = MaterialTheme.typography.bodyMedium, color = Gray400)
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedButton(onClick = onBack) { Text("back".t(lang)) }
                }
            }
            return@Scaffold
        }

        val marginPercent = if (product.costPrice > 0) {
            ((product.sellingPrice - product.costPrice) / product.costPrice * 100).toInt()
        } else 0

        val statusColor = when (product.status) {
            StockStatus.PLENTY -> Green600
            StockStatus.LOW -> Amber600
            StockStatus.OUT_OF_STOCK -> Red600
        }

        val pdScrollState = rememberScrollState()
        val scrollStateHolder = LocalTutorialScrollStateHolder.current
        LaunchedEffect(pdScrollState) { scrollStateHolder.updateScrollState(pdScrollState) }
        CompositionLocalProvider(LocalScreenScrollState provides pdScrollState) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(pdScrollState)
        ) {
            // ── Out-of-stock banner ──────────────────────────────────────
            if (product.status == StockStatus.OUT_OF_STOCK) {
            Card(
                modifier = Modifier.fillMaxWidth().tutorialHighlight("pdStockAlert", highlightState),
                colors = CardDefaults.cardColors(containerColor = Red50),
                shape = MaterialTheme.shapes.medium
            ) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = Red600)
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                "criticalStockAlert".t(lang),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = Red700
                            )
                            Text("criticalAlertDesc".t(lang), style = MaterialTheme.typography.bodySmall, color = Red600)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // ── Stock info card ──────────────────────────────────────────
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Surface),
                shape = MaterialTheme.shapes.medium,
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // Stock quantity
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            modifier = Modifier.size(56.dp),
                            shape = MaterialTheme.shapes.medium,
                            color = statusColor.copy(alpha = 0.12f)
                        ) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                Text(
                                    "${product.quantity}",
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = statusColor
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text("stockLabel".t(lang), style = MaterialTheme.typography.bodySmall, color = Gray500)
                            Text(product.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(
                                product.unit,
                                style = MaterialTheme.typography.bodySmall,
                                color = Gray400
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(color = Gray100)
                    Spacer(modifier = Modifier.height(12.dp))

                    // ── Product identity tags (web v2.59 pd-tag parity) ──
                    // Category, unit/measure, brand (when available), package size.
                    // Each tag reads from its OWN property — an empty brand must
                    // never display another field's value (v2.59 cleanup).
                    val identityTags = buildList {
                        if (product.category.isNotBlank())
                            add(com.example.tindago.ui.localization.Strings.productCategoryLabel(product.category, lang))
                        add(com.example.tindago.ui.localization.Strings.productUnitLabel(product.unit, lang))
                        if (product.brand.isNotBlank()) add(product.brand)
                        if (product.packageSize.isNotBlank()) add(product.packageSize)
                    }
                    if (identityTags.isNotEmpty()) {
                        androidx.compose.foundation.layout.FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(bottom = 12.dp)
                        ) {
                            identityTags.forEach { tag ->
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = Green50,
                                    modifier = Modifier.wrapContentSize()
                                ) {
                                    Text(
                                        tag,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Medium,
                                        color = Green800,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Pricing details
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("costPrice".t(lang), style = MaterialTheme.typography.bodySmall, color = Gray500)
                            Text(fmt.format(product.costPrice), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("sellPrice".t(lang), style = MaterialTheme.typography.bodySmall, color = Gray500)
                            Text(fmt.format(product.sellingPrice), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Green600)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("profitMargin".t(lang), style = MaterialTheme.typography.bodySmall, color = Gray500)
                            Text(
                                if (marginPercent > 0) "+$marginPercent%" else "--",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (marginPercent >= 15) Green600 else if (marginPercent > 0) Amber600 else Gray400
                            )
                        }
                    }
                }
            }

            // ── Forecast (Level 1 Offline ML) ──
            val specificSales by viewModel.specificSales.collectAsState()
            val forecast = remember(product?.id, specificSales, viewModel.today) {
                product?.let { ForecastEngine.forecastForProduct(it, specificSales, viewModel.today) }
            }
            if (forecast != null) {
                ForecastDetailCard(result = forecast, lang = lang, onRestock = { onRestock(product!!.id) })
                Spacer(modifier = Modifier.height(16.dp))
            }
            Spacer(modifier = Modifier.height(16.dp))

            // ── Deduct stock ─────────────────────────────────────────────
            if (product.quantity > 0) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Surface),
                    shape = MaterialTheme.shapes.medium,
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("deductStock".t(lang), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = deductQty,
                                onValueChange = { deductQty = it.filter { c -> c.isDigit() } },
                                placeholder = { Text("1") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f),
                                shape = MaterialTheme.shapes.medium
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Button(
                                onClick = {
                                    val qty = deductQty.toIntOrNull() ?: 1
                                    if (qty > 0 && qty <= product.quantity) {
                                        viewModel.deductStock(product.id, qty)
                                        deductQty = ""
                                    }
                                },
                                enabled = deductQty.toIntOrNull()?.let { it > 0 && it <= product.quantity } ?: false,
                                colors = ButtonDefaults.buttonColors(containerColor = Green600),
                                shape = MaterialTheme.shapes.medium,
                                modifier = Modifier.height(56.dp)
                            ) {
                                Text("sold".t(lang), style = MaterialTheme.typography.titleSmall)
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // ── Action buttons ───────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .tutorialHighlight("pdActions", highlightState),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = { onEdit(product.id) },
                    modifier = Modifier.weight(1f).height(52.dp),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("edit".t(lang))
                }
                Button(
                    onClick = { onRestock(product.id) },
                    modifier = Modifier.weight(1f).height(52.dp),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("restock".t(lang))
                }
            }

            // ── Delete button ────────────────────────────────────────────
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                onClick = { showDeleteConfirm = true },
                modifier = Modifier.fillMaxWidth().height(48.dp).tutorialHighlight("pdDeleteBtn", highlightState),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Red600),
                shape = MaterialTheme.shapes.medium
            ) {
                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Delete Product")
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
    }




    // ── Delete Confirmation Dialog (Stage 5) ─────────────────────────
    if (showDeleteConfirm && product != null) {
        val productToDelete = product
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = Red600) },
            title = { Text("areYouSure".t(lang)) },
            text = { Text("Delete \"${productToDelete.name}\"? This action cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteProduct(productToDelete.id)
                        showDeleteConfirm = false
                        snackbarScope.launch {
                            snackbarHost.showSnackbar("${productToDelete.name} deleted.")
                        }
                        onDeleted()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Red600)
                ) { Text("confirm".t(lang)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("cancel".t(lang)) }
            }
        )
    }
}

@Preview(showBackground = true, name = "Product Detail Screen")
@Composable
fun ProductDetailScreenPreview() {
    TindaGoTheme {
        ProductDetailScreen(
            viewModel = remember { AppViewModel() },
            productId = 1,
            onBack = {},
            onEdit = {},
            onRestock = {}
        )
    }
}