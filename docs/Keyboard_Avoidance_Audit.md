# TindaGo — Keyboard Avoidance (IME) Audit

**Scope:** App-wide IME avoidance for all text-field surfaces (Jetpack Compose, Material 3).
**Target:** Text fields never hidden behind the keyboard; no dead gaps or double padding.
**Baseline bug:** Dev Tools `ModalBottomSheet` — Dev Date/Time Override fields hidden behind the IME.

---

## 1. Inset strategy (4 layers)

| # | Layer | File | What it does |
|---|---|---|---|
| 1 | Manifest | `app/src/main/AndroidManifest.xml` (`android:windowSoftInputMode="adjustResize"`) | Activity window resizes when the IME opens (singleLine targets minSdk 24; `adjustResize` no-ops on API 30+ where edge-to-edge insets take over) |
| 2 | Edge-to-edge | `MainActivity.kt` — `enableEdgeToEdge()` in `onCreate()` | Draws behind system bars; required for inset-based avoidance |
| 3 | Root padding | `MainActivity.kt` root `Surface` — `Modifier.fillMaxSize().imePadding()` | Plain screens shrink above the IME; Compose's automatic `bringIntoView` scrolls focused fields into view |
| 4 | Per-window padding | Sheets' own windows: `DeveloperPanel.kt` + `DebtsScreen.kt` content Columns — `.imePadding()` | `ModalBottomSheet` lives in a separate window and does **not** inherit root insets |
| 5 | Nav-bar inset | `BottomNavBar.kt` (both nav variants) — `navigationBarsPadding()` on the bar's content `Row`, **inside** the `Surface` | Insets the tab row by 24 dp. **Reverted** the `windowInsetsPadding(WindowInsets.navigationBars.exclude(WindowInsets.ime))` experiment (see §6): it was a no-op (the root `imePadding()` already consumes the IME insets) and, on the `Surface`'s own modifier, the padding falls *outside* the painted background, so the bar no longer covered the system nav-bar strip and looked detached |

**Why sheets needed explicit padding:** the root `Surface`'s `imePadding` applies to the activity window only. Material 3 `ModalBottomSheet` renders in its own window with its own insets, so its content never saw the root padding — this was the reported bug.

## 2. Change inventory (all verified by compile)

| File | Line | Change |
|---|---|---|
| `AndroidManifest.xml` | 33 | `android:windowSoftInputMode="adjustResize"` on `<activity>` |
| `MainActivity.kt` | onCreate | `enableEdgeToEdge()` |
| `MainActivity.kt` | 42 | Root `Surface`: `Modifier.fillMaxSize().imePadding()` |
| `ui/components/DeveloperPanel.kt` | 181 | Sheet content `Column`: `.imePadding()` (before `verticalScroll`) |
| `ui/screens/DebtsScreen.kt` | 216 | Payment-sheet content `Column`: `.imePadding()` |
| `ui/navigation/BottomNavBar.kt` | 137-152, 240-255 | `navigationBarsPadding()` **moved** from the `Surface` modifier to the bar's content `Row` (revert of the `windowInsetsPadding(WindowInsets.navigationBars.exclude(WindowInsets.ime))` experiment — see §6) |

All files use `androidx.compose.foundation.layout.*` (or explicit) imports; no new dependencies.

## 3. Audited — no change needed

| Surface / risk | Result |
|---|---|
| 10 `Scaffold` screens (AddStock, Checkout, CustomerDebtDetail, Expenses, NewDebt, ProductDetail, RecordPayment, Restock, Settings, Reports) | Plain content → covered by root `imePadding()` + `bringIntoView` |
| All `AlertDialog` / `DatePickerDialog` (Checkout, Help, MorningCheck, ProductDetail, DeveloperPanel, Expenses, NotificationPrimer) | M3 dialogs handle window insets automatically; verified all fields sit in main content, none inside dialogs |
| Custom `Dialog` / `Popup` / `BottomSheetScaffold` / `ModalDrawer` | Zero uses |
| Double-`imePadding` | Impossible: root padding is activity-window; the 2 sheet sites live in separate sheet windows (disjoint) |
| Multi-line fields | Census: all 47 screen text fields are `singleLine = true` (no `adjustResize` + multi-line resize quirks apply) |
| `CategorySearchField` (Checkout + Stocks) | Fixed 52dp field in normal content flow → root padding covers it; dropdown is a popup with no text inputs |
| Predictive back / IME dismiss | Platform-handled with the canonical `adjustResize` + edge-to-edge config |

## 4. Build validation

| Stage | Command | Result |
|---|---|---|
| 1 | `:app:processDebugMainManifest` + `:app:compileDebugKotlin` | BUILD SUCCESSFUL; merged manifest shows `adjustResize` |
| 2 | `:app:compileDebugKotlin` | BUILD SUCCESSFUL (1m 33s, 2 executed) |
| 3 | `:app:compileDebugKotlin` | BUILD SUCCESSFUL (2m 12s, 2 executed) |
| 4 | `:app:compileDebugKotlin` | BUILD SUCCESSFUL (3m 44s, 2 executed) |
| 5 | `:app:assembleDebug` | BUILD SUCCESSFUL (1m 21s; 4 executed, 33 up-to-date) — full APK assembled |

