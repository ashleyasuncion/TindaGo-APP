package com.example.tindago.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navDeepLink
import com.example.tindago.data.notifications.NotificationDeepLinks
import com.example.tindago.TindaGoApp
import com.example.tindago.data.AppRepository
import com.example.tindago.data.LocalSnackbarHost
import com.example.tindago.data.LocalSnackbarScope
import com.example.tindago.data.notifications.NotificationCenter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.example.tindago.ui.MainScaffold
import com.example.tindago.ui.SupportScaffold
import com.example.tindago.ui.components.*
import com.example.tindago.ui.localization.AppSettings
import com.example.tindago.ui.localization.LocalLanguage
import com.example.tindago.ui.localization.t
import com.example.tindago.ui.screens.*

// ── 14-step Main Tutorial (matching web prototype app2.6) ─────────────
// Maps to the Three Moment flow: Morning → Day → Closing → Inventory → Debts → Settings
val tutorialSteps = listOf(
    TutorialStep("tutorial1", "morning"),                                   // Welcome
    TutorialStep("tutorial2", "morning", "morningStockCard"),                 // Stock card
    TutorialStep("tutorial3", "morning", "morningDebtCard"),                  // Debt card
    TutorialStep("tutorial4", "morning", "startDayBtn"),                     // Start the Day
    TutorialStep("tutorial5", "day"),                                        // Welcome to Day Mode
    TutorialStep("tutorial6", "day", "dayStatsGrid"),                       // Stats summary
    TutorialStep("tutorial7", "day", "sellFab"),                            // Sell FAB
    TutorialStep("tutorial8", "closing"),                                    // Closing screen
    TutorialStep("tutorial9", "closing", "closingEarnings"),                // Cost + earnings
    TutorialStep("tutorial10", "closing", "completeDayBtn"),                 // Day Complete
    TutorialStep("tutorial11", "inventory", "stockSearchBar"),              // Inventory search
    TutorialStep("tutorial12", "inventory", "addStockBtn"),                  // Add Stock button
    TutorialStep("tutorial13", "debts", "totalDebtCard"),                   // Debts total
    TutorialStep("tutorial14", "settings", "settingsLanguage")               // Settings
)

