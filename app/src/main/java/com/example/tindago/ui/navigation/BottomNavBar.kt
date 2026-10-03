package com.example.tindago.ui.navigation

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.*
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.tindago.data.LocalSnackbarHost
import com.example.tindago.data.LocalSnackbarScope
import com.example.tindago.data.StockStatus
import com.example.tindago.ui.components.LocalTutorialHighlightState
import com.example.tindago.ui.components.tutorialHighlight
import com.example.tindago.ui.localization.LocalLanguage
import com.example.tindago.ui.localization.t
import com.example.tindago.ui.screens.AppViewModel
import com.example.tindago.ui.theme.*
import kotlinx.coroutines.launch

// ── Three Moment bottom nav items ──────────────────────────────────────────
enum class MomentNavItem(val route: String) {
    MORNING(Routes.MORNING),
    DAY(Routes.DAY),
    CLOSING(Routes.CLOSING)
}

// ── Support nav items (for inventory / debts pages) ────────────────────────
enum class SupportNavItem(val route: String) {
    INVENTORY(Routes.INVENTORY),
    DEBTS(Routes.DEBTS)
}

/** Routes where the 3-moment bottom nav is shown */
private val momentRoutes = setOf(Routes.MORNING, Routes.DAY, Routes.CLOSING)

/** Routes where the support nav (Morning/Inventory/Debts) is shown */
private val supportRoutes = setOf(Routes.INVENTORY, Routes.DEBTS)

/** Double-tap threshold for Developer Panel activation */
private const val DOUBLE_TAP_MS = 600L