**Build-environment fix (Stage 5):** `assembleDebug` initially failed at `:app:compileDebugJavaWithJavac` — Gradle toolchain auto-detection picked the VS Code RedHat JRE (21.0.12, no `jlink.exe`) for AGP's `JdkImageTransform` on compileSdk 36. Fixed in `gradle.properties` with `org.gradle.toolchains.auto-detect=false` + `org.gradle.toolchains.auto-download=false` (toolchain = current JVM = the Android Studio JBR already pinned via `org.gradle.java.home`) and a daemon restart (`gradlew --stop`).

## 5. On-device validation checklist (Stage 5)

**Dev Tools sheet:** double-press **Morning** tab → sheet opens → tap **Dev Date Override** / **Dev Time Override** → field auto-scrolls above IME with no manual scrolling; **Set/Clear** buttons reachable; sheet dismissed normally.

| Surface (fields) | Check |
|---|---|
| Setup (2) | Both fields visible while typing; continue button reachable |
| AddStock (10) | Each field scrolls above IME; save button reachable |
| Checkout (3) + search field | Notes field; `CategorySearchField` search input not clipped by sticky header |
| Stocks (search field) | Same as Checkout search |
| CustomerDebtDetail (2) | Fields visible; actions reachable |
| Debts payment sheet (1) | Amount field lifts above IME; **Bayad/Kanselahin** reachable; no gap between sheet and keyboard |
| EveningClosing (1) | Field visible; confirm reachable |
| Expenses (4) | Fields visible; add flow completable |
| Help (1) | Field visible |
| NewDebt (3) | Fields visible; save reachable |
| ProductDetail (1) | Field visible |
| RecordPayment (2) | Fields visible; submit reachable |
| Restock (5) | Fields visible; confirm reachable |
| Settings (12) | Fields visible; every save/clear reachable |
| Bottom nav (all screens with IME open) | No dead gap between nav bar and keyboard; nav returns correctly after IME closes |

**Pass criteria:** on every surface the focused field is fully visible above the IME with no manual scrolling, all action buttons are reachable, and no layout gaps remain after the IME closes.

## 6. Nav-bar inset revert — "detached" bottom bar (fixed)

The `windowInsetsPadding(WindowInsets.navigationBars.exclude(WindowInsets.ime))` experiment in `BottomNavBar.kt` has been **reverted** to `navigationBarsPadding()`.

**Why the experiment could not have fixed the look**

1. It was a **no-op**: the root `MainActivity` `Surface` already applies `.imePadding()`, which consumes the IME insets for the whole app subtree, so `WindowInsets.ime` reads 0 inside the nav bar — `navigationBars.exclude(ime)` is identical to `navigationBars` (24 dp), with the keyboard open or closed.
2. The **real cause** was the `enableEdgeToEdge()` step of this same work. With edge-to-edge the app draws behind the system navigation bar, and the inset padding sat on the `Surface`'s *own* `modifier`. Compose's `Surface` appends `.surface(...)` (background + elevation shadow) **after** the caller's modifier, so that padding was applied *outside* the painted background: the bottom 24 dp (the system nav-bar strip) showed the page background (`#F8FAFC`) with the bar's 8 dp shadow bleeding into it, instead of the bar's `surface` colour (`#FFFFFF`) — i.e. a floating slab with a grey gap under it.

**Fix (container only — no tab/FAB/icon changes)**

`navigationBarsPadding()` now sits on the bar's content `Row` **inside** the `Surface`, so the surface colour paints to the bottom edge of the screen while the tab row keeps its previous position. Applied to both nav variants (3-moment and support).

**On-device verification** (device 720×1612, density 270; tutorial overlay dismissed):

| Check | Before | After |
|---|---|---|
| Bar surface pixels (edge columns x=8 / x=712) | `#FFFFFF` y=1424→1570, then a 41 px (24 dp) strip: `201,202,204` → `246,248,250` (background + shadow) | `#FFFFFF` y=1424→1611 — flush with the bottom edge, no strip |
| Bottom row y=1608 (x=0/120/360/600/719) | background grey | `255,255,255` across the full width |
| Gesture handle (x=360, y≈1588-1594) | drawn over the grey strip | drawn on the white bar |
| Tab icon/label pixel transitions at x=360 | 1456/1457/1458/1461/1462/1464/1465/1486/1516-1518/1526-1528 | **identical** — contents did not move |
| Inventory screen (support nav) | same detached strip | same fix confirmed |

Build: `:app:compileDebugKotlin` + `:app:assembleDebug` → BUILD SUCCESSFUL; APK installed over ADB.
