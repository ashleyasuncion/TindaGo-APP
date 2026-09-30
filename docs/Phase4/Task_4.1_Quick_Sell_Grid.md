# Task 4.1 — Quick-Sell Grid (Mabilisang Benta)

## Problem
Ringing up a sale requires search-and-select for every item, even though sari-sari stores sell the same 10-15 products dozens of times daily. Cashier taps should be 1, not 5.

## What the System Already Knows
`SpecificSaleEntity` has `description` (product name, no FK), `quantity`, `amount`, `timestamp`, `date` plus full `Product` catalog with `quantity` (stock) and `sellingPrice`. History can rank bestsellers by volume.

## Files to Modify (exact paths)
- `app/src/main/java/com/example/tindago/data/local/dao/SpecificSaleDao.kt`
- `app/src/main/java/com/example/tindago/data/AppRepository.kt`
- `app/src/main/java/com/example/tindago/ui/screens/AppViewModel.kt`
- `app/src/main/java/com/example/tindago/ui/screens/CheckoutScreen.kt`
- `app/src/main/java/com/example/tindago/ui/localization/Strings.kt`

## Step-by-Step Implementation

### 1) DAO — add query-only SELECT (no migration, no new Entity)
Inside `SpecificSaleDao` interface, add:
```kotlin
@Query("SELECT description FROM specific_sales GROUP BY description ORDER BY SUM(quantity) DESC LIMIT :limit")
fun getTopSellingDescriptions(limit: Int = 8): Flow<List<String>>
```
Keep existing `getAllSales()` and `getSalesByDate()` untouched. This is query-only, so Room stays at v12.

### 2) Repository — wire Flow
In `AppRepository`, add:
```kotlin
fun getTopSellingNames(limit: Int = 8): Flow<List<String>> =
    specificSaleDao.getTopSellingDescriptions(limit)
```

### 3) ViewModel — expose StateFlow (do NOT change ctor)
In `AppViewModel.kt` (still `class AppViewModel : ViewModel()`):
- Near other `_products`/`_specificSales` StateFlows, add:
```kotlin
private val _quickSellProducts = MutableStateFlow<List<Product>>(emptyList())
val quickSellProducts: StateFlow<List<Product>> = _quickSellProducts.asStateFlow()
```
- Inside existing `initRepository(db)` after `repository = AppRepository(...)` and after `_products` collection is set up, add:
```kotlin
viewModelScope.launch {
    repository?.getTopSellingNames()?.collect { names ->
        val byName = _products.value.associateBy { it.name }
        val ranked = names.mapNotNull { byName[it] }.filter { it.quantity > 0 }
        val padded = if (ranked.size < 8) {
            val remaining = _products.value.filter { it.quantity > 0 && it !in ranked }
                .sortedByDescending { it.quantity }.take(8 - ranked.size)
            ranked + remaining
        } else ranked
        _quickSellProducts.value = padded.take(8)
    }
}
```
Previews never call `initRepository()` so they see `emptyList()` — safe, no crash.

### 4) UI — CheckoutScreen LazyRow above search
In `CheckoutScreen.kt`, directly above the existing `CategorySearchField` / search bar:
```kotlin
val quickSell by viewModel.quickSellProducts.collectAsState()
val lang = LocalLanguage.current
if (quickSell.isNotEmpty()) {
    Text("mabilisangBenta".t(lang), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(6.dp))
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(quickSell, key = { it.id }) { product ->
            SuggestionChip(
                onClick = { viewModel.addToCart(product, 1) },
                label = { Text(product.name + " : P" + product.sellingPrice + " (" + product.quantity + ")") }
            )
        }
    }
    Spacer(Modifier.height(10.dp))
}
```
Rules:
- Hide entire block when `quickSell.isEmpty()` (fresh install -> no empty row).
- Single tap = `addToCart(product, 1)` with existing feedback/snackbar.
- Do NOT replace or move `CategorySearchField`, product grid, or cart list.

### 5) Strings — add i18n (both en and fil)
In `Strings.kt` maps:
```kotlin
"mabilisangBenta" to "Mabilisang Benta", // fil
"mabilisangBenta" to "Quick Sell",       // en
```

## Verification
1. `./gradlew :app:assembleDebug` — KSP passes, Room query compiles.
2. Airplane mode ON -> add 3 sales (repeat same product twice) -> Quick-Sell row updates live, rank 1 is that product.
3. Tap chip -> item appears in cart at qty 1, stock still respected.
4. Fresh DB (no sales) -> row pads with in-stock catalog or stays hidden if no stock, no crash.
5. `@Preview` compiles: `CheckoutScreen` preview uses `AppViewModel()` -> empty row hidden.

## Risk
Low. Read-only SELECT + UI addition only. No schema change, no migration. If a product is deleted, `mapNotNull` drops it silently. Out-of-stock filtered. Independently revertible.
