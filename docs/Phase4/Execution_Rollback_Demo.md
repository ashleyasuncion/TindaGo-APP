# Phase 4 — Execution Order, Rollback & Defense Demo

## Execution Order & Dependencies

Implement sequentially. Do NOT bundle. Each task builds + smokes alone.

1. **Task 4.1 Quick-Sell Grid** — independent, no guard changes. Do first.
2. **Task 4.2 Auto Day Lifecycle** — changes NavGraph guards and Morning snackbar. Do second because 4.4 relies on checkout being exempt when stale.
3. **Task 4.3 Smart Utang** — touches same `CheckoutScreen.kt` as 4.1 but different branch (utang vs quick-sell row). Do third to avoid merge conflict with 4.1.
4. **Task 4.4 Navigation** — visual hierarchy only, depends on 4.2 guard behavior for correct FAB demo when stale. Do last.

After each task:
```
./gradlew :app:assembleDebug
# airplane mode ON — smoke the task's verify steps
# check @Preview still compiles (MainScaffoldPreview, MorningCheckScreenPreview, DayModeScreenPreview)
git add -A && git commit -m "feat(phase4-4.X): <task name>"
```

Sequence: `4.1 -> assembleDebug -> smoke -> commit -> 4.2 -> ... -> 4.4`.

## Commit Plan (independently revertible)

- `feat(phase4-4.1): Quick-Sell Grid` — DAO query + repo wrapper + VM quickSellProducts + Checkout LazyRow + mabilisangBenta strings
- `feat(phase4-4.2): Auto Day Lifecycle` — VM openDay/closeStale + auto-open in initAppSettings + Morning snackbar + NavGraph CHECKOUT exempt + strings
- `feat(phase4-4.3): Smart Utang` — VM recentDebtors (filtered) + Checkout chips + inline creditLimitWarning + strings
- `feat(phase4-4.4): Navigation` — MainScaffold hero FAB + muted Morning + optional NavGraph auto-advance (default OFF)

Each `git revert <hash>` must leave `assembleDebug` green.

## Rollback Plan

- **If 4.1 breaks:** `git revert <4.1>` removes `@Query getTopSellingDescriptions`, `getTopSellingNames()`, `_quickSellProducts` + collection, LazyRow in Checkout, and `mabilisangBenta` strings. App returns to search-only checkout.
- **If 4.2 breaks:** `git revert <4.2>` removes `openDay()`/`closeStaleDayAndStartToday()` + auto-open check + snackbar + NavGraph guard relaxation. Day reverts to manual open + blocking stale gate.
- **If 4.3 breaks:** `git revert <4.3>` removes `_recentDebtors` + chips + warning + strings. Utang returns to manual text field only.
- **If 4.4 breaks:** `git revert <4.4>` restores old `BottomNavBar` layout + removes optional LaunchedEffect. Routes unchanged.

No DB migration in any task, so revert never needs downgrade.

## Global Constraints Recap

- Room stays v12, no new Entity/Column.
- Keep `AppViewModel : ViewModel()` no-arg ctor, init via `initRepository`/`initAppSettings`/`initSync`.
- Keep `.t(lang)` for every new string (add en+fil).
- Offline-first: all 4 tasks work in airplane mode.
- Previews must stay green.

## Testing Checklist (global, after all 4)

- `assembleDebug` passes, KSP/Room OK.
- Airplane ON: 4.1 quick-sell updates live, 4.2 auto-open + stale snackbar OK, 4.3 chips + warning, 4.4 FAB works when stale.
- Fresh DB: no crashes, empty quick-sell hidden, no chips, day auto-opens clean.
- Stale sim: banner + snackbar, OK archives, CHECKOUT works before OK, DAY/CLOSING still blocked.
- All 3 previews compile.

## File Map

- `data/local/dao/SpecificSaleDao.kt` — 4.1 query
- `data/local/dao/CustomerDebtDao.kt` — 4.3 optional query (or no change if filtering in VM)
- `data/AppRepository.kt` — 4.1 + 4.3 wrappers
- `ui/screens/AppViewModel.kt` — 4.1 quickSellProducts, 4.2 openDay/closeStale + auto-open, 4.3 recentDebtors
- `ui/screens/CheckoutScreen.kt` — 4.1 LazyRow above search, 4.3 chips + warning inside utang branch
- `ui/screens/MorningCheckScreen.kt` — 4.2 snackbar (keep banner + Review)
- `ui/navigation/MainScaffold.kt` — 4.4 hero FAB + muted Morning
- `ui/navigation/NavGraph.kt` — 4.2 guard relax + 4.4 optional auto-advance
- `ui/screens/DayModeScreen.kt` — 4.4 FAB prominence
- `ui/localization/Strings.kt` — all new keys (mabilisangBenta, staleBannerMessage, dayStartedToday, recentDebtors, creditLimitWarning)

## Defense Demo Script (60-90s)

> "Phase 4 moves effort from the cashier to the system. Everything is offline, no migration, and reversible."

1. **Launch fresh (4.2):** Open app — day auto-opens, toast "Araw na nagsimula — [date]". No manual tap. "Before, every morning required a tap. Now the system knows the date and opens itself."
2. **Stale (4.2):** Simulate yesterday left open — amber banner + snackbar "Hindi nasara ang kahapon. Isara at mag-simula ngayon?" Tap OK — archives and starts today. While stale before OK, tap FAB -> Checkout still works. "We never block a sale because yesterday wasn't closed."
3. **Quick-Sell (4.1):** In Checkout, show "Mabilisang Benta" row — top 8 by volume, tap one chip -> cart qty 1. "The same 10 products sell dozens of times. One tap, not five."
4. **Smart Utang (4.3):** Switch payment to Utang -> recent debtor chips appear above text field, tap "Aling Nena : P320" -> auto-fills. Add items until over limit -> inline amber "Lagpas sa limit ni Aling Nena (limit P500) — ituloy?" appears but does not block. "Suggestions, not gates."
5. **Navigation (4.4):** Show Morning tab muted, FAB Sell is hero 64dp primary. "Counter sees Sell first, Morning is still there for review but not in the way."
6. **Close:** Turn on airplane mode, repeat a sale — all still works. "All local, all offline, all reversible per task."

> End of plan — no code has been changed. Implement 4.1 -> 4.4 sequentially per above.
