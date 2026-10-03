# Task 4.4 — Counter Navigation Simplification

## Problem
Bottom nav (Morning/Day/Closing) exposes back-office complexity to the counter user. For an elderly store owner at the counter, the primary action must be selling, not navigating rituals.

## What the System Already Knows
- `MainScaffold.kt` has `BottomNavBar(navController, appViewModel, onDevPanelTriggered, onSaleFabClick)` with MORNING/DAY/CLOSING tabs
- `NavGraph.kt` has 16 routes, guards that redirect when `!dayOpen || isStaleOpenDay()`
- `DayModeScreen.kt` has FAB -> CHECKOUT, plus secondary actions (today summary, close day)
- Task 4.2 already relaxes CHECKOUT guard when stale

## Files to Modify
- `app/src/main/java/com/example/tindago/ui/navigation/MainScaffold.kt`
- `app/src/main/java/com/example/tindago/ui/screens/DayModeScreen.kt`
- `app/src/main/java/com/example/tindago/ui/navigation/NavGraph.kt` (optional default landing)
- `app/src/main/java/com/example/tindago/ui/localization/Strings.kt` (if new labels)

## Step-by-Step — MainScaffold (visual hierarchy only)

Do NOT remove any screens or routes. Keep MORNING route intact — just deprioritize it visually.

In `MainScaffold.kt` `BottomNavBar`:

1. Morning tab — muted, not default:
```kotlin
NavigationBarItem(
    selected = currentRoute == Routes.MORNING,
    onClick = { navController.navigate(Routes.MORNING) },
    icon = { Icon(Icons.Default.Coffee, null, modifier = Modifier.size(20.dp)) },
    label = { Text("morning".t(lang), style = MaterialTheme.typography.labelSmall) },
    // optional: colors = NavigationBarItemDefaults.colors(unselectedIconColor = Color.Gray)
)
```
Keep it as third item or move to overflow, but do not delete. Selected state still works for review.

2. Hero FAB for Checkout — most prominent element:
```kotlin
FloatingActionButton(
    onClick = onSaleFabClick, // already wired -> navController.navigate(Routes.CHECKOUT)
    containerColor = MaterialTheme.colorScheme.primary,
    modifier = Modifier.size(64.dp)
) { Icon(Icons.Default.ShoppingCart, null, tint = Color.White, modifier = Modifier.size(28.dp)) }
```
Keep existing `MainScaffold(navController, appViewModel, onDevPanelTriggered, onSaleFabClick)` signature. FAB must work even when stale (Task 4.2 already allows CHECKOUT when stale).

## Step-by-Step — DayModeScreen

In `DayModeScreen.kt`:

- Verify FAB -> CHECKOUT is largest, primary color, centered or docked, above the fold.
- Secondary actions (view today's summary, close day) should be visually subordinate: smaller buttons, cards, or menu items, not competing with FAB.
- No logic change — only hierarchy.

## Step-by-Step — NavGraph (optional, default OFF)

In `NavGraph.kt` after `initAppSettings` restore:

```kotlin
// Optional — default OFF for manual demo. Uncomment to enable auto-advance when clean.
LaunchedEffect(dayOpen, dayDate) {
    if (dayOpen && !viewModel.isStaleOpenDay()
        && navController.currentDestination?.route == Routes.MORNING) {
        // navController.navigate(Routes.DAY) { popUpTo(Routes.MORNING) { inclusive = false } }
    }
}
```

Rules:
- Never auto-advance when `isStaleOpenDay()` is true — let Morning show banner/snackbar.
- Keep commented/flagged so defense can demo manual MORNING -> DAY if they prefer.

## Strings — if needed

Only if FAB needs contentDescription:
```kotlin
"sell" to "Benta", // fil
"sell" to "Sell",  // en
```
Otherwise no new strings. Existing `morning`, `day`, `closing`, `checkout` keys remain.

## Verification

1. `./gradlew :app:assembleDebug` + `MainScaffoldPreview` compiles.
2. Bottom bar: MORNING muted/small, FAB 64dp primary, navigates to CHECKOUT even when stale.
3. DayModeScreen: FAB is visually dominant, secondary actions subordinate.
4. Optional auto-navigate OFF -> launch lands on MORNING; ON -> clean day lands on DAY (stale still stays on MORNING).
5. Airplane mode + `@Preview` pass.
6. No route removed — all 16 routes still navigable.

## Risk

Low. UI hierarchy only. No logic or DB change. Independently revertible by restoring old `BottomNavBar` layout + removing LaunchedEffect.

---

## SUPERSEDED — web-parity reversal (Sell button)

The DayModeScreen in-page "Hero Sell FAB" prescribed in this task was **removed**.
The web app (`day.html`) has no sell button inside the day page content: its Sell
button is the centre FAB of the bottom nav (`#navSale` -> `openSaleSheet()`), which
sits between the Morning and Close tabs and carries the cart icon + "Sell"/"Benta"
label. The mobile app already carries that port in `BottomNavBar.kt`, so the
`DayModeScreen` duplicate was dropped to restore web parity and leave exactly one
Sell entry point.

Changes:
- `DayModeScreen.kt` — removed the hero FAB `Box`/`FloatingActionButton` plus its
  `Text("sell")` label, the now-unused `onOpenSaleSheet` parameter, the
  `onOpenSaleSheet` preview argument, and the `icons.filled.ShoppingCart` import.
- `NavGraph.kt` — removed the `onOpenSaleSheet = openSaleSheet` argument.
- `BottomNavBar.kt` — **unchanged**: the Day-only centre FAB
  (64dp primary circle, `ShoppingCart` 28dp, `tutorialHighlight("sellFab")`,
  `onSaleFabClick ?: navigate(Routes.CHECKOUT)`) and `MainScaffold`'s
  `onSaleFabClick = openSaleSheet` wiring remain the single Sell entry point.

Result: Day screen renders `Morning | [Sell FAB] | Close` with no sell button in the
page body — matching `day.html`. `tutorialHighlight("sellFab")` now resolves to a
single element (the nav FAB), matching web tutorial step 7 (`#navSale`).