@Composable
fun BottomNavBar(
    navController: NavController,
    appViewModel: AppViewModel? = null,
    onDevPanelTriggered: () -> Unit = {},
    onSaleFabClick: (() -> Unit)? = null
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route ?: ""
    val langState = LocalLanguage.current
    val lang = langState.value
    val snackbarHost = LocalSnackbarHost.current
    val snackbarScope = LocalSnackbarScope.current

    val isMomentScreen = currentRoute in momentRoutes
    val isSupportScreen = currentRoute in supportRoutes
    val highlightState = LocalTutorialHighlightState.current

    // Only show nav on moment or support screens
    if (!isMomentScreen && !isSupportScreen) return

    // Badge: low stock count shown on Morning tab
    val products by appViewModel?.products?.collectAsState() ?: remember { mutableStateOf(emptyList()) }
    val lowStockCount = products.count {
        it.status == StockStatus.LOW || it.status == StockStatus.OUT_OF_STOCK
    }
    val showBadge = lowStockCount > 0

    // Double-tap Morning to open dev panel
    var lastMorningTapTime by remember { mutableLongStateOf(0L) }

    fun handleMomentTap(item: MomentNavItem) {
        if (item == MomentNavItem.MORNING) {
            val now = System.currentTimeMillis()
            if (lastMorningTapTime > 0 && now - lastMorningTapTime < DOUBLE_TAP_MS) {
                lastMorningTapTime = 0L
                onDevPanelTriggered()
                return
            }
            lastMorningTapTime = now
        }
        // Overdue guard (web v2.35 parity): Day Mode and Closing require an open,
        // non-stale day. Block at the source — no navigation round-trip.
        if ((item == MomentNavItem.DAY || item == MomentNavItem.CLOSING) && appViewModel != null) {
            val vm = appViewModel
            if (!vm.dayOpen || vm.isStaleOpenDay()) {
                val msg = if (vm.isStaleOpenDay())
                    "overdueRedirect".t(lang) else "dayNotOpen".t(lang)
                snackbarScope.launch { snackbarHost.showSnackbar(msg) }
                return
            }
        }

        if (currentRoute != item.route) {
            navController.navigate(item.route) {
                popUpTo(navController.graph.startDestinationId) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    fun handleSupportTap(item: SupportNavItem) {
        if (currentRoute != item.route) {
            navController.navigate(item.route) {
                popUpTo(navController.graph.startDestinationId) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    if (isMomentScreen) {
        // ═══════════════════════════════════════════════════════
        // THREE-MOMENT NAV (Morning / Day(+Sell FAB Day-only) / Close) — Option 1
        // ═══════════════════════════════════════════════════════
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 76.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 8.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // NOTE: the nav-bar inset padding must live INSIDE the Surface.
                    // On the Surface's own modifier it is applied outside the painted
                    // background, so the system nav-bar strip showed the page
                    // background (bar looked detached); here the surface colour
                    // reaches the bottom edge.
                    .navigationBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // ── Morning tab (left) — Phase 4.4: muted, not default (small 20.dp, gray) ──
                NavBarTab(
                    label = "morning".t(lang),
                    icon = {
                        Box(contentAlignment = Alignment.Center) {
                            if (showBadge) {
                                BadgedBox(badge = {
                                    Badge(containerColor = Red500, contentColor = Color.White) {
                                        Text(
                                            if (lowStockCount > 99) "99+" else "$lowStockCount",
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                }) {
                                    MorningIcon()
                                }
                            } else {
                                // icon = { Icon(Icons.Default.Coffee, null, modifier = Modifier.size(20.dp)) } — muted Morning
                                MorningIcon()
                            }
                        }
                    },
                    isSelected = currentRoute == Routes.MORNING, // labelSmall muted gray — MaterialTheme.typography.labelSmall + NavigationBarItemDefaults.colors(unselectedIconColor = Color.Gray)
                    onClick = { handleMomentTap(MomentNavItem.MORNING) }
                )

                // ── Stage 5 Option 1: Sell FAB Day-only ─ visible only when currentRoute == DAY ──
                if (currentRoute == Routes.DAY) {
                    // Hero Sell FAB — web-port: 52.dp circle, 24.dp icon, no resting shadow
                    // (web only applies shadow on hover; mobile has no hover, so resting is bare)
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                            .clickable {
                                // Day-only FAB always goes to Checkout; CHECKOUT guard allows stale (Task 4.2)
                                onSaleFabClick?.invoke() ?: navController.navigate(Routes.CHECKOUT)
                            }
                            .tutorialHighlight("sellFab", highlightState),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            androidx.compose.material3.Icon(
                                Icons.Filled.ShoppingCart,
                                contentDescription = "sell".t(lang),
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                            Text(
                                "sell".t(lang),
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                } else {
                    // Day tab (regular nav) — taps go through handleMomentTap which guards !dayOpen / stale
                    NavBarTab(
                        label = "day".t(lang),
                        icon = { DayIcon() },
                        isSelected = currentRoute == Routes.DAY,
                        onClick = { handleMomentTap(MomentNavItem.DAY) }
                    )
                }

                                // ── Close tab (right) ──
                NavBarTab(
                    label = "close".t(lang),
                    icon = { CloseIcon() },
                    isSelected = currentRoute == Routes.CLOSING,
                    onClick = { handleMomentTap(MomentNavItem.CLOSING) }
                )
            }
        }
    } else if (isSupportScreen) {
        // ═══════════════════════════════════════════════════════
        // SUPPORT NAV (Morning / Inventory / Debts)
        // Uses same Surface+Row layout as 3-moment nav for consistent height.
        // ═══════════════════════════════════════════════════════
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 76.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 8.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // Same as the 3-moment nav above: the inset padding belongs inside
                    // the Surface so the bar background paints to the bottom edge.
                    .navigationBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // ── Morning tab ──
                NavBarTab(
                    label = "morning".t(lang),
                    icon = {
                        Box(contentAlignment = Alignment.Center) {
                            if (showBadge) {
                                BadgedBox(badge = {
                                    Badge(containerColor = Red500, contentColor = Color.White) {
                                        Text(
                                            if (lowStockCount > 99) "99+" else "$lowStockCount",
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                }) {
                                    MorningIcon()
                                }
                            } else {
                                // icon = { Icon(Icons.Default.Coffee, null, modifier = Modifier.size(20.dp)) } — muted Morning
                                MorningIcon()
                            }
                        }
                    },
                    isSelected = false,
                    onClick = { navController.navigate(Routes.MORNING) { popUpTo(Routes.MORNING) { inclusive = true } } }
                )

                // ── Inventory tab ──
                NavBarTab(
                    label = "inventory".t(lang),
                    icon = { InventoryIcon() },
                    isSelected = currentRoute == Routes.INVENTORY,
                    onClick = { handleSupportTap(SupportNavItem.INVENTORY) }
                )

                // ── Debts tab ──
                NavBarTab(
                    label = "debts".t(lang),
                    icon = { DebtsIcon() },
                    isSelected = currentRoute == Routes.DEBTS,
                    onClick = { handleSupportTap(SupportNavItem.DEBTS) }
                )
            }
        }
    }
}

// ── Reusable tab component (for 3-moment nav) ─────────────────────────────

@Composable
private fun NavBarTab(
    label: String,
    icon: @Composable () -> Unit,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val selectedColor = MaterialTheme.colorScheme.primary
    val unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        modifier = Modifier
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        icon()
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            label,
            fontSize = 11.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (isSelected) selectedColor else unselectedColor,
            textAlign = TextAlign.Center
        )
    }
}

// ── SVG Icons ─────────────────────────────────────────────────────────────

@Composable
private fun MorningIcon() {
    Text("\u2600\uFE0F", fontSize = 20.sp)
}

@Composable
private fun PlusIcon() {
    Text("\u2795", fontSize = 20.sp)
}

@Composable
private fun CloseIcon() {
    Text("\uD83C\uDF19", fontSize = 20.sp)
}

@Composable
private fun DayIcon() {
    Text("\uD83D\uDECD\uFE0F", fontSize = 20.sp)
}

@Composable
private fun InventoryIcon() {
    Box(
        modifier = Modifier
            .size(24.dp)
            .background(Green100, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text("\uD83D\uDCE6", fontSize = 14.sp)
    }
}

@Composable
private fun DebtsIcon() {
    Box(
        modifier = Modifier
            .size(24.dp)
            .background(Amber100, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text("\uD83D\uDCB0", fontSize = 14.sp)
    }
}

// ── Preview ────────────────────────────────────────────────────────────────

@Preview(showBackground = true, name = "Bottom Nav — 3 Moments")
@Composable
fun BottomNavBarPreview() {
    TindaGoTheme {
        val navController = rememberNavController()
        BottomNavBar(navController = navController)
    }
}
