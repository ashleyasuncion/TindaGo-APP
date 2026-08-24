package com.example.tindago.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.text.format.DateFormat as AndroidDateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.ContextCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.tindago.data.notifications.DailyCheckWorker
import com.example.tindago.data.notifications.NotificationCenter
import com.example.tindago.data.notifications.NotificationChannels
import com.example.tindago.data.notifications.NotificationDeepLinks
import com.example.tindago.ui.localization.AppSettings
import com.example.tindago.ui.localization.LocalLanguage
import com.example.tindago.ui.localization.LocalTextScale
import com.example.tindago.ui.localization.t
import com.example.tindago.ui.components.LocalScreenScrollState
import com.example.tindago.ui.components.LocalTutorialHighlightState
import com.example.tindago.ui.components.LocalTutorialScrollStateHolder
import com.example.tindago.ui.components.PageTutorial
import com.example.tindago.ui.components.TutorialIconButton
import com.example.tindago.ui.components.pageTutorials
import com.example.tindago.ui.components.tutorialHighlight
import com.example.tindago.ui.theme.*
import kotlinx.coroutines.launch
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    appSettings: AppSettings? = null,
    viewModel: AppViewModel? = null,
    onBack: () -> Unit,
    onLaunchTutorial: (String) -> Unit = {},
    onTutorialClick: (() -> Unit)? = null,
    onOpenReports: () -> Unit = {},
    onOpenHelp: () -> Unit = {}
) {
    val context = LocalContext.current
    val langState = LocalLanguage.current
    val scaleState = LocalTextScale.current

    val settings = appSettings ?: remember { AppSettings(context) }
    val highlightState = LocalTutorialHighlightState.current

    var storeName by remember { mutableStateOf(settings.storeName) }
    var ownerName by remember { mutableStateOf(settings.ownerName) }
    var language by remember { mutableStateOf(settings.language) }
    var selectedSize by remember { mutableStateOf(settings.textSize) }
    var defaultMarkupText by remember { mutableStateOf(settings.defaultMarkup.toString()) }
    var lowStockThresholdText by remember { mutableStateOf(settings.lowStockThreshold.toString()) }
    var defaultCreditLimitText by remember { mutableStateOf(settings.defaultCreditLimit.toString()) }

    // Collapsible section states
    var displayExpanded by remember { mutableStateOf(false) }
    var inventoryExpanded by remember { mutableStateOf(false) }
    var notificationsExpanded by remember { mutableStateOf(false) }
    var supportExpanded by remember { mutableStateOf(false) }
    var dataExpanded by remember { mutableStateOf(false) }

    // Snackbar for save feedback
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Export/Import launchers
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null && viewModel != null) {
            try {
                val json = viewModel.getRawStateJson().toString(2)
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(json.toByteArray(Charsets.UTF_8))
                }
                scope.launch { snackbarHostState.showSnackbar("Data exported successfully!") }
            } catch (e: Exception) {
                scope.launch { snackbarHostState.showSnackbar("Export failed: ${e.message}") }
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null && viewModel != null) {
            try {
                val json = context.contentResolver.openInputStream(uri)?.use { input ->
                    input.bufferedReader(Charsets.UTF_8).readText()
                } ?: return@rememberLauncherForActivityResult
                val obj = JSONObject(json)
                viewModel.importData(obj)
                scope.launch { snackbarHostState.showSnackbar("Data imported successfully!") }
            } catch (e: Exception) {
                scope.launch { snackbarHostState.showSnackbar("Import failed: ${e.message}") }
            }
        }
    }

    val smsPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            scope.launch { snackbarHostState.showSnackbar("SMS permission granted.") }
        } else {
            // Revert the toggle if permission denied
            settings.smsEnabled = false
            scope.launch { snackbarHostState.showSnackbar("smsPermissionNeeded".t(settings.language)) }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            // V2.68: subpage rule — Back button present → centered title.
            CenterAlignedTopAppBar(
                title = { Text("settings".t(settings.language), style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (onTutorialClick != null) TutorialIconButton(onClick = onTutorialClick)
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Green600,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { padding ->
    val settingsScrollState = rememberScrollState()
    val scrollStateHolder = LocalTutorialScrollStateHolder.current
    LaunchedEffect(settingsScrollState) { scrollStateHolder.updateScrollState(settingsScrollState) }
    CompositionLocalProvider(LocalScreenScrollState provides settingsScrollState) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .verticalScroll(settingsScrollState)
        ) {
            // ═══════════════════════════════════════════════════════
            // ── Store Profile ──
            // ═══════════════════════════════════════════════════════
            SettingsSectionTitle("settingsSectionProfile".t(settings.language))
            OutlinedTextField(
                value = storeName,
                onValueChange = {
                    storeName = it
                    settings.storeName = it
                },
                label = { Text("storeName".t(settings.language)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().tutorialHighlight("settingsStoreName", highlightState)
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = ownerName,
                onValueChange = {
                    ownerName = it
                    settings.ownerName = it
                },
                label = { Text("ownerName".t(settings.language)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().tutorialHighlight("settingsOwnerName", highlightState)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.tutorialHighlight("settingsLanguage", highlightState)) {
                FilterChip(
                    selected = language == "en",
                    onClick = {
                        language = "en"
                        settings.language = "en"
                        langState.value = "en"
                        scope.launch { snackbarHostState.showSnackbar("settingsSaved".t(settings.language)) }
                    },
                    label = { Text("English") }
                )
                Spacer(modifier = Modifier.width(8.dp))
                FilterChip(
                    selected = language == "fil",
                    onClick = {
                        language = "fil"
                        settings.language = "fil"
                        langState.value = "fil"
                        scope.launch { snackbarHostState.showSnackbar("settingsSaved".t(settings.language)) }
                    },
                    label = { Text("Filipino") }
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ═══════════════════════════════════════════════════════
            // ── Display ──
            // ═══════════════════════════════════════════════════════
            CollapsibleSection(
                title = "settingsSectionDisplay".t(settings.language),
                expanded = displayExpanded,
                onToggle = { displayExpanded = !displayExpanded }
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.tutorialHighlight("settingsTextSize", highlightState)) {
                    listOf("standard" to "standard".t(settings.language), "large" to "large".t(settings.language), "extra-large" to "extraLarge".t(settings.language)).forEach { (value, label) ->
                        FilterChip(
                            selected = selectedSize == value,
                            onClick = {
                                selectedSize = value
                                settings.textSize = value
                                scaleState.value = settings.getTextScaleFactor()
                                scope.launch { snackbarHostState.showSnackbar("settingsSaved".t(settings.language)) }
                            },
                            label = { Text(label, style = MaterialTheme.typography.bodySmall) }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ═══════════════════════════════════════════════════════
            // ── Inventory Defaults ──
            // ═══════════════════════════════════════════════════════
            CollapsibleSection(
                title = "settingsSectionDefaults".t(settings.language),
                expanded = inventoryExpanded,
                onToggle = { inventoryExpanded = !inventoryExpanded }
            ) {
            OutlinedTextField(
                value = defaultMarkupText,
                onValueChange = {
                    defaultMarkupText = it.filter { c -> c.isDigit() }
                    val coerced = (defaultMarkupText.toIntOrNull() ?: 20).coerceIn(0, 200)
                    settings.defaultMarkup = coerced
                    defaultMarkupText = coerced.toString()
                },
                label = { Text("defaultMarkupLabel".t(settings.language)) },
                suffix = { Text("%") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().tutorialHighlight("settingsDefaultMarkup", highlightState)
            )
            Text(
                "defaultMarkupHint".t(settings.language),
                style = MaterialTheme.typography.bodySmall,
                color = Gray400
            )
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(
                value = lowStockThresholdText,
                onValueChange = {
                    lowStockThresholdText = it.filter { c -> c.isDigit() }
                    val coerced = lowStockThresholdText.toIntOrNull() ?: 5
                    settings.lowStockThreshold = coerced
                    lowStockThresholdText = coerced.toString()
                },
                label = { Text("alertThreshold".t(settings.language)) },
                suffix = { Text("units") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().tutorialHighlight("settingsLowStockThreshold", highlightState)
            )
            Text(
                "alertThresholdDesc".t(settings.language),
                style = MaterialTheme.typography.bodySmall,
                color = Gray400
            )
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(
                value = defaultCreditLimitText,
                onValueChange = {
                    defaultCreditLimitText = it.filter { c -> c.isDigit() }
                    defaultCreditLimitText.toIntOrNull()?.let { parsed ->
                        val coerced = parsed.coerceIn(0, 10000)
                        settings.defaultCreditLimit = coerced
                        defaultCreditLimitText = coerced.toString()
                    }
                },
                label = { Text("defaultCreditLimitLabel".t(settings.language)) },
                suffix = { Text("₱") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().tutorialHighlight("settingsDefaultCreditLimit", highlightState)
            )
            Text(
                "defaultCreditLimitHint".t(settings.language),
                style = MaterialTheme.typography.bodySmall,                color = Gray400
            )
            } // end CollapsibleSection

            Spacer(modifier = Modifier.height(16.dp))


            // ═══════════════════════════════════════════════════════
            // ── Notifications ──
            // ═══════════════════════════════════════════════════════
            CollapsibleSection(
                title = "notificationsSection".t(settings.language),
                expanded = notificationsExpanded,
                onToggle = { notificationsExpanded = !notificationsExpanded }
            ) {
            val contextForNotif = context
            var notifEnabled by remember { mutableStateOf(settings.notificationsEnabled) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "notificationsEnabled".t(settings.language),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Gray800
                    )
                    Text(
                        "notifyDescMaster".t(settings.language),
                        style = MaterialTheme.typography.bodySmall,
                        color = Gray400
                    )
                }
                Switch(
                    checked = notifEnabled,
                    onCheckedChange = {
                        notifEnabled = it
                        settings.notificationsEnabled = it
                    }
                )
            }
            if (notifEnabled) {
                Spacer(modifier = Modifier.height(8.dp))
                NotificationToggleRow(
                    label = "notifyOverdue".t(settings.language),
                    desc = "notifyDescOverdue".t(settings.language),
                    initial = settings.notifyOverdue,
                    onChange = { settings.notifyOverdue = it }
                )
                NotificationToggleRow(
                    label = "notifyStock".t(settings.language),
                    desc = "notifyDescStock".t(settings.language),
                    initial = settings.notifyStock,
                    onChange = { settings.notifyStock = it }
                )
                NotificationToggleRow(
                    label = "notifyClosing".t(settings.language),
                    desc = "notifyDescClosing".t(settings.language),
                    initial = settings.notifyClosing,
                    onChange = { settings.notifyClosing = it }
                )
                NotificationToggleRow(
                    label = "notifyDigest".t(settings.language),
                    desc = "notifyDescDigest".t(settings.language),
                    initial = settings.notifyDigest,
                    onChange = { settings.notifyDigest = it }
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Notification timing info card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                    shape = MaterialTheme.shapes.small
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            "notifTimingHeader".t(settings.language),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            "notifTimingDesc".t(settings.language),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Closing reminder hour (6-21) — device-aware time format
                Text(
                    "closingReminderHour".t(settings.language),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = Gray800
                )
                Text(
                    "closingReminderHourDesc".t(settings.language),
                    style = MaterialTheme.typography.bodySmall,
                    color = Gray400
                )
                Spacer(modifier = Modifier.height(6.dp))
                val is24h = AndroidDateFormat.is24HourFormat(context)
                val storedHour = settings.closingReminderHour

                if (is24h) {
                    var closingHourText by remember(storedHour) { mutableStateOf(storedHour.toString()) }
                    OutlinedTextField(
                        value = closingHourText,
                        onValueChange = {
                            closingHourText = it.filter { c -> c.isDigit() }
                            closingHourText.toIntOrNull()?.let { v ->
                                settings.closingReminderHour = v.coerceIn(6, 21)
                            }
                        },
                        label = { Text("closingReminderHour".t(settings.language)) },
                        suffix = { Text(":00") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    val isPm = storedHour >= 12
                    val display12 = when {
                        storedHour == 0 -> 12
                        storedHour > 12 -> storedHour - 12
                        else -> storedHour
                    }
                    var hour12Text by remember(storedHour) { mutableStateOf(display12.toString()) }
                    var pmState by remember(storedHour) { mutableStateOf(isPm) }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = hour12Text,
                            onValueChange = {
                                hour12Text = it.filter { c -> c.isDigit() }
                                hour12Text.toIntOrNull()?.let { h12 ->
                                    val h12Coerced = h12.coerceIn(1, 12)
                                    val h24 = when {
                                        pmState && h12Coerced < 12 -> h12Coerced + 12
                                        !pmState && h12Coerced == 12 -> 0
                                        else -> h12Coerced
                                    }
                                    settings.closingReminderHour = h24.coerceIn(6, 21)
                                }
                            },
                            label = { Text("closingReminderHour".t(settings.language)) },
                            suffix = { Text(":00") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                        FilledTonalButton(
                            onClick = {
                                pmState = !pmState
                                hour12Text.toIntOrNull()?.let { h12 ->
                                    val h24 = when {
                                        pmState && h12 < 12 -> h12 + 12
                                        !pmState && h12 == 12 -> 0
                                        else -> h12
                                    }
                                    settings.closingReminderHour = h24.coerceIn(6, 21)
                                }
                            },
                            modifier = Modifier.height(56.dp)
                        ) {
                            Text(
                                if (pmState) "PM" else "AM",
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // Permission denied → open system settings
                val hasNotifPermission = Build.VERSION.SDK_INT < 33 ||
                    ContextCompat.checkSelfPermission(contextForNotif, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                if (!hasNotifPermission) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "notifPermissionDeniedHint".t(settings.language),
                        style = MaterialTheme.typography.bodySmall,
                        color = Gray400
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            contextForNotif.startActivity(
                                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(Settings.EXTRA_APP_PACKAGE, contextForNotif.packageName)
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("openSystemSettings".t(settings.language)) }
                }
            } // end if (notifEnabled)
            } // end CollapsibleSection (Notifications)

            Spacer(modifier = Modifier.height(16.dp))

            // ═══════════════════════════════════════════════════════
            // ── Support ──
            // ═══════════════════════════════════════════════════════
            CollapsibleSection(
                title = "settingsSectionSupport".t(settings.language),
                expanded = supportExpanded,
                onToggle = { supportExpanded = !supportExpanded }
            ) {
            // Tutorial selector
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Green50),
                shape = MaterialTheme.shapes.medium
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        "tutorials".t(settings.language),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Green800
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        var selectedTutorial by remember { mutableStateOf<PageTutorial?>(null) }
                        var dropdownExpanded by remember { mutableStateOf(false) }
                        ExposedDropdownMenuBox(
                            expanded = dropdownExpanded,
                            onExpandedChange = { dropdownExpanded = !dropdownExpanded },
                            modifier = Modifier.weight(1f)
                        ) {
                            OutlinedTextField(
                                value = selectedTutorial?.labelKey?.t(settings.language) ?: "",
                                onValueChange = {},
                                readOnly = true,
                                placeholder = { Text("tutSelector".t(settings.language), color = Gray400) },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = dropdownExpanded) },
                                modifier = Modifier.fillMaxWidth().menuAnchor(),
                                singleLine = true,
                                shape = MaterialTheme.shapes.medium
                            )
                            ExposedDropdownMenu(
                                expanded = dropdownExpanded,
                                onDismissRequest = { dropdownExpanded = false }
                            ) {
                                pageTutorials.forEach { tut ->
                                    DropdownMenuItem(
                                        text = { Text(tut.labelKey.t(settings.language)) },
                                        onClick = {
                                            selectedTutorial = tut
                                            dropdownExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                        Button(
                            onClick = {
                                selectedTutorial?.let {
                                    onLaunchTutorial(it.id)
                                }
                            },
                            enabled = selectedTutorial != null,
                            modifier = Modifier.height(48.dp),
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Text("tutLaunch".t(settings.language))
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            // Help + Reports
            OutlinedButton(
                onClick = onOpenHelp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Help, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("help".t(settings.language))
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = onOpenReports,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.BarChart, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("reportsTitle".t(settings.language))
            }
            } // end CollapsibleSection (Support)

            Spacer(modifier = Modifier.height(16.dp))

            // ═══════════════════════════════════════════════════════
            // ── SMS Notifications ──
            // ═══════════════════════════════════════════════════════
            CollapsibleSection(
                title = "smsSectionTitle".t(settings.language),
                expanded = dataExpanded,
                onToggle = { dataExpanded = !dataExpanded }
            ) {
            Text(
                "smsSectionDesc".t(settings.language),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))

            // Master toggle
            var smsEnabled by remember { mutableStateOf(settings.smsEnabled) }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "smsMasterToggle".t(settings.language),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        "smsMasterToggleDesc".t(settings.language),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = smsEnabled,
                    onCheckedChange = { enabled ->
                        if (enabled) {
                            // Request SEND_SMS permission first
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                                ContextCompat.checkSelfPermission(
                                    context, Manifest.permission.SEND_SMS
                                ) != PackageManager.PERMISSION_GRANTED
                            ) {
                                smsPermissionLauncher.launch(Manifest.permission.SEND_SMS)
                                return@Switch
                            }
                        }
                        smsEnabled = enabled
                        settings.smsEnabled = enabled
                        // Reschedule/cancel SMS worker
                        com.example.tindago.data.notifications.SmsWorker.schedule(
                            context, enabled, settings.smsReminderDays
                        )
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                if (enabled) "smsWorkerScheduled".t(settings.language)
                                else "smsWorkerCancelled".t(settings.language)
                            )
                        }
                    }
                )
            }

            if (smsEnabled) {
                Spacer(modifier = Modifier.height(12.dp))

                // Frequency
                Text(
                    "smsFrequency".t(settings.language),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                var reminderDays by remember { mutableStateOf(settings.smsReminderDays) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(3, 7, 14).forEach { days ->
                        FilterChip(
                            selected = reminderDays == days,
                            onClick = {
                                reminderDays = days
                                settings.smsReminderDays = days
                                com.example.tindago.data.notifications.SmsWorker.schedule(
                                    context, true, days
                                )
                            },
                            label = { Text("$days days") }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Quiet hours
                Text(
                    "smsQuietHours".t(settings.language),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    "smsQuietHoursDesc".t(settings.language),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                var quietStart by remember { mutableStateOf(settings.smsQuietHoursStart) }
                var quietEnd by remember { mutableStateOf(settings.smsQuietHoursEnd) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = quietStart.toString(),
                        onValueChange = { v ->
                            val h = v.toIntOrNull()?.coerceIn(0, 23) ?: 8
                            quietStart = h
                            settings.smsQuietHoursStart = h
                        },
                        label = { Text("From") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    Text("—")
                    OutlinedTextField(
                        value = quietEnd.toString(),
                        onValueChange = { v ->
                            val h = v.toIntOrNull()?.coerceIn(0, 23) ?: 20
                            quietEnd = h
                            settings.smsQuietHoursEnd = h
                        },
                        label = { Text("To") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Paid confirmation toggle
                var smsPaidConfirm by remember { mutableStateOf(settings.smsSendPaidConfirmation) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "smsPaidConfirm".t(settings.language),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            "smsPaidConfirmDesc".t(settings.language),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = smsPaidConfirm,
                        onCheckedChange = {
                            smsPaidConfirm = it
                            settings.smsSendPaidConfirmation = it
                        }
                    )
                }
            } // end if (smsEnabled)
            } // end CollapsibleSection (SMS)

            Spacer(modifier = Modifier.height(16.dp))

            // ═══════════════════════════════════════════════════════
            // ── Data ──
            // ═══════════════════════════════════════════════════════
            CollapsibleSection(
                title = "settingsSectionData".t(settings.language),
                expanded = dataExpanded,
                onToggle = { dataExpanded = !dataExpanded }
            ) {
            OutlinedButton(
                onClick = {
                    if (viewModel != null) {
                        exportLauncher.launch("tindago-data.json")
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("exportData".t(settings.language)) }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    if (viewModel != null) {
                        importLauncher.launch(arrayOf("application/json"))
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("importData".t(settings.language)) }
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = {
                    if (viewModel != null) {
                        // TODO: add confirmation dialog
                        viewModel.resetAllData()
                        scope.launch { snackbarHostState.showSnackbar("Data has been reset.") }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Red600)
            ) { Text("resetDataBtn".t(settings.language), color = MaterialTheme.colorScheme.onPrimary) }
            } // end CollapsibleSection (Data)
        } // Column
    } // CompositionLocalProvider
    } // Scaffold padding
} // SettingsScreen

@Composable
private fun CollapsibleSection(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggle() }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Gray700,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = Gray400,
                    modifier = Modifier.size(20.dp)
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(expandFrom = Alignment.Top),
                exit = shrinkVertically(shrinkTowards = Alignment.Top)
            ) {
                Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                    content()
                }
            }
        }
    }
}

@Preview(showBackground = true, name = "Settings Screen")
@Composable
fun SettingsScreenPreview() {
    val context = LocalContext.current
    TindaGoTheme {
        SettingsScreen(
            appSettings = AppSettings(context),
            onBack = {}
        )
    }
}

@Composable
private fun NotificationToggleRow(
    label: String,
    desc: String,
    initial: Boolean,
    onChange: (Boolean) -> Unit
) {
    var checked by remember { mutableStateOf(initial) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = Gray800)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = Gray400)
        }
        Switch(
            checked = checked,
            onCheckedChange = {
                checked = it
                onChange(it)
            }
        )
    }
}

@Composable
private fun SettingsSectionTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = Green800,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = 8.dp)
    )
}
