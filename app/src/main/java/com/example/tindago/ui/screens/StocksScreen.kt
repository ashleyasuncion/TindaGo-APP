package com.example.tindago.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.tindago.data.Product
import com.example.tindago.data.StockStatus
import com.example.tindago.data.ml.ForecastBadge
import com.example.tindago.data.ml.ForecastEngine
import com.example.tindago.data.ml.ForecastResult
import com.example.tindago.ui.localization.AppSettings
import com.example.tindago.ui.localization.LocalLanguage
import com.example.tindago.ui.localization.t
import androidx.compose.ui.tooling.preview.Preview
import com.example.tindago.ui.theme.*
import com.example.tindago.ui.theme.TindaGoTheme
import com.example.tindago.ui.components.LocalScreenLazyListState
import com.example.tindago.ui.components.LocalTutorialHighlightState
import com.example.tindago.ui.components.LocalTutorialScrollStateHolder
import com.example.tindago.ui.components.CategorySearchField
import com.example.tindago.ui.components.tutorialHighlight
import java.text.NumberFormat
import java.util.*

/**
 * STOCKS SCREEN — Inventory management.
 * Matches inventory.html from the web prototype exactly.
 */
@Composable
fun StocksScreen(
    viewModel: AppViewModel,
    appSettings: AppSettings,
    onAddStock: () -> Unit,
    onProductClick: (Int) -> Unit = {},
    onLaunchTutorial: (() -> Unit)? = null,
    onStartRestockDay: (() -> Unit)? = null
) {
    val langState = LocalLanguage.current
    val lang = langState.value
    val fmt = remember { NumberFormat.getCurrencyInstance(Locale("en", "PH")) }
    val highlightState = LocalTutorialHighlightState.current

    val products by viewModel.products.collectAsState()
    // Search query for product name/brand search (web inventory.html parity).
    var searchQuery by remember { mutableStateOf("") }
    // Category filter ('' = all). Products without a category only match 'all'
    // (web v2.59 renderManageInventory parity).
    var selectedCategory by remember { mutableStateOf("") }
    // Subcategory within [selectedCategory] ('' = all). index.html Section 2B
    // two-level drill-down; cleared whenever the category changes.
    var selectedSubcategory by remember { mutableStateOf("") }
    var sortByForecast by remember { mutableStateOf(appSettings.sortByForecast) }

    val specificSales by viewModel.specificSales.collectAsState()
    // ── Forecast urgency rank (mirrors web app.js forecastUrgencyRank) ──
    // Lower = more urgent (appears first).
    //   out-of-stock (currentStock <= 0) → rank 0  (always first)
    //   no prediction / collecting / no demand → Int.MAX_VALUE  (always last)
    //   otherwise → predictedDaysUntilOut (ascending — soonest-to-run-out first)
    val forecastUrgencyRank: (ForecastResult) -> Int = { r ->
        when {
            r.currentStock <= 0                   -> 0
            r.predictedDaysUntilOut == null        -> Int.MAX_VALUE
            else                                   -> r.predictedDaysUntilOut
        }
    }

    val filteredProducts = remember(products, searchQuery, selectedCategory, selectedSubcategory, specificSales, viewModel.today, sortByForecast) {
        // Combined text + category filtering using getInventoryFilteredProducts
        // (inventory-dedicated twin of the checkout method; web renderManageInventory).
        val byCategory = if (searchQuery.isNotBlank() || selectedCategory.isNotBlank() || selectedSubcategory.isNotBlank()) {
            viewModel.getInventoryFilteredProducts(searchQuery, selectedCategory, selectedSubcategory)
        } else {
            products
        }
        // Default: stock status → quantity ascending → name (web stkRank parity).
        val statusRank: (Product) -> Int = { when (it.status) {
            StockStatus.OUT_OF_STOCK -> 0
            StockStatus.LOW          -> 1
            StockStatus.PLENTY       -> 2
        } }
        if (!sortByForecast) {
            // Default sort: status rank → quantity → name (mobile + web Stage 1 parity).
            byCategory.sortedWith(compareBy(statusRank, { it.quantity }, { it.name.lowercase() }))
        } else {
            // Forecast sort: rank by learned urgency (out-of-stock first, predicted days
            // ascending), tie-break by name. Applied to the raw filtered list (not the
            // already-sorted base), mirroring web app.js forecastUrgencyRank + name.
            val today = viewModel.today
            byCategory
                .map { p -> Triple(p, ForecastEngine.forecastForProduct(p, specificSales, today).let { r -> forecastUrgencyRank(r) to r }, p.name.lowercase()) }
                .sortedWith(compareBy({ it.second.first }, { it.third }))
                .map { it.first }
        }
    }

    val listState = rememberLazyListState()
    val scrollStateHolder = LocalTutorialScrollStateHolder.current
    LaunchedEffect(listState) { scrollStateHolder.updateLazyListState(listState) }
    val coroutineScope = rememberCoroutineScope()
    // Show back-to-top when scrolled past ~3 items (search control + add + restock buttons).
    val showBackToTop by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 2 }
    }

    CompositionLocalProvider(LocalScreenLazyListState provides listState) {
    Box(modifier = Modifier.fillMaxSize()) {
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 20.dp, end = 20.dp, top = 16.dp)
            .tutorialHighlight("inventoryList", highlightState),

        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {

        // ── Search control (web [search-control] parity) ────────────────
        // Morphing drill-down field: collapsed category button + search icon,
        // expanding into a text search input (index.html Sections 2A+2B+2C).
        // Replaced the old category-filter chip row in Stage 2 for exact web UI
        // parity. Callbacks drive the same selectedCategory / selectedSubcategory
        // state that feeds getInventoryFilteredProducts() in the list filter.
        item {
            CategorySearchField(
                searchQuery = searchQuery,
                onSearchQueryChange = { searchQuery = it },
                selectedCategory = selectedCategory,
                selectedSubcategory = selectedSubcategory,
                onSelectCategory = { cat ->
                    selectedCategory = cat
                    selectedSubcategory = ""
                },
                onSelectSubcategory = { sub ->
                    selectedSubcategory = sub
                },
                onClearCategoryFilter = {
                    selectedCategory = ""
                    selectedSubcategory = ""
                },
                lang = lang,
                modifier = Modifier
                    .tutorialHighlight("stockSearchBar", highlightState),
                // web inventory.html updLabel() parity: default label is t('catAll').
                categoryPlaceholder = "catAll".t(lang)
            )
        }

        // ── Add Stock button ────────────────────────────────────────────
        item {
            Button(
                onClick = onAddStock,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .tutorialHighlight("addStockBtn", highlightState),
                shape = MaterialTheme.shapes.medium
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("addStock".t(lang), style = MaterialTheme.typography.titleSmall)
            }
        }

        // ── Start Restock Day button ────────────────────────────────────
        if (onStartRestockDay != null) {
            item {
                OutlinedButton(
                    onClick = onStartRestockDay,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = MaterialTheme.shapes.medium,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color(0xFFF59E0B) // accent/amber
                    )
                ) {
                    Text("\uD83D\uDE9A Start Restock Day \uD83D\uDE9A", style = MaterialTheme.typography.titleSmall)
                }
            }
        }

        // ── Product list ────────────────────────────────────────────────

        item {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("\uD83D\uDD2E " + "forecastDetailTitle".t(lang), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = Gray700)
                FilterChip(
                    selected = sortByForecast,
                    onClick = {
                        sortByForecast = !sortByForecast
                        appSettings.sortByForecast = sortByForecast
                    },
                    label = {
                        Text(
                            if (sortByForecast) "forecastSortToggle".t(lang)
                            else "\ud83d\udd2e " + "forecastSortOff".t(lang)
                        )
                    }
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        items(filteredProducts, key = { it.id }) { product ->
            val forecast = remember(product.id, specificSales, viewModel.today) { ForecastEngine.forecastForProduct(product, specificSales, viewModel.today) }
            InventoryProductCard(
                product = product,
                fmt = fmt,
                lang = lang,
                onClick = { onProductClick(product.id) },
                forecast = forecast
            )
        }

        if (filteredProducts.isEmpty()) {
            item {
                Box(modifier = Modifier.fillMaxWidth().padding(top = 32.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("\uD83D\uDCE6", style = MaterialTheme.typography.displayMedium)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("noStockItems".t(lang), style = MaterialTheme.typography.bodyMedium, color = Gray400)
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(8.dp)) }
    }

    // Back-to-top FAB
    if (showBackToTop) {
        FloatingActionButton(
            onClick = {
                coroutineScope.launch { listState.animateScrollToItem(0) }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = Gray500,
            elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 2.dp),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 16.dp)
                .size(44.dp)
        ) {
            Text("\u25B2", fontSize = 18.sp, color = Gray500)
        }
    }
    } // Box
    } // CompositionLocalProvider
}

