package com.example.tindago.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.tindago.ui.localization.LocalLanguage
import com.example.tindago.ui.localization.t
import com.example.tindago.ui.theme.*
import androidx.compose.ui.tooling.preview.Preview
import com.example.tindago.ui.theme.TindaGoTheme
import kotlin.math.roundToInt

data class TutorialStep(
    val i18nKey: String,
    val page: String = "home",
    val highlightTarget: String? = null
)

data class PageTutorial(
    val id: String,
    val labelKey: String,
    val stepsKeyPrefix: String,
    val stepCount: Int,
    val page: String,
    // Per-step highlight target ids (null = no highlight). Must match ids
    // registered via Modifier.tutorialHighlight(id, state) in the screens.
    val highlights: List<String?> = emptyList(),
    // Optional final step that highlights the ? button with a replay hint.
    val replayHintKey: String? = null
)

val pageTutorials = listOf(
    PageTutorial("backup", "tutBackup", "backupTutorial", 3, "settings",
        highlights = listOf(null, "backupToggle", null),
        replayHintKey = "tutorialReplayHint"),

    PageTutorial("main", "tutMain", "tutorial", 14, "morning"),
    PageTutorial("home", "tutHome", "homeTutorial", 10, "morning",
        highlights = listOf(null, null, "morningStockCard", "morningDebtCard", null, null, null, null, null, null),
        replayHintKey = "tutorialReplayHint"),
    PageTutorial("stock", "tutStock", "stockTutorial", 10, "inventory",
        highlights = listOf(null, "stockSearchBar", "addStockBtn", null, "inventoryList", null, null, null, null, null),
        replayHintKey = "tutorialReplayHint"),
    PageTutorial("sales", "tutSales", "salesTutorial", 10, "day",
        highlights = listOf(null, null, null, "dayStatsGrid", null, "dayStatsGrid", "sellFab", "dayTxFeed", null, null),
        replayHintKey = "tutorialReplayHint"),
    PageTutorial("debt", "tutDebt", "debtTutorial", 10, "debts",
        highlights = listOf(null, "totalDebtCard", "newDebtBtn", "debtList", "debtList", null, null, null, null, null),
        replayHintKey = "tutorialReplayHint"),
    PageTutorial("eod", "tutEOD", "eodTutorial", 6, "closing",
        highlights = listOf(null, "closingEarnings", "closingUtang", null, null, "completeDayBtn"),
        replayHintKey = "tutorialReplayHint"),
    PageTutorial("report", "tutReport", "reportTutorial", 6, "reports",
        highlights = listOf(null, "reportPeriodToggle", "reportSummaryCards", "reportRecentTx", "reportBestSellers", "reportLowStock"),
        replayHintKey = "tutorialReplayHint"),
    PageTutorial("settings", "tutSettings", "settingsTutorial", 5, "settings",
        highlights = listOf(null, "settingsLanguage", "settingsTextSize", "settingsStoreName", "settingsOwnerName"),
        replayHintKey = "tutorialReplayHint"),
    PageTutorial("addProduct", "tutAddProduct", "addProductTutorial", 5, "add_stock",
        highlights = listOf(null, "addStockNameField", "addStockMarkup", "addStockQtyField", "addStockSaveBtn"),
        replayHintKey = "tutorialReplayHint"),
    PageTutorial("newSale", "tutNewSale", "newSaleTutorial", 5, "day",
        highlights = listOf("sellFab", null, null, null, null),
        replayHintKey = "tutorialReplayHint"),
    // Web v2.64 parity: standalone checkout page tutorial.
    PageTutorial("checkout", "tutCheckout", "checkoutTutorial", 5, "checkout",
        highlights = listOf("checkoutSearch", "checkoutAddCart", "checkoutCart", "checkoutPayCredit", "checkoutComplete"),
        replayHintKey = "tutorialReplayHint"),
    PageTutorial("newDebt", "tutNewDebt", "newDebtTutorial", 4, "new_debt",
        highlights = listOf(null, "newDebtNameField", "newDebtAmountField", "newDebtSaveBtn"),
        replayHintKey = "tutorialReplayHint"),
    PageTutorial("help", "tutHelp", "helpTutorial", 6, "help",
        highlights = listOf(null, "helpTutSelector", "helpHowTo", "helpContact", "helpAbout", null),
        replayHintKey = "tutorialReplayHint"),
    PageTutorial("restock", "tutRestock", "restockTutorial", 8, "restock",
        highlights = listOf(null, "restockStep1Section", "restockSearchField", "restockProductList", "restockContinueBtn", "restockStep2Section", "restockPurchaseList", "restockDoneBtn"),
        replayHintKey = "tutorialReplayHint"),
    PageTutorial("productDetail", "tutProductDetail", "productDetailTutorial", 4, "product_detail",
        highlights = listOf(null, "pdStockAlert", "pdActions", "pdDeleteBtn"),
        replayHintKey = "tutorialReplayHint"),
    PageTutorial("customerDebtDetail", "tutCustomerDebtDetail", "customerDebtDetailTutorial", 5, "customer_debt_detail",
        highlights = listOf(null, "cddBalanceCard", "cddLedger", "cddRecordPaymentBtn", null),
        replayHintKey = "tutorialReplayHint"),
    PageTutorial("recordPayment", "tutRecordPayment", "recordPaymentTutorial", 4, "record_payment",
        highlights = listOf("rpAmountField", "rpRemainingPreview", "rpNoteField", "rpPayBtn"),
        replayHintKey = "tutorialReplayHint"),
    // Web V2.71 parity: Store Expenses tutorial (Expense Log).
    PageTutorial("expenses", "tutExpenses", "expensesTutorial", 3, "expenses",
        highlights = listOf(null, "expenseCategoryField", "expenseList"),
        replayHintKey = "tutorialReplayHint")
)

