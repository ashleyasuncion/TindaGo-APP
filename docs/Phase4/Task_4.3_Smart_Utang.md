# Task 4.3 — Smart Utang Defaults

## Problem
When selecting "Utang" payment, user must search/type customer name every time, even though most utang customers are repeat borrowers. System already has all `CustomerDebtEntity` records.

## What the System Already Knows
- `CustomerDebtEntity`: `id, customerName, amount, remainingBalance, createdAt, creditLimit: Int?, phoneNumber, smsOptIn`
- Domain `CustomerDebt.creditLimit` + `AppSettings.defaultCreditLimit` drive warning
- `CustomerDebtDao.getAllDebts(): Flow<List<CustomerDebtEntity>> ORDER BY id DESC` exists — filtering can be done in ViewModel with no migration.

## Files to Modify
- `app/src/main/java/com/example/tindago/data/local/dao/CustomerDebtDao.kt` (optional query, no migration)
- `app/src/main/java/com/example/tindago/data/AppRepository.kt` (optional wrapper)
- `app/src/main/java/com/example/tindago/ui/screens/AppViewModel.kt`
- `app/src/main/java/com/example/tindago/ui/screens/CheckoutScreen.kt`
- `app/src/main/java/com/example/tindago/ui/localization/Strings.kt`

## Step-by-Step — ViewModel

### Option A — No new DAO query (recommended, lowest risk)
No DAO change. Derive from existing `debts` Flow.

In `AppViewModel.kt` near other StateFlows:
```kotlin
private val _recentDebtors = MutableStateFlow<List<CustomerDebt>>(emptyList())
val recentDebtors: StateFlow<List<CustomerDebt>> = _recentDebtors.asStateFlow()
```
Inside `initRepository(db)` after `debts` collection:
```kotlin
viewModelScope.launch {
    debts.collect { all ->
        _recentDebtors.value = all.filter { it.remainingBalance > 0 }
            .sortedByDescending { it.id }.take(5)
    }
}
```
Zero DB change — satisfies "filter and sort in ViewModel".

### Option B — Additive SELECT (also allowed, still no migration)
If you want a dedicated query, in `CustomerDebtDao.kt`:
```kotlin
@Query("SELECT * FROM customer_debts WHERE remainingBalance > 0 ORDER BY id DESC LIMIT 5")
fun getRecentActiveDebts(): Flow<List<CustomerDebtEntity>>
```
In `AppRepository.kt`:
```kotlin
fun getRecentDebtors(): Flow<List<CustomerDebt>> =
    customerDebtDao.getRecentActiveDebts().map { list -> list.map { it.toDomainModel() } }
```
In `AppViewModel.initRepository(db)`:
```kotlin
viewModelScope.launch { repository?.getRecentDebtors()?.collect { _recentDebtors.value = it } }
```
Pick A or B — do not do both.
## Step-by-Step — UI (CheckoutScreen)

Inside `CheckoutScreen.kt` in the `if (salePayment == "credit")` branch, ABOVE the customer name `OutlinedTextField`:

```kotlin
val recentDebtors by viewModel.recentDebtors.collectAsState()
val lang = LocalLanguage.current
val cartTotal = cart.sumOf { it.amount }
if (recentDebtors.isNotEmpty()) {
    Text("recentDebtors".t(lang), style = MaterialTheme.typography.labelMedium, color = Color.Gray)
    Spacer(Modifier.height(4.dp))
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(recentDebtors, key = { it.id }) { debtor ->
            SuggestionChip(
                onClick = { selectedCustomerName = debtor.customerName },
                label = { Text(debtor.customerName + " : P" + debtor.remainingBalance) }
            )
        }
    }
    Spacer(Modifier.height(8.dp))
}
```

Keep the manual `OutlinedTextField(value = selectedCustomerName, onValueChange = { ... })` below chips — chips are suggestions, not replacements. Tapping a chip auto-fills the field.

Inline credit warning — directly above the "Complete Sale" button, still inside utang branch:

```kotlin
val debts by viewModel.debts.collectAsState()
val selectedDebtor = debts.find { it.customerName == selectedCustomerName.trim() }
val defaultLimit = 500
val effectiveLimit = selectedDebtor?.creditLimit ?: defaultLimit
val isOverLimit = selectedCustomerName.isNotBlank()
    && effectiveLimit > 0
    && selectedDebtor != null
    && (selectedDebtor.remainingBalance + cartTotal) > effectiveLimit
## Strings — add i18n

In `Strings.kt` (both en and fil maps):
```kotlin
"recentDebtors" to "Huling may utang",
"recentDebtors" to "Recent customers with utang",
"creditLimitWarning" to "Lagpas sa limit ni {name} (limit P{limit}) — ituloy?",
"creditLimitWarning" to "Over limit for {name} (limit P{limit}) — continue?",
```

## Verification

1. `./gradlew :app:assembleDebug`
2. Create 6 debts with `remainingBalance > 0` -> only 5 newest chips appear, newest first.
3. Pay one debtor to `remainingBalance == 0` -> chip disappears live.
4. In Checkout, switch to Utang -> chips appear above text field; tapping chip fills field.
5. With cart total that pushes `remainingBalance + cartTotal > effectiveLimit` -> warning appears; Cash hides warning.
6. New customer name (not in debts) -> no warning, complete sale still works.
7. Airplane mode -> chips and warning work offline.
8. Previews compile: `AppViewModel()` -> emptyList -> no chips, no crash.

## Risk

Low. UI-layer using existing data. No schema change. Filtering in VM avoids new DAO. Warning is non-blocking so no checkout regression. Independently revertible.

if (isOverLimit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFD32F2F), modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            "creditLimitWarning".t(lang)
                .replace("{name}", selectedDebtor!!.customerName)
                .replace("{limit}", "P" + effectiveLimit),
            color = Color(0xFFD32F2F),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold
        )
    }
    Spacer(Modifier.height(6.dp))
}
```

Rules: warning is advisory only — never block `completeSale()`. Show only for known debtors over limit. New customers never show warning. `effectiveLimit == 0` means unlimited.

