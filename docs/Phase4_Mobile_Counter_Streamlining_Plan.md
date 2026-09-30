# Phase 4: Mobile Counter Streamlining — Implementation Plan

> Reviewed against live codebase at `git/app/TindaGo_APP/` | Date: 2026-09-28 | Status: FROZEN
> Philosophy: Human effort down, System responsibility up — from manual notebook to smart counter terminal.
> Phase 3 Backoffice complete. Phase 4 scout complete. No code changes yet — this is instructions only.

## Overview

**Goal:** Transform the mobile app from a "digital notebook requiring constant manual input" into a "smart counter terminal that does work for the user" — fast, guided, effortless at point of sale for defense demo.

**Why these 4 tasks:** Highest demo impact, lowest risk, no DB migration, no network dependency, independently reversible. Each uses data the system already has to reduce what the cashier must type, remember, or navigate.

**What changes:** Checkout gets 1-tap bestsellers (4.1), day opens itself and never blocks a sale when stale (4.2), utang suggests recent debtors with inline limit warning (4.3), navigation makes Sell the hero (4.4).

**Full details split into focused files to avoid the previous scramble:**

- [Task 4.1 — Quick-Sell Grid](./Phase4/Task_4.1_Quick_Sell_Grid.md)
- [Task 4.2 — Auto Day Lifecycle](./Phase4/Task_4.2_Auto_Day_Lifecycle.md)
- [Task 4.3 — Smart Utang Defaults](./Phase4/Task_4.3_Smart_Utang.md)
- [Task 4.4 — Navigation Simplification](./Phase4/Task_4.4_Navigation.md)
- [Execution, Rollback & Demo Script](./Phase4/Execution_Rollback_Demo.md)

## Constraints (non-negotiable)

1. No DB migration — stay Room v12 (`AppDatabase.kt` v12). All new queries are additive SELECT only.
2. Don't break `@Preview` — `MainScaffoldPreview`, `MorningCheckScreenPreview`, `DayModeScreenPreview` must compile. `AppViewModel()` keeps no-arg `ViewModel()` ctor (not AndroidViewModel).
3. Don't change `AppViewModel` ctor — use `initRepository(db)` / `initAppSettings(settings)` / `initSync(context, db)` from `NavGraph.kt`.
4. Keep `.t(lang)` i18n — every new string adds `en` + `fil` in `Strings.kt` via `"key".t(lang)`.
5. Offline-first — airplane mode must work. No network calls in 4.1-4.4.
6. One commit per task, independently revertible — `git revert <task>` keeps `assembleDebug` green.

## Findings (live audit 2026-09-28)

- `AppDatabase.kt` v12, 10 entities (products, daily_entries, specific_sales, customer_debts, end_of_day_data, restock_log, debt_payments, debt_transactions, expenses, sms_log). No migration needed.
- `SpecificSaleDao.kt` only has `getAllSales()` + `getSalesByDate()` — missing `GROUP BY description ORDER BY SUM(quantity) DESC LIMIT 8` for 4.1.
- `SpecificSaleEntity.description` is `Product.name` (no FK). Grouping by description is correct.
- `CustomerDebtDao.kt` only has `getAllDebts ORDER BY id DESC` — missing `WHERE remainingBalance > 0 LIMIT 5` for 4.3 (can also just filter in VM).
- `AppRepository.kt` maps Flow<Entity> -> Flow<Domain> via `.map { toDomainModel() }`. No GROUP BY yet.
- `AppViewModel.kt` (~2629L) has `dayOpen/dayDate/dayArchived` (SharedPrefs via AppSettings), `isStaleOpenDay()`, `_saleCart/_salePayment`, `getCheckoutFilteredProducts()` capped at 8, `archiveDaySales()/completeEndOfDay()`.
- `CheckoutScreen.kt` (956L) is multi-item cart with `CategorySearchField`, search/filter, cart ops (`addToCart`, `cartAdjustQty`, etc.), cash/utang toggle.
- `NavGraph.kt` (~759L) blocks DAY/CLOSING/CHECKOUT if `!dayOpen || isStaleOpenDay()` -> redirects to MORNING.
- `MorningCheckScreen.kt` (~601L) shows amber overdue banner + Review dialog when stale, never auto-archives (web v2.35 parity).
- `MainScaffold.kt` has `BottomNavBar(navController, appViewModel, onDevPanelTriggered, onSaleFabClick)` with MORNING/DAY/CLOSING.

## Execution Order

4.1 -> 4.2 -> 4.3 -> 4.4 . After each: `./gradlew :app:assembleDebug` + airplane-mode smoke + preview check + commit. See [Execution_Rollback_Demo.md](./Phase4/Execution_Rollback_Demo.md) for full checklist.

## How to Use This Plan

1. Read this index, then open each Task file in order.
2. Each task file lists: problem, files, step-by-step code, verify, risk.
3. Implement one task, build/smoke/commit, then next. Do NOT bundle.
4. No implementation has started — this is instructions only.

> End of index — open `Phase4/Task_4.1_Quick_Sell_Grid.md` to begin.
