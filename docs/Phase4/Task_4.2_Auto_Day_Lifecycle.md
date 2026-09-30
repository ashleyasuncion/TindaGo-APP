# Task 4.2 — Automatic Day Lifecycle

## Problem
User must manually open the day every morning and manually resolve stale days. System already knows `currentDate` (StateFlow), `dayOpen`, `dayDate`, `dayArchived` (persisted via AppSettings). This blocks selling for no reason.

## What the System Already Knows
- `AppViewModel.dayOpen`, `dayDate`, `dayArchived` restored in `initAppSettings(appSettings)`
- `currentDate` StateFlow from midnight ticker + `refreshCurrentDate()` in `LifecycleResumeEffect`
- Helpers: `isStaleOpenDay(): Boolean = dayOpen && dayDate != today`, `openDay()`, `closeStaleDayAndStartToday()`, `archiveDaySales()`

## Files to Modify (exact paths)
- `app/src/main/java/com/example/tindago/ui/screens/AppViewModel.kt`
- `app/src/main/java/com/example/tindago/ui/navigation/NavGraph.kt`
- `app/src/main/java/com/example/tindago/ui/screens/MorningCheckScreen.kt`
- `app/src/main/java/com/example/tindago/ui/navigation/MainScaffold.kt` (guard adjustment only)
- `app/src/main/java/com/example/tindago/ui/localization/Strings.kt`

## Step-by-Step Implementation

### 1) ViewModel — ensure two functions exist (no ctor change, no migration)
In `AppViewModel.kt` keep `class AppViewModel : ViewModel()` and add if missing:
```kotlin
fun openDay() {
    if (dayOpen) return
    if (isStaleOpenDay()) return
    dayOpen = true
    dayDate = today // derived from currentDate
    dayArchived = false
    persistDayState() // writes to AppSettings SharedPreferences
}
fun closeStaleDayAndStartToday() {
    if (!isStaleOpenDay()) return
    archiveDaySales()
    dayOpen = true
    dayDate = today
    dayArchived = false
    persistDayState()
}
```

### 2) Auto-open on launch (human effort down)
In `AppViewModel.initAppSettings(settings)` after restoring `dayOpen/dayDate/dayArchived` from prefs:
```kotlin
if (!dayOpen && !isStaleOpenDay()) {
    openDay()
    // brief toast via viewModelScope + snackbar or Toast: "Araw na nagsimula — $today".t(lang) if needed
}
```
Alternative if keeping logic in NavGraph.kt: same check in `LaunchedEffect(dayOpen, dayDate, currentDate)` after `initAppSettings` completes. Prefer VM so previews still safe.

Behavior: user opens app and can immediately sell. No morning interaction needed when clean. Show brief toast `dayStartedToday` = "Araw na nagsimula — [date]" / "Day started — [date]" if you want visible feedback, otherwise silent.

### 3) One-tap stale resolution (never block checkout)
In `MorningCheckScreen.kt` keep amber overdue banner + Review dialog (web v2.35 parity). Add non-blocking affordance:
```kotlin
val lang = LocalLanguage.current
LaunchedEffect(isStaleOpenDay()) {
    if (isStaleOpenDay()) {
        val result = snackbarHost.showSnackbar(
            message = "staleBannerMessage".t(lang), // "Hindi nasara ang kahapon. Isara at mag-simula ngayon?"
            actionLabel = "ok".t(lang),
            duration = SnackbarDuration.Indefinite,
            withDismissAction = true
        )
        if (result == SnackbarResult.ActionPerformed) {
            viewModel.closeStaleDayAndStartToday()
            snackbarHost.showSnackbar("dayStartedToday".t(lang))
        }
    }
}
```
Important: while stale is unresolved, STILL allow `CHECKOUT` navigation and sales. Those sales attribute to today after resolution.

### 4) NavGraph guards — relax checkout only
In `NavGraph.kt` change guards:
- Before (blocks all): `if (!dayOpen || isStaleOpenDay()) redirectToMorning`
- After: keep `DAY` and `CLOSING` blocked, but `CHECKOUT` exempt when stale. Example:
```kotlin
// for Routes.DAY and Routes.CLOSING:
if (!viewModel.dayOpen || viewModel.isStaleOpenDay()) { /* redirect */ }
// for Routes.CHECKOUT:
if (!viewModel.dayOpen && !viewModel.isStaleOpenDay()) { /* only block if truly closed, not just stale */ }
// or simply: if (!viewModel.dayOpen && viewModel.dayDate != today) etc — checkout always allowed when stale
```
Preserve full Morning screen — just don't force it as gate. Review still accessible.

### 5) Strings — add i18n
In `Strings.kt`:
```kotlin
"staleBannerMessage" to "Hindi nasara ang kahapon. Isara at mag-simula ngayon?", // fil
"staleBannerMessage" to "Yesterday was not closed. Close it and start today?",   // en
"dayStartedToday" to "Araw na nagsimula — {date}", // fil
"dayStartedToday" to "Day started — {date}",       // en
"ok" to "OK", // already exists — verify
```

## Verification
1. `./gradlew :app:assembleDebug` — no guard compile error.
2. Fresh `clearAll()` -> launch -> `dayOpen==true`, `dayDate==today`, no snackbar, can go straight to Checkout FAB.
3. Stale sim (set devDateOverride to yesterday, dayOpen=true, relaunch) -> amber banner + indefinite snackbar with OK, tapping OK archives yesterday and opens today; snackbar dismisses, toast shows.
4. While stale BEFORE tapping OK -> navigate to CHECKOUT works, add to cart and `completeSale` succeeds (sales go to today after OK).
5. DAY/CLOSING still blocked while stale (redirect to MORNING) — intentional.
6. Airplane mode -> same flows work.
7. Previews compile: `MorningCheckScreenPreview` uses `AppViewModel()` -> no snackbar auto-trigger.

## Risk
Low-moderate. Logic in NavGraph guards is most sensitive. Mitigation: only CHECKOUT is exempt; keep DAY/CLOSING blocked. Never auto-archive without owner tap (indefinite snackbar, not auto). Test both clean and stale paths. Independently revertible.