@Composable
private fun InventoryProductCard(
    product: Product,
    fmt: java.text.NumberFormat,
    lang: String,
    onClick: () -> Unit,
    forecast: com.example.tindago.data.ml.ForecastResult? = null,
) {
    val bgColor = when (product.status) {
        StockStatus.PLENTY -> Green100
        StockStatus.LOW -> Amber100
        StockStatus.OUT_OF_STOCK -> Red100
    }
    val statusIcon = when (product.status) {
        StockStatus.PLENTY -> "\u2705"
        StockStatus.LOW -> "\u26A0\uFE0F"
        StockStatus.OUT_OF_STOCK -> "\uD83D\uDD34"
    }

    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = Surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Status icon
            Surface(
                modifier = Modifier.size(40.dp),
                shape = RoundedCornerShape(10.dp),
                color = bgColor
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Text(statusIcon, fontSize = 18.sp)
                }
            }
            Spacer(modifier = Modifier.width(14.dp))

            // Info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    product.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Gray800
                )
                // Brand · size sub-line (web productSubline parity) — only when
                // a brand exists; an empty brand never falls back to size/unit.
                val subline = com.example.tindago.ui.localization.Strings.productSubline(product, lang)
                if (subline.isNotEmpty()) {
                    Text(
                        subline,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = Green700,
                        modifier = Modifier.padding(top = 1.dp)
                    )
                }
                Text(
                    "${product.quantity} ${com.example.tindago.ui.localization.Strings.productUnitLabel(product.unit, lang)} \u2022 ${fmt.format(product.sellingPrice)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Gray500,
                    modifier = Modifier.padding(top = 2.dp)
                )
                if (forecast != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    ForecastBadge(result = forecast, lang = lang, compact = true)
                }
            }

            Spacer(modifier = Modifier.width(4.dp))
            IconButton(onClick = onClick, modifier = Modifier.size(44.dp)) {
                Icon(Icons.Default.ChevronRight, contentDescription = "Details", tint = Gray400, modifier = Modifier.size(22.dp))
            }
        }
    }
}

@Preview(showBackground = true, name = "Stocks Screen")
@Composable
fun StocksScreenPreview() {
    val context = LocalContext.current
    TindaGoTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            StocksScreen(
                viewModel = remember { AppViewModel() },
                appSettings = remember { AppSettings(context) },
                onAddStock = {},
                onProductClick = {}
            )
        }
    }
}