@Composable
fun NavGraph(
    navController: NavHostController,
    appSettings: AppSettings
) {
    val context = LocalContext.current

    // Shared ViewModel for the app
    val appViewModel: AppViewModel = viewModel()

    // Initialize Room database
    val app = context.applicationContext as TindaGoApp
    val repository = remember {
        AppRepository(
            productDao = app.database.productDao(),
            dailyEntryDao = app.database.dailyEntryDao(),
            specificSaleDao = app.database.specificSaleDao(),
            customerDebtDao = app.database.customerDebtDao(),
            endOfDayDao = app.database.endOfDayDao(),
            restockLogDao = app.database.restockLogDao(),
            debtPaymentDao = app.database.debtPaymentDao(),
            debtTransactionDao = app.database.debtTransactionDao(),
            expenseDao = app.database.expenseDao(),
            smsLogDao = app.database.smsLogDao()
        )
    }
    LaunchedEffect(Unit) {
        appViewModel.initRepository(repository)
    }

    // Restore persisted day state after the ViewModel is ready
    LaunchedEffect(appSettings) {
        appViewModel.initAppSettings(appSettings)
    }

    // Keep the observable current date in sync whenever the app returns to the
    // foreground — so the Morning overdue banner / Day & Closing guards recompute
    // after a real calendar day passes while the process stays alive (web parity).
    LifecycleResumeEffect(Unit) {
        appViewModel.refreshCurrentDate()
        onPauseOrDispose { }
    }

    // Dev Panel state
    var devPanelVisible by remember { mutableStateOf(false) }
    val showDevPanel: () -> Unit = { devPanelVisible = true }

    // Sale checkout state (web v2.64 parity: the sale bottom sheet was replaced
    // by a standalone checkout page — the Day FAB now navigates to it).
    val openSaleSheet: () -> Unit = { navController.navigate(Routes.CHECKOUT) }

    // Tutorial state
    var tutorialActive by remember { mutableStateOf(false) }
    var tutorialStep by remember { mutableIntStateOf(0) }
    var tutorialReplay by remember { mutableStateOf(false) }
    var pageTutorial by remember { mutableStateOf<PageTutorial?>(null) }

    // Session guard: tutorial only fires on fresh app launch, not every navigation to MORNING.
    // Set true by ANY tutorial launch (auto, onboarding, manual replay, page tutorial) so the
    // MORNING auto-launch guard can never re-fire when navigation returns to Morning after a
    // tutorial completes (duplicate-tutorial bug fix).
    var tutorialLaunchedThisSession by remember { mutableStateOf(false) }

    // Tutorial highlight state — tracks bounds of elements for highlight frames
    val highlightState = remember { TutorialHighlightState() }
    // Shared scroll state holder for tutorial auto-scroll.
    // Screens update this when they compose; the TutorialOverlay reads from it.
    val scrollStateHolder = remember { TutorialScrollStateHolder() }

    fun getCurrentTutorialSteps(): List<TutorialStep> {
        return pageTutorial?.let { pt ->
            val steps = (1..pt.stepCount).map { i ->
                TutorialStep(
                    i18nKey = "${pt.stepsKeyPrefix}$i",
                    page = pt.page,
                    highlightTarget = pt.highlights.getOrNull(i - 1)
                )
            }
            // Append replay hint step if configured
            if (pt.replayHintKey != null) {
                steps + TutorialStep(
                    i18nKey = pt.replayHintKey,
                    page = pt.page,
                    highlightTarget = "headerTutorialBtn"
                )
            } else steps
        } ?: tutorialSteps
    }

    // Shared helpers: map between nav routes and tutorial page names.
    // Used by advanceTutorial(), previousTutorial() and startPageTutorial()
    // so the mapping never drifts between forward/backward/launch navigation.
    fun pageForRoute(route: String): String = when (route) {
        Routes.MORNING -> "morning"
        Routes.DAY -> "day"
        Routes.CLOSING -> "closing"
        Routes.INVENTORY -> "inventory"
        Routes.DEBTS -> "debts"
        Routes.HELP -> "help"
        Routes.SETTINGS -> "settings"
        Routes.ADD_STOCK -> "add_stock"
        Routes.CHECKOUT -> "checkout"
        Routes.NEW_DEBT -> "new_debt"
        Routes.RESTOCK -> "restock"
        Routes.REPORTS -> "reports"
        Routes.EXPENSES -> "expenses"
        Routes.PRODUCT_DETAIL -> "product_detail"
        Routes.CUSTOMER_DEBT_DETAIL -> "customer_debt_detail"
        Routes.RECORD_PAYMENT -> "record_payment"
        else -> "morning"
    }

    fun routeForPage(page: String): String = when (page) {
        "morning" -> Routes.MORNING; "day" -> Routes.DAY; "closing" -> Routes.CLOSING
        "inventory" -> Routes.INVENTORY; "debts" -> Routes.DEBTS
        "help" -> Routes.HELP; "settings" -> Routes.SETTINGS; "add_stock" -> Routes.addStock()
        "checkout" -> Routes.CHECKOUT
        "new_debt" -> Routes.NEW_DEBT; "restock" -> Routes.RESTOCK
        "reports" -> Routes.REPORTS
        "expenses" -> Routes.EXPENSES
        // Arg'd pages have no fixed route id — fall back to their parent list page
        // when launched from Help/Settings (launching from the page itself stays put).
        "product_detail" -> Routes.INVENTORY
        "customer_debt_detail" -> Routes.DEBTS
        "record_payment" -> Routes.DEBTS
        else -> Routes.MORNING
    }

    fun advanceTutorial() {
        val steps = getCurrentTutorialSteps()
        val next = tutorialStep + 1
        if (next >= steps.size) {
            val wasMainTutorial = pageTutorial == null
            tutorialActive = false; tutorialReplay = false; pageTutorial = null
            appSettings.hasCompletedTutorial = true
            // Web v2.40 parity: completing the main tutorial returns to Morning,
            // where the tutorial originally began. Page tutorials finish in place.
            if (wasMainTutorial) {
                navController.navigate(Routes.MORNING) {
                    popUpTo(Routes.MORNING) { saveState = true }
                    launchSingleTop = true
                }
            }
            return
        }
        val nextStep = steps[next]
        val currentRoute = navController.currentDestination?.route ?: ""
        val stepPage = pageForRoute(currentRoute)
        if (nextStep.page != stepPage) {
            val route = routeForPage(nextStep.page)
            tutorialStep = next
            navController.navigate(route) {
                popUpTo(Routes.MORNING) { saveState = true }
                launchSingleTop = true
            }
        } else {
            tutorialStep = next
        }
        // Restock tutorial: trigger step 1→2 transition when advancing to step 6+
        // (index 5+), so the Step 2 UI is rendered before the highlight frame appears.
        if (pageTutorial?.id == "restock" && next >= 5) {
            appViewModel.setRestockStep(2)
        }
    }

    fun previousTutorial() {
        val steps = getCurrentTutorialSteps()
        val prev = tutorialStep - 1
        if (prev < 0) return
        val prevStep = steps[prev]
        val currentRoute = navController.currentDestination?.route ?: ""
        val stepPage = pageForRoute(currentRoute)
        if (prevStep.page != stepPage) {
            val route = routeForPage(prevStep.page)
            tutorialStep = prev
            navController.navigate(route) {
                popUpTo(Routes.MORNING) { saveState = true }
                launchSingleTop = true
            }
        } else {
            tutorialStep = prev
        }
        // Restock tutorial: trigger step 2→1 transition when going back to step 5 or earlier
        // (index 4 or below), so the Step 1 UI is rendered before the highlight frame appears.
        if (pageTutorial?.id == "restock" && prev <= 4) {
            appViewModel.setRestockStep(1)
        }
    }

    fun startTutorial(replay: Boolean = false) {
        pageTutorial = null; tutorialActive = true; tutorialStep = 0; tutorialReplay = replay
        // Mark the tutorial as launched this session the moment it starts, so the MORNING
        // auto-launch guard can't re-trigger when we navigate back to Morning afterwards.
        tutorialLaunchedThisSession = true
        highlightState.clear()
        scrollStateHolder.clear()
    }

    fun endTutorial(returnToMorning: Boolean = false) {
        val wasMainTutorial = pageTutorial == null
        tutorialActive = false; tutorialReplay = false; pageTutorial = null
        appSettings.hasCompletedTutorial = true
        highlightState.clear()
        scrollStateHolder.clear()
        // Web v2.40 parity: finishing the main tutorial returns to Morning.
        // Skip stays in place; page tutorials finish in place.
        if (returnToMorning && wasMainTutorial) {
            navController.navigate(Routes.MORNING) {
                popUpTo(Routes.MORNING) { saveState = true }
                launchSingleTop = true
            }
        }
    }

    fun startPageTutorial(tutorialId: String) {
        // Web parity fix: the "main" tutorial is the full multi-page 14-step
        // flow (real page transitions + highlights). Route it through
        // startTutorial() so a manual launch from Settings/Help behaves
        // identically to the auto-launched main tutorial — instead of the
        // page-tutorial generator, which would pin every step to one page
        // with no highlight targets (static overlay bug).
        if (tutorialId == "main") {
            startTutorial(replay = true)
            navController.navigate(Routes.MORNING) {
                popUpTo(Routes.MORNING) { saveState = true }
                launchSingleTop = true
            }
            return
        }
        val tut = pageTutorials.find { it.id == tutorialId }
        if (tut != null) {
            pageTutorial = tut; tutorialActive = true; tutorialStep = 0; tutorialReplay = true
            // Page tutorials must also mark the session guard — otherwise returning to
            // Morning after one finishes would auto-start the main tutorial.
            tutorialLaunchedThisSession = true
            // Launching from the tutorial's own page (the header "?" button) stays in
            // place — only navigate when launched from elsewhere (Help/Settings selector).
            val currentRoute = navController.currentDestination?.route ?: ""
            if (pageForRoute(currentRoute) != tut.page) {
                val route = routeForPage(tut.page)
                navController.navigate(route) {
                    popUpTo(Routes.MORNING) { saveState = true }; launchSingleTop = true
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Provide highlight state and scroll state holder to all screen composables
        CompositionLocalProvider(
            LocalTutorialHighlightState provides highlightState,
            LocalTutorialScrollStateHolder provides scrollStateHolder
        ) {
            NavHost(navController = navController, startDestination = Routes.SPLASH) {
            // ── App flow ──────────────────────────────────────────
            composable(Routes.SPLASH) {
                SplashScreen(
                    onNavigateToSetup = { navController.navigate(Routes.SETUP) { popUpTo(Routes.SPLASH) { inclusive = true } } },
                    onNavigateToHome = { navController.navigate(Routes.MORNING) { popUpTo(Routes.SPLASH) { inclusive = true } } }
                )
            }
            composable(Routes.SETUP) {
                SetupScreen(
                    onComplete = {
                        navController.navigate(Routes.MORNING) { popUpTo(Routes.SETUP) { inclusive = true } }
                    },
                    onTutorialReady = {
                        navController.navigate(Routes.DAY) { popUpTo(Routes.SETUP) { inclusive = true } }
                        // Count the onboarding tutorial as launch #1 so the no-skip (mandatory)
                        // tutorial only appears on a clean-slate install; the NEXT relaunch is
                        // then launch #2 and shows the skip button (replay=true). Without this,
                        // launchCount stayed 0 after onboarding and the first relaunch was
                        // misdetected as first-ever (no skip shown again).
                        appSettings.launchCount++
                        startTutorial(false)
                    }
                )
            }

            // ── Three Moments ────────────────────────────────────
            composable(
                route = Routes.MORNING,
                // V2.70: notification deep links (tindago://morning)
                deepLinks = listOf(navDeepLink { uriPattern = NotificationDeepLinks.MORNING })
            ) {
                LaunchedEffect(Unit) {
                    // Tutorial auto-starts only on fresh app launch (not every navigation to MORNING)
                    // Session guard: tutorialLaunchedThisSession prevents re-trigger on nav back to MORNING
                    // First-ever launch: launchCount == 1 → no skip button (isReplay = false)
                    // Subsequent launches: launchCount > 1 → skip button shown (isReplay = true)
                    if (appSettings.hasCompletedSetup && !tutorialActive && !tutorialLaunchedThisSession) {
                        tutorialLaunchedThisSession = true
                        appSettings.launchCount++
                        val isFirstLaunch = appSettings.launchCount == 1
                        startTutorial(replay = !isFirstLaunch)
                    }
                }
                val lang = LocalLanguage.current.value
                val ownerName = appSettings.ownerName
                val greetingText = "pageMorning".t(lang)
                // V2.70: contextual notification primer + permission request, shown
                // once at the high-intent moment of the first "Start the Day" tap.
                var showNotifPrimer by remember { mutableStateOf(false) }
                val notifPermissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { }
                fun startDayFlow() {
                    appViewModel.archiveDaySales()
                    appViewModel.openDay()
                    navController.navigate(Routes.DAY) {
                        popUpTo(Routes.MORNING) { saveState = true }
                        launchSingleTop = true
                    }
                }
                MainScaffold(
                    navController = navController,
                    currentRoute = Routes.MORNING,
                    appViewModel = appViewModel,
                    onDevPanelTriggered = showDevPanel,
                    greeting = greetingText,
                    onTutorialClick = { startPageTutorial("home") },
                    onInventoryClick = { navController.navigate(Routes.INVENTORY) }
                ) {
                    val snackbarHost = LocalSnackbarHost.current
                    val snackbarScope = LocalSnackbarScope.current
                    MorningCheckScreen(
                        viewModel = appViewModel,
                        ownerName = ownerName,
                        onStartDay = {
                            if (Build.VERSION.SDK_INT >= 33 && !appSettings.notifPrimerShown && appSettings.notificationsEnabled) {
                                appSettings.notifPrimerShown = true
                                showNotifPrimer = true
                            } else {
                                startDayFlow()
                            }
                        },
                        onNavigateToClosing = {
                            navController.navigate(Routes.CLOSING)
                        },
                        onEditClosing = {
                            appViewModel.reopenClosing()
                            navController.navigate(Routes.CLOSING)
                        },
                        onCloseStaleDayAndStartToday = {
                            // Close the previous (stale) day, show the confirmation
                            // toast, then enter Day Mode (web: toast + 900ms delay).
                            appViewModel.closeStaleDayAndStartToday()
                            // V2.70: resolving the overdue day cancels its notification.
                            NotificationCenter.cancel(context, NotificationCenter.ID_OVERDUE)
                            snackbarScope.launch {
                                snackbarHost.showSnackbar("overdueArchivedToast".t(lang))
                                delay(900)
                                navController.navigate(Routes.DAY) {
                                    popUpTo(Routes.MORNING) { saveState = true }
                                    launchSingleTop = true
                                }
                            }
                        },
                        onNavigateToInventory = { navController.navigate(Routes.INVENTORY) },
                        onNavigateToDebts = { navController.navigate(Routes.DEBTS) },
                        onNavigateToSettings = { navController.navigate(Routes.SETTINGS) },
                        onNavigateToRestock = { navController.navigate(Routes.RESTOCK) },
                        onLaunchTutorial = { startPageTutorial("home") }
                    )
                }
                if (showNotifPrimer) {
                    NotificationPrimerDialog(
                        onAllow = {
                            showNotifPrimer = false
                            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            startDayFlow()
                        },
                        onNotNow = {
                            showNotifPrimer = false
                            startDayFlow()
                        }
                    )
                }
            }

            composable(Routes.DAY) {
                val lang = LocalLanguage.current.value
                val greetingText = "pageDay".t(lang)
                // Re-key the entry guard on the observable date so a midnight rollover
                // while the user is on Day Mode re-runs the stale-day check.
                val currentDate by appViewModel.currentDate.collectAsState()
                MainScaffold(
                    navController = navController,
                    currentRoute = Routes.DAY,
                    appViewModel = appViewModel,
                    onDevPanelTriggered = showDevPanel,
                    onSaleFabClick = openSaleSheet,
                    greeting = greetingText,
                    onTutorialClick = { startPageTutorial("sales") },
                    onInventoryClick = { navController.navigate(Routes.INVENTORY) }
                ) {
                    val snackbarHost = LocalSnackbarHost.current
                    val snackbarScope = LocalSnackbarScope.current
                    // Entry guard (web navigateToDayMode parity): Day Mode requires
                    // an open, non-stale day. Skipped during the tutorial flow, which
                    // visits Day Mode before the day is started.
                    LaunchedEffect(currentDate) {
                        if (!tutorialActive && (!appViewModel.dayOpen || appViewModel.isStaleOpenDay())) {
                            val msg = if (appViewModel.isStaleOpenDay())
                                "overdueRedirect".t(lang) else "dayNotOpen".t(lang)
                            snackbarScope.launch { snackbarHost.showSnackbar(msg) }
                            navController.navigate(Routes.MORNING) {
                                popUpTo(0) { inclusive = true }
                            }
                        }
                    }
                    DayModeScreen(
                        viewModel = appViewModel,
                        onCloseStore = {
                            // Guard at source (web showClosingScreen parity).
                            if (appViewModel.dayOpen && !appViewModel.isStaleOpenDay()) {
                                navController.navigate(Routes.CLOSING)
                            } else {
                                val msg = if (appViewModel.isStaleOpenDay())
                                    "overdueRedirect".t(lang) else "dayNotOpen".t(lang)
                                snackbarScope.launch { snackbarHost.showSnackbar(msg) }
                            }
                        },
                        onNavigateToInventory = { navController.navigate(Routes.INVENTORY) },
                        onNavigateToExpenses = { navController.navigate(Routes.EXPENSES) },
                        onOpenSaleSheet = openSaleSheet,
                        onLaunchTutorial = { startPageTutorial("sales") }
                    )
                }
            }

            // ── Standalone Checkout (web v2.64 parity — replaces the sale sheet overlay) ──
            composable(Routes.CHECKOUT) {
                val lang = LocalLanguage.current.value
                // Entry guard: checkout requires an open, non-stale day. Skipped
                // during the tutorial flow (same pattern as Day Mode). The message
                // is routed through the screen (it owns its snackbar host).
                var blockedMessage by remember { mutableStateOf<String?>(null) }
                LaunchedEffect(Unit) {
                    if (!tutorialActive && (!appViewModel.dayOpen || appViewModel.isStaleOpenDay())) {
                        blockedMessage = if (appViewModel.isStaleOpenDay())
                            "overdueRedirect".t(lang) else "dayNotOpen".t(lang)
                    }
                }
                CheckoutScreen(
                    viewModel = appViewModel,
                    onBack = { navController.popBackStack() },
                    onTutorialClick = { startPageTutorial("checkout") },
                    blockedMessage = blockedMessage
                )
            }

            composable(
                route = Routes.CLOSING,
                // V2.70: closing-reminder deep link (tindago://closing)
                deepLinks = listOf(navDeepLink { uriPattern = NotificationDeepLinks.CLOSING })
            ) {
                val lang = LocalLanguage.current.value
                val greetingText = "pageClosing".t(lang)
                // Re-key the entry guard on the observable date (midnight rollover).
                val currentDate by appViewModel.currentDate.collectAsState()
                MainScaffold(
                    navController = navController,
                    currentRoute = Routes.CLOSING,
                    appViewModel = appViewModel,
                    onDevPanelTriggered = showDevPanel,
                    greeting = greetingText,
                    onTutorialClick = { startPageTutorial("eod") },
                    onInventoryClick = { navController.navigate(Routes.INVENTORY) }
                ) {
                    val snackbarHost = LocalSnackbarHost.current
                    val snackbarScope = LocalSnackbarScope.current
                    // Entry guard (web closing-page parity): Closing requires an open,
                    // non-stale day. Skipped during the tutorial flow.
                    LaunchedEffect(currentDate) {
                        if (!tutorialActive && (!appViewModel.dayOpen || appViewModel.isStaleOpenDay())) {
                            val msg = if (appViewModel.isStaleOpenDay())
                                "overdueRedirect".t(lang) else "dayNotOpen".t(lang)
                            snackbarScope.launch { snackbarHost.showSnackbar(msg) }
                            navController.navigate(Routes.MORNING) {
                                popUpTo(0) { inclusive = true }
                            }
                        }
                    }
                    EveningClosingScreen(
                        viewModel = appViewModel,
                        onComplete = {
                            navController.navigate(Routes.MORNING) {
                                popUpTo(Routes.MORNING) { inclusive = true }
                            }
                        },
                        onBackToDay = { navController.popBackStack() },
                        onNavigateToInventory = { navController.navigate(Routes.INVENTORY) },
                        onNavigateToDebts = { navController.navigate(Routes.DEBTS) },
                        onLaunchTutorial = { startPageTutorial("eod") }
                    )
                }
            }

            // ── Support screens ──────────────────────────────────
            composable(
                route = Routes.INVENTORY,
                // V2.70: stock-alert deep link (tindago://inventory)
                deepLinks = listOf(navDeepLink { uriPattern = NotificationDeepLinks.INVENTORY })
            ) {
                val lang = LocalLanguage.current.value
                // V2.70: opening Inventory acknowledges (cancels) stock alerts for now.
                LaunchedEffect(Unit) {
                    NotificationCenter.cancel(context, NotificationCenter.ID_STOCK)
                }
                SupportScaffold(
                    navController = navController,
                    currentRoute = Routes.INVENTORY,
                    appViewModel = appViewModel,
                    onDevPanelTriggered = showDevPanel,
                    headerTitle = "inventory".t(lang),
                    onBackClick = { navController.popBackStack() },
                    onTutorialClick = { startPageTutorial("stock") },
                    onSettingsClick = { navController.navigate(Routes.SETTINGS) }
                ) { paddingValues ->
                    StocksScreen(
                        viewModel = appViewModel,
                        onAddStock = { navController.navigate(Routes.addStock()) },
                        onProductClick = { id -> navController.navigate(Routes.productDetail(id)) },
                        onLaunchTutorial = { startPageTutorial("stock") },
                        onStartRestockDay = { navController.navigate(Routes.RESTOCK) }
                    )
                }
            }

            composable(Routes.ADD_STOCK) { backStackEntry ->
                val productId = backStackEntry.arguments?.getString("productId")?.toIntOrNull()
                AddStockScreen(
                    viewModel = appViewModel,
                    productId = productId?.let { if (it >= 0) it else null },
                    defaultMarkup = appSettings.defaultMarkup,
                    defaultLowStockThreshold = appSettings.lowStockThreshold,
                    onBack = { navController.popBackStack() },
                    onSaved = { },
                    onTutorialClick = { startPageTutorial("addProduct") }
                )
            }

            composable(
                route = Routes.DEBTS,
                // V2.70: weekly-digest deep link (tindago://debts)
                deepLinks = listOf(navDeepLink { uriPattern = NotificationDeepLinks.DEBTS })
            ) {
                val lang = LocalLanguage.current.value
                // V2.70: opening Debts acknowledges (cancels) the weekly digest.
                LaunchedEffect(Unit) {
                    NotificationCenter.cancel(context, NotificationCenter.ID_DIGEST)
                }
                SupportScaffold(
                    navController = navController,
                    currentRoute = Routes.DEBTS,
                    appViewModel = appViewModel,
                    onDevPanelTriggered = showDevPanel,
                    headerTitle = "debts".t(lang),
                    onBackClick = { navController.popBackStack() },
                    onTutorialClick = { startPageTutorial("debt") },
                    onSettingsClick = { navController.navigate(Routes.SETTINGS) }
                ) {
                    DebtsScreen(
                        viewModel = appViewModel,
                        onNewDebt = { navController.navigate(Routes.NEW_DEBT) },
                        onDebtClick = { debtId -> navController.navigate(Routes.customerDebtDetail(debtId)) },
                        onNavigateToReports = { navController.navigate(Routes.MORNING) },
                        onLaunchTutorial = { startPageTutorial("debt") }
                    )
                }
            }

            composable(Routes.NEW_DEBT) {
                NewDebtScreen(
                    viewModel = appViewModel,
                    onBack = { navController.popBackStack() },
                    onSaved = { },
                    onTutorialClick = { startPageTutorial("newDebt") }
                )
            }

            composable(Routes.CUSTOMER_DEBT_DETAIL) { backStackEntry ->
                val debtId = backStackEntry.arguments?.getString("debtId")?.toIntOrNull() ?: 0
                CustomerDebtDetailScreen(
                    viewModel = appViewModel,
                    debtId = debtId,
                    onBack = { navController.popBackStack() },
                    onRecordPayment = { id -> navController.navigate(Routes.recordPayment(id)) },
                    onTutorialClick = { startPageTutorial("customerDebtDetail") }
                )
            }

            composable(Routes.RECORD_PAYMENT) { backStackEntry ->
                val debtId = backStackEntry.arguments?.getString("debtId")?.toIntOrNull() ?: 0
                RecordPaymentScreen(
                    viewModel = appViewModel,
                    debtId = debtId,
                    onBack = { navController.popBackStack() },
                    onPaymentSaved = { navController.popBackStack() },
                    onTutorialClick = { startPageTutorial("recordPayment") }
                )
            }

            composable(Routes.PRODUCT_DETAIL) { backStackEntry ->
                val productId = backStackEntry.arguments?.getString("productId")?.toIntOrNull() ?: 0
                ProductDetailScreen(
                    viewModel = appViewModel,
                    productId = productId,
                    onBack = { navController.popBackStack() },
                    onEdit = { id -> navController.navigate(Routes.addStock(id)) },
                    onRestock = { id -> navController.navigate(Routes.addStock(id)) },
                    onDeleted = { navController.popBackStack() },
                    onTutorialClick = { startPageTutorial("productDetail") }
                )
            }

            composable(Routes.HELP) {
                HelpScreen(
                    // Replay the MAIN tutorial through startPageTutorial("main") so
                    // it behaves identically to the auto-launch (navigates to Morning
                    // first, real 14-step flow, highlights). Web v2.40 parity.
                    onReplayTutorial = { startPageTutorial("main") },
                    onBack = { navController.popBackStack() },
                    onLaunchPageTutorial = { tutId -> startPageTutorial(tutId) },
                    onTutorialClick = { startPageTutorial("help") }
                )
            }

            composable(Routes.SETTINGS) {
                SettingsScreen(
                    appSettings = appSettings,
                    viewModel = appViewModel,
                    onBack = { navController.popBackStack() },
                    onLaunchTutorial = { tutId -> startPageTutorial(tutId) },
                    onTutorialClick = { startPageTutorial("settings") },
                    onOpenReports = { navController.navigate(Routes.REPORTS) },
                    onOpenHelp = { navController.navigate(Routes.HELP) }
                )
            }

            // ── Reports Screen ────────────────────────────────────
            composable(Routes.REPORTS) {
                ReportsScreen(
                    viewModel = appViewModel,
                    onBack = { navController.popBackStack() },
                    onTutorialClick = { startPageTutorial("report") }
                )
            }

            // ── Expense Log Screen (web V2.71 parity — sub-page with back button) ──
            composable(Routes.EXPENSES) {
                ExpensesScreen(
                    viewModel = appViewModel,
                    onBack = { navController.popBackStack() },
                    onTutorialClick = { startPageTutorial("expenses") }
                )
            }

            // ── Restock Screen ────────────────────────────────────
            composable(Routes.RESTOCK) {
                RestockScreen(
                    viewModel = appViewModel,
                    onBack = { navController.popBackStack() },
                    onComplete = {
                        // V2.70: restocking resolves the stock-alert notification.
                        NotificationCenter.cancel(context, NotificationCenter.ID_STOCK)
                        navController.navigate(Routes.INVENTORY) {
                            popUpTo(Routes.INVENTORY) { inclusive = true }
                        }
                    },
                    onNavigateToInventory = {
                        NotificationCenter.cancel(context, NotificationCenter.ID_STOCK)
                        navController.navigate(Routes.INVENTORY) {
                            popUpTo(Routes.INVENTORY) { inclusive = true }
                        }
                    },
                    onTutorialClick = { startPageTutorial("restock") }
                )
            }
        }
        } // end CompositionLocalProvider
    }        // Tutorial overlay on top — with highlight frame support
        CompositionLocalProvider(LocalTutorialScrollStateHolder provides scrollStateHolder) {
            val currentSteps = getCurrentTutorialSteps()
            if (tutorialActive && tutorialStep < currentSteps.size) {
                val step = currentSteps[tutorialStep]
                TutorialOverlay(
                    isActive = tutorialActive,
                    currentStep = tutorialStep,
                    totalSteps = currentSteps.size,
                    isReplay = tutorialReplay,
                    step = step,
                    highlightState = highlightState,
                    onNext = { advanceTutorial() },
                    onPrev = { previousTutorial() },
                    onSkip = { endTutorial() },
                    onFinish = { endTutorial(returnToMorning = true) }
                )
            }
        }

    // Developer Panel
    DeveloperPanel(visible = devPanelVisible, onDismiss = { devPanelVisible = false }, viewModel = appViewModel, appSettings = appSettings)
}