/**
 * Tutorial overlay with:
 * - Highlight frame around the target element (if [TutorialStep.highlightTarget] is set)
 * - Semi-transparent backdrop
 * - Backdrop click to dismiss (on replay only)
 * - Smart positioning: card auto-positions above or below the highlight
 * - Step indicator header pill with dots
 * - Skip (on replay) and Next / Finish buttons
 */
@Composable
fun TutorialOverlay(
    isActive: Boolean,
    currentStep: Int,
    totalSteps: Int,
    isReplay: Boolean,
    step: TutorialStep,
    highlightState: TutorialHighlightState = LocalTutorialHighlightState.current,
    onNext: () -> Unit,
    onPrev: () -> Unit = {},
    onSkip: () -> Unit,
    onFinish: () -> Unit,
    tutorialColor: Color = Green600
) {
    val langState = LocalLanguage.current
    val lang = langState.value
    val isLastStep = currentStep >= totalSteps - 1

    // Read screen scroll state for auto-scroll.
    // Uses the shared holder provided at NavGraph level, which screens update
    // when they compose. The old approach of reading LocalScreenScrollState/
    // LocalScreenLazyListState directly didn't work because the overlay is
    // composed outside those providers' scope.
    val scrollHolder = LocalTutorialScrollStateHolder.current
    val density = LocalDensity.current

    // Track the overlay's own bounds for viewport height calculation
    var overlayHeight by remember { mutableFloatStateOf(0f) }

    // Get the current tutorial name for the header pill
    val tutorialName = pageTutorials
        .find { step.i18nKey.startsWith(it.stepsKeyPrefix) }
        ?.labelKey
        ?.t(lang) ?: "tutMain".t(lang)

    // Get target bounds for highlight frame
    val targetBounds = step.highlightTarget?.let { highlightState.getBounds(it) }

    // ── Auto-scroll: when step changes with a highlight target that's off-screen, scroll to make it visible ──
    // Matches web app's behavior in renderTutorialStep() using scrollContainer.scrollTop adjustments
    LaunchedEffect(currentStep, step.highlightTarget, targetBounds, overlayHeight) {
        if (targetBounds != null && !targetBounds.isEmpty && overlayHeight > 0f) {
            val bufferPx = with(density) { 160.dp.toPx() } // leave room for header pill + tutorial card
            val viewportBottom = overlayHeight
            val targetTop = targetBounds.top
            val targetBottom = targetBounds.bottom
            val needsScrollDown = targetBottom > viewportBottom - bufferPx
            val needsScrollUp = targetTop < 100f

            if (needsScrollDown || needsScrollUp) {
                // Read into local vals so Kotlin can smart-cast (delegated properties can't be smart-cast)
                val currentScrollState = scrollHolder.scrollState
                val currentLazyListState = scrollHolder.lazyListState
                if (currentScrollState != null) {
                    // Regular ScrollState screens (verticalScroll)
                    if (needsScrollDown) {
                        val scrollDelta = (targetBottom - (viewportBottom - bufferPx)).toInt()
                        currentScrollState.animateScrollTo(currentScrollState.value + scrollDelta)
                    } else if (needsScrollUp) {
                        val scrollDelta = (100f - targetTop + 30f).toInt()
                        currentScrollState.animateScrollTo((currentScrollState.value - scrollDelta).coerceAtLeast(0))
                    }
                } else if (currentLazyListState != null) {
                    // LazyColumn screens: use animateScrollToItem with approximate index
                    val itemHeightPx = with(density) { 80.dp.toPx() }
                    if (needsScrollDown) {
                        val targetIndex = ((targetBottom - viewportBottom + bufferPx) / itemHeightPx + currentLazyListState.firstVisibleItemIndex).toInt().coerceAtLeast(0)
                        currentLazyListState.animateScrollToItem(targetIndex)
                    } else if (needsScrollUp) {
                        val targetIndex = (currentLazyListState.firstVisibleItemIndex - 2).coerceAtLeast(0)
                        currentLazyListState.animateScrollToItem(targetIndex)
                    }
                }
            }
        }
    }

    AnimatedVisibility(
        visible = isActive,
        enter = fadeIn(animationSpec = tween(300)),
        exit = fadeOut(animationSpec = tween(200))
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { coordinates ->
                    overlayHeight = coordinates.localToRoot(Offset.Zero).y + coordinates.size.height
                }
        ) {
            // ── Backdrop (semi-transparent) ──
            // Always consumes touches so the underlying UI can't be interacted with while the
            // tutorial is active (mandatory-tutorial interaction-blocking fix). Tapping the
            // backdrop only dismisses the tutorial on replay (isReplay); mandatory tutorials
            // simply swallow the tap.
            Box(
                modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.55f))
                        .clickable(
                            indication = null,
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                        ) { if (isReplay) onSkip() }
                )

            // ── Highlight frame around target element ──
            if (targetBounds != null && !targetBounds.isEmpty) {
                val density = LocalDensity.current
                val offsetX = (targetBounds.left - 4f).roundToInt()
                val offsetY = (targetBounds.top - 4f).roundToInt()
                val frameWidth = with(density) { (targetBounds.width + 8f).toDp() }
                val frameHeight = with(density) { (targetBounds.height + 8f).toDp() }

                // Pure outline highlight — transparent center with white + green rings
                // Matches web app CSS behavior: box-shadow: 0 0 0 2px white, 0 0 0 4px var(--primary)
                // No background fill — the target element is fully visible underneath
                Box(
                    modifier = Modifier
                        .offset { IntOffset(offsetX, offsetY) }
                        .size(frameWidth, frameHeight)
                        .drawBehind {
                            // Green outer ring (4dp wide, offset 2dp outward)
                            // This is drawn first (behind) and extends further outward
                            drawRoundRect(
                                color = tutorialColor,
                                topLeft = Offset(-2.dp.toPx(), -2.dp.toPx()),
                                size = Size(size.width + 4.dp.toPx(), size.height + 4.dp.toPx()),
                                cornerRadius = CornerRadius(14.dp.toPx()),
                                style = Stroke(width = 4.dp.toPx())
                            )
                            // White inner ring (2dp wide, centered on Box edge)
                            // Drawn on top of green, covering the inner 2dp of the green ring
                            drawRoundRect(
                                color = Color.White,
                                cornerRadius = CornerRadius(12.dp.toPx()),
                                style = Stroke(width = 2.dp.toPx())
                            )
                        }
                )
            }

            // ── Top header pill (tutorial name + step number + dots) ──
            Surface(
                modifier = Modifier
                    .padding(top = 48.dp)
                    .align(Alignment.TopCenter),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                shadowElevation = 6.dp,
                tonalElevation = 2.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Step number badge
                    Surface(
                        modifier = Modifier.size(28.dp),
                        shape = CircleShape,
                        color = tutorialColor
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                "${currentStep + 1}",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    // Tutorial name
                    Text(
                        tutorialName,
                        style = MaterialTheme.typography.bodySmall,
                        color = Gray500
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    // Step dots
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        repeat(totalSteps.coerceAtMost(14)) { i ->
                            Box(
                                modifier = Modifier
                                    .size(if (i == currentStep) 8.dp else 6.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (i <= currentStep) tutorialColor
                                        else Gray300
                                    )
                            )
                        }
                    }
                }
            }

            // ── Bottom instruction card with smart positioning ──
            val overlayHeightDp = with(LocalDensity.current) {
                overlayHeight.toDp()
            }
            val cardAlignment = if (targetBounds != null && !targetBounds.isEmpty) {
                val targetCenterY = targetBounds.top + targetBounds.height / 2
                val centerYDp = with(LocalDensity.current) { targetCenterY.toDp() }
                // If target is in top 45%, place card at bottom. Otherwise at top (below header).
                if (centerYDp < overlayHeightDp * 0.45f) {
                    Alignment.BottomCenter
                } else {
                    Alignment.TopCenter
                }
            } else {
                Alignment.BottomCenter
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = 20.dp,
                        end = 20.dp,
                        top = if (cardAlignment == Alignment.TopCenter) 100.dp else 20.dp,
                        bottom = if (cardAlignment == Alignment.BottomCenter) 20.dp else 20.dp
                    )
                    .align(cardAlignment),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    // Step counter
                    Text(
                        text = "${currentStep + 1} / $totalSteps",
                        style = MaterialTheme.typography.bodySmall,
                        color = Gray400,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    // Instruction text
                    Text(
                        text = step.i18nKey.t(lang),
                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp),
                        color = Gray800,
                        fontWeight = FontWeight.Medium,
                        lineHeight = MaterialTheme.typography.bodyLarge.lineHeight
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    // Buttons row
                    // Skip sits on the LEFT; Previous + Next/Finish stay grouped on the RIGHT.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isReplay) {
                            TextButton(
                                onClick = onSkip
                            ) {
                                Text("skip".t(lang), color = Gray500)
                            }
                        } else {
                            Spacer(modifier = Modifier)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (currentStep > 0) {
                                TextButton(
                                    onClick = onPrev
                                ) {
                                    Text("prev".t(lang), color = Gray600)
                                }
                            }
                            Button(
                                onClick = {
                                    if (isLastStep) onFinish()
                                    else onNext()
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = tutorialColor,
                                    contentColor = Color.White
                                ),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(
                                    if (isLastStep) "getStarted".t(lang) else "next".t(lang),
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true, name = "Tutorial Overlay")
@Composable
fun TutorialOverlayPreview() {
    TindaGoTheme {
        TutorialOverlay(
            isActive = true,
            currentStep = 0,
            totalSteps = 5,
            isReplay = false,
            step = TutorialStep("tutorial1", "morning"),
            onNext = {},
            onSkip = {},
            onFinish = {}
        )
    }
}
