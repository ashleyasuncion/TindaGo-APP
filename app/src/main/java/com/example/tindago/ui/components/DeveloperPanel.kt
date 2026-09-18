 package com.example.tindago.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.tindago.data.notifications.DailyCheckWorker
import com.example.tindago.data.notifications.NotificationCenter
import com.example.tindago.data.notifications.runNotificationCheck
import com.example.tindago.ui.localization.AppSettings
import com.example.tindago.ui.localization.t
import com.example.tindago.ui.screens.AppViewModel
import com.example.tindago.ui.theme.Red500
import com.example.tindago.ui.theme.Blue500
import com.example.tindago.ui.theme.Green500
import com.example.tindago.ui.theme.Amber500
import com.example.tindago.ui.theme.TindaGoTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Developer Panel — hidden utility accessible via double-tap Help in bottom nav.
 * Provides data viewers, test data generators, and reset actions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeveloperPanel(
    visible: Boolean,
    onDismiss: () -> Unit,
    viewModel: AppViewModel,
    appSettings: AppSettings? = null
) {
    val context = LocalContext.current

    // State for sub-dialogs
    var showRawStateDialog by remember { mutableStateOf(false) }
    var showClearSelectedDialog by remember { mutableStateOf(false) }
    var showResetAllConfirm by remember { mutableStateOf(false) }

    // CSV Export launcher
    val csvExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri != null) {
            try {
                val csv = viewModel.exportCsv()
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(csv.toByteArray(Charsets.UTF_8))
                }
                Toast.makeText(context, "CSV exported successfully!", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "CSV export failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Export/Import launchers
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            try {
                val json = viewModel.getRawStateJson().toString(2)
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(json.toByteArray(Charsets.UTF_8))
                }
                Toast.makeText(context, "Data exported successfully!", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                val json = context.contentResolver.openInputStream(uri)?.use { input ->
                    input.bufferedReader(Charsets.UTF_8).readText()
                } ?: return@rememberLauncherForActivityResult
                val obj = JSONObject(json)
                viewModel.importData(obj)
                Toast.makeText(context, "Data imported successfully!", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Import failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // V2.73: coroutine scope for the on-demand notification check trigger.
    val scope = rememberCoroutineScope()

    // V2.73: notification permission request (Android 13+) — without it every
    // post silently no-ops, so the test tools must be able to request it.
    val notifPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        Toast.makeText(
            context,
            if (granted) "Notification permission granted — post again to see banners."
            else "Notification permission denied — banners will not show.",
            Toast.LENGTH_LONG
        ).show()
    }

    // Show sub-dialogs
    if (showRawStateDialog) {
        RawStateDialog(
            json = viewModel.getRawStateJson().toString(2),
            onDismiss = { showRawStateDialog = false }
        )
    }

    if (showClearSelectedDialog) {
        ClearSelectedDialog(
            onDismiss = { showClearSelectedDialog = false },
            onClear = { types ->
                viewModel.clearSelectedData(types)
                showClearSelectedDialog = false
                Toast.makeText(context, "Selected data cleared.", Toast.LENGTH_SHORT).show()
            }
        )
    }

    if (showResetAllConfirm) {
        AlertDialog(
            icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = Red500) },
            title = { Text("Reset ALL Data?") },
            text = { Text("This will permanently delete all your products, sales, debts, and settings. This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.resetAllData()
                        showResetAllConfirm = false
                        onDismiss()
                        Toast.makeText(context, "All data has been reset.", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = Red500)
                ) { Text("Reset All") }
            },
            dismissButton = {
                TextButton(onClick = { showResetAllConfirm = false }) { Text("Cancel") }
            },
            onDismissRequest = { showResetAllConfirm = false }
        )
    }

    // Main bottom sheet
    if (visible) {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 32.dp)
            ) {
                // Header
                Text(
                    text = "Dev Tools",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                // ── Section: Data Viewers ──
                SectionHeader("Data Viewers")
                DevActionItem(
                    icon = Icons.Default.Code,
                    label = "View Raw State",
                    desc = "See all app data as JSON",
                    onClick = { showRawStateDialog = true }
                )
                DevActionItem(
                    icon = Icons.Default.FileUpload,
                    label = "Export Data",
                    desc = "Save all data as JSON file",
                    onClick = { exportLauncher.launch("tindago-data.json") }
                )
                DevActionItem(
                    icon = Icons.Default.FileDownload,
                    label = "Import Data",
                    desc = "Load data from JSON file (overwrites current)",
                    onClick = { importLauncher.launch(arrayOf("application/json")) }
                )
                DevActionItem(
                    icon = Icons.Default.TableChart,
                    label = "Export CSV",
                    desc = "Export data as CSV file for spreadsheets",
                    onClick = { csvExportLauncher.launch("tindago-data.csv") }
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                // ── Section: Test Generators ──
                SectionHeader("Test Generators")
                DevActionItem(
                    icon = Icons.Default.ShoppingCart,
                    label = "Generate Test Sale",
                    desc = "Create a random sale from inventory",
                    onClick = {
                        viewModel.generateTestSale()
                        Toast.makeText(context, "Test sale generated!", Toast.LENGTH_SHORT).show()
                    }
                )
                DevActionItem(
                    icon = Icons.Default.AccountBalance,
                    label = "Generate Test Debts",
                    desc = "Create 2-5 random customer debts",
                    onClick = {
                        val count = viewModel.generateTestDebts()
                        Toast.makeText(context, "$count test debts created!", Toast.LENGTH_SHORT).show()
                    }
                )
                DevActionItem(
                    icon = Icons.Default.Dashboard,
                    label = "Bulk Add Items",
                    desc = "Add 15 random products to inventory",
                    onClick = {
                        val count = viewModel.bulkAddItems()
                        Toast.makeText(context, "$count items added!", Toast.LENGTH_SHORT).show()
                    }
                )
                DevActionItem(
                    icon = Icons.Default.ContentPaste,
                    label = "Seed Sample Data",
                    desc = "Load default sample products & debts",
                    onClick = {
                        viewModel.seedSampleData()
                        Toast.makeText(context, "Sample data loaded.", Toast.LENGTH_SHORT).show()
                    }
                )
                DevActionItem(
                    icon = Icons.Default.CalendarMonth,
                    label = "Generate 1 Month Data",
                    desc = "Create realistic sales, debts & expenses across 30 days",
                    onClick = {
                        val summary = viewModel.generateMonthOfTestData()
                        Toast.makeText(context, summary, Toast.LENGTH_LONG).show()
                    }
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                // ── Section: Restock ──
                SectionHeader("Restock")
                DevActionItem(
                    icon = Icons.Default.Inventory,
                    label = "Clear Restock Data",
                    desc = "Reset restock date & history",
                    onClick = {
                        viewModel.clearRestockData()
                        Toast.makeText(context, "Restock data cleared.", Toast.LENGTH_SHORT).show()
                    }
                )
                DevActionItem(
                    icon = Icons.Default.CalendarToday,
                    label = "Set Restock Date to Today",
                    desc = "For testing the morning reminder",
                    onClick = {
                        viewModel.setRestockDateToday()
                        Toast.makeText(context, "Restock date set to today.", Toast.LENGTH_SHORT).show()
                    }
                )
                DevActionItem(
                    icon = Icons.Default.History,
                    label = "View Restock Log",
                    desc = viewModel.viewRestockLogCount(),
                    onClick = {
                        Toast.makeText(context, viewModel.viewRestockLogCount(), Toast.LENGTH_SHORT).show()
                    }
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                // ── Section: Test Notifications (V2.73) ──
                SectionHeader("Test Notifications")
                DevActionItem(
                    icon = Icons.Default.Notifications,
                    label = "Run Notification Check Now",
                    desc = "Run the real morning/evening rules + cooldowns with current data (force-posts)",
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            val app = context.applicationContext as com.example.tindago.TindaGoApp
                            val morning = runNotificationCheck(
                                app,
                                DailyCheckWorker.CHECK_MORNING,
                                force = true
                            )
                            // Also run the closing reminder half so both rule sets are exercised.
                            val closing = runNotificationCheck(
                                app,
                                DailyCheckWorker.CHECK_CLOSING,
                                force = true
                            )
                            val total = morning.posted + closing.posted
                            val msg = when {
                                morning.masterToggleOff || closing.masterToggleOff ->
                                    "Notifications are OFF (Settings → Notifications master switch). Turn them on, then run again."
                                morning.noPermission || closing.noPermission ->
                                    "Notification permission missing — grant it, then run again."
                                total > 0 ->
                                    "Notification check: $total notification(s) posted."
                                else ->
                                    "Notification check ran — nothing due (check Settings toggles / day state / stock)."
                            }
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                )
                DevActionItem(
                    icon = Icons.Default.Email,
                    label = "Post Sample Notifications",
                    desc = "One per channel (overdue/stock/closing/digest) — no rules, no cooldowns",
                    onClick = {
                        if (!NotificationCenter.canPost(context)) {
                            if (android.os.Build.VERSION.SDK_INT >= 33) {
                                notifPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                Toast.makeText(context, "Notifications blocked by the system.", Toast.LENGTH_LONG).show()
                            }
                        } else {
                            val lang = appSettings?.language ?: "en"
                            val posted = NotificationCenter.postSampleNotifications(context, lang)
                            Toast.makeText(
                                context,
                                if (posted == 4) "4 sample notification(s) posted — check the shade."
                                else "$posted/4 posted — some channels may be disabled in system settings (try Reset Channels).",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                )
                DevActionItem(
                    icon = Icons.Default.Settings,
                    label = "Reset Notification Channels",
                    desc = "Recreate the 4 channels (fixes channels disabled in system settings)",
                    onClick = {
                        NotificationCenter.resetChannels(context)
                        Toast.makeText(context, "Notification channels recreated.", Toast.LENGTH_SHORT).show()
                    }
                )
                DevActionItem(
                    icon = Icons.Default.Clear,
                    label = "Clear All Notifications",
                    desc = "Dismiss every notification this app posted",
                    onClick = {
                        NotificationCenter.cancelAll(context)
                        Toast.makeText(context, "Notifications cleared.", Toast.LENGTH_SHORT).show()
                    }
                )
                Text(
                    text = "Tip: scheduled workers (~06:30 / 18:00) suppress while the app is open — the check button forces a run for testing.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                // ── Section: Day State ──
                SectionHeader("Day State")
                DevActionItem(
                    icon = Icons.Default.PlayArrow,
                    label = "Toggle Day Open/Close",
                    desc = if (viewModel.dayOpen) "Day is currently OPEN. Close it." else "Day is currently CLOSED. Open it.",
                    onClick = {
                        viewModel.dayOpen = !viewModel.dayOpen
                        viewModel.persistDayState()
                        Toast.makeText(context, "Day ${if (viewModel.dayOpen) "opened" else "closed"} manually.", Toast.LENGTH_SHORT).show()
                    }
                )
                DevActionItem(
                    icon = Icons.Default.DateRange,
                    label = "Start New Day",
                    desc = "Archive current day and prepare a fresh business day",
                    onClick = {
                        viewModel.startNewDay()
                        Toast.makeText(context, "New day started! Tap 'Start the Day' to open.", Toast.LENGTH_SHORT).show()
                    }
                )
                DevActionItem(
                    icon = Icons.Default.Archive,
                    label = "Archive Today's Sales",
                    desc = "Move today's sales into historical record",
                    onClick = {
                        viewModel.archiveDaySales()
                        Toast.makeText(context, "Sales archived.", Toast.LENGTH_SHORT).show()
                    }
                )

                // ── Dev Date Override (temporary, in-memory only) ──
                // Simulates a different date for testing time-dependent features.
                // NOT persisted — resets on app restart, never affects real data.
                var overrideInput by remember { mutableStateOf(viewModel.devDateOverride) }
                Text(
                    text = "Dev Date Override (temporary)",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                )
                OutlinedTextField(
                    value = overrideInput,
                    onValueChange = { overrideInput = it },
                    placeholder = { Text("YYYY-MM-DD (e.g. 2026-07-30)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            if (viewModel.setDevDateOverride(overrideInput)) {
                                val msg = if (viewModel.devDateOverride.isBlank())
                                    "Dev date override cleared."
                                else
                                    "Dev date override set to ${viewModel.devDateOverride} (temporary)."
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Invalid date. Use YYYY-MM-DD.", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Set Override") }
                    OutlinedButton(
                        onClick = {
                            overrideInput = ""
                            viewModel.clearDevDateOverride()
                            Toast.makeText(context, "Dev date override cleared.", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Clear") }
                }

                // ── Dev Time Override (temporary, in-memory only) ──
                // Simulates an hour (0-23) to test the time-based greeting (v2.37).
                // NOT persisted — resets on app restart, never affects real data.
                var timeOverrideInput by remember { mutableStateOf(viewModel.devTimeOverride?.toString() ?: "") }
                Text(
                    text = "Dev Time Override (temporary)",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                )
                OutlinedTextField(
                    value = timeOverrideInput,
                    onValueChange = { timeOverrideInput = it },
                    placeholder = { Text("Hour 0-23 (e.g. 21 = evening)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            if (viewModel.setDevTimeOverride(timeOverrideInput)) {
                                val msg = if (viewModel.devTimeOverride == null)
                                    "Dev time override cleared."
                                else
                                    "Dev time override set to ${viewModel.devTimeOverride}:00 (temporary)."
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Invalid hour. Use 0-23.", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Set Override") }
                    OutlinedButton(
                        onClick = {
                            timeOverrideInput = ""
                            viewModel.clearDevTimeOverride()
                            Toast.makeText(context, "Dev time override cleared.", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Clear") }
                }

                DevActionItem(
                    icon = Icons.Default.Refresh,
                    label = "Reset Tutorial State",
                    desc = "Tutorial will show on next launch",
                    onClick = {
                        if (appSettings != null) {
                            appSettings.launchCount = 0
                            appSettings.hasCompletedTutorial = false
                        }
                        Toast.makeText(context, "Tutorial state reset. Will show on next launch.", Toast.LENGTH_SHORT).show()
                    }
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                // ── Section: Reset Actions ──
                SectionHeader("Reset Actions")
                DevActionItem(
                    icon = Icons.Default.RestartAlt,
                    label = "Reset Today's Sales",
                    desc = "Clear today's daily entry & specific sales",
                    onClick = {
                        viewModel.resetTodaySales()
                        Toast.makeText(context, "Today's sales reset.", Toast.LENGTH_SHORT).show()
                    }
                )
                DevActionItem(
                    icon = Icons.Default.Inventory,
                    label = "Clear Inventory",
                    desc = "Remove all products from inventory",
                    onClick = {
                        viewModel.clearAllInventory()
                        Toast.makeText(context, "Inventory cleared.", Toast.LENGTH_SHORT).show()
                    }
                )
                DevActionItem(
                    icon = Icons.Default.ClearAll,
                    label = "Clear Selected...",
                    desc = "Choose which data categories to clear",
                    onClick = { showClearSelectedDialog = true }
                )
                DevActionItem(
                    icon = Icons.Default.Warning,
                    label = "Reset ALL Data",
                    desc = "Full factory reset (irreversible)",
                    iconTint = Red500,
                    onClick = { showResetAllConfirm = true }
                )

                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(vertical = 4.dp)
    )
}

@Composable
private fun DevActionItem(
    icon: ImageVector,
    label: String,
    desc: String,
    iconTint: Color = Green500,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = iconTint,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = label, style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

// ── Raw State Dialog ────────────────────────────────────────────────────

@Composable
private fun RawStateDialog(json: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Raw State (JSON)") },
        text = {
            Column {
                SelectionContainer {
                    Text(
                        text = json,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 400.dp)
                            .verticalScroll(rememberScrollState())
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Raw State", json))
                }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Copy to Clipboard")
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = {}
    )
}

// ── Clear Selected Dialog ───────────────────────────────────────────────

data class ClearableDataType(val key: String, val label: String, var checked: Boolean = false)

@Composable
private fun ClearSelectedDialog(
    onDismiss: () -> Unit,
    onClear: (List<String>) -> Unit
) {
    val types = remember {
        mutableStateListOf(
            ClearableDataType("products", "Products"),
            ClearableDataType("sales", "Sales & Daily Entries"),
            ClearableDataType("debts", "Debts & Customers"),
            ClearableDataType("eod", "End-of-Day Data")
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Clear Selected Data") },
        text = {
            Column {
                Text("Select data types to clear:", style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(8.dp))
                types.forEach { item ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val idx = types.indexOf(item)
                                types[idx] = item.copy(checked = !item.checked)
                            }
                            .padding(vertical = 4.dp)
                    ) {
                        Checkbox(
                            checked = item.checked,
                            onCheckedChange = { checked ->
                                val idx = types.indexOf(item)
                                types[idx] = item.copy(checked = checked)
                            }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(item.label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val selected = types.filter { it.checked }.map { it.key }
                    onClear(selected)
                },
                colors = ButtonDefaults.textButtonColors(contentColor = Red500)
            ) { Text("Clear Selected") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Preview(showBackground = true, name = "Developer Panel")
@Composable
fun DeveloperPanelPreview() {
    TindaGoTheme {
        DeveloperPanel(
            visible = true,
            onDismiss = {},
            viewModel = remember { AppViewModel() }
        )
    }
}
