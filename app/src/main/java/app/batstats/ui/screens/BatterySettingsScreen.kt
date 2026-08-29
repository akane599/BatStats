package app.batstats.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.batstats.R
import app.batstats.battery.BatteryGraph
import app.batstats.battery.shizuku.ShizukuBridge
import app.batstats.battery.util.PrivilegeChecker
import app.batstats.battery.util.RootStatsCollector
import app.batstats.battery.util.ShellRunner
import app.batstats.settings.AppSettings
import app.batstats.settings.AppSettingsSchema
import app.batstats.settings.Data
import app.batstats.settings.Display
import app.batstats.settings.General
import app.batstats.settings.Notifications
import app.batstats.viewmodel.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.mlmgames.settings.core.SettingField
import io.github.mlmgames.settings.core.SettingMeta
import io.github.mlmgames.settings.core.backup.ImportResult
import io.github.mlmgames.settings.core.resources.StringResourceProvider
import io.github.mlmgames.settings.core.types.Dropdown
import io.github.mlmgames.settings.core.types.Slider
import io.github.mlmgames.settings.core.types.Toggle
import io.github.mlmgames.settings.ui.ProvideStringResources
import io.github.mlmgames.settings.ui.components.SettingsAction
import io.github.mlmgames.settings.ui.components.SettingsItem
import io.github.mlmgames.settings.ui.components.SettingsSection
import io.github.mlmgames.settings.ui.components.SettingsToggle
import io.github.mlmgames.settings.ui.dialogs.DropdownSettingDialog
import io.github.mlmgames.settings.ui.dialogs.SliderSettingDialog
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatterySettingsScreen(
    onBack: () -> Unit,
    onExportData: () -> Unit,
    vm: SettingsViewModel = koinViewModel(),
    stringProvider: StringResourceProvider = koinInject()
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val snackbarHost = remember { SnackbarHostState() }

    var showResetDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var showClearDataDialog by remember { mutableStateOf(false) }

    var showDropdown by remember { mutableStateOf(false) }
    var showSlider by remember { mutableStateOf(false) }
    var currentField by remember { mutableStateOf<SettingField<AppSettings, *>?>(null) }

    val schema = AppSettingsSchema
    val grouped = remember { schema.groupedByCategory() }

    // Launcher for exporting settings (backup)
    val createSettingsBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val message = vm.exportToFile(uri)
                snackbarHost.showSnackbar(message)
            }
        }
    }

    val categoryOrder = listOf(
        General::class to "General",
        Notifications::class to "Notifications",
        Display::class to "Display",
        Data::class to "Data & Export"
    )

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.batstats))
                        Text(
                            stringResource(R.string.customize_behavior),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { createSettingsBackup.launch("BatStats_Settings_Backup.json") }) {
                        Icon(Icons.Outlined.Backup, contentDescription = stringResource(R.string.export_settings))
                    }
                    IconButton(onClick = { showImportDialog = true }) {
                        Icon(Icons.Outlined.Restore, contentDescription = stringResource(R.string.import_settings_desc))
                    }
                    IconButton(onClick = { showResetDialog = true }) {
                        Icon(Icons.Outlined.RestartAlt, contentDescription = stringResource(R.string.reset_settings_desc))
                    }
                },
                scrollBehavior = scrollBehavior
            )
        },
        snackbarHost = { SnackbarHost(snackbarHost) }
    ) { padding ->
        ProvideStringResources(stringProvider) {
            LazyColumn(
                modifier = Modifier.padding(padding),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                categoryOrder.forEach { (categoryClass, categoryTitle) ->
                    val fields = grouped[categoryClass].orEmpty()
                    if (fields.isEmpty()) return@forEach

                    item(key = "header_$categoryTitle") {
                        Text(
                            text = categoryTitle,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }

                    item(key = "section_$categoryTitle") {
                        SettingsSection(title = "") {
                            fields.forEach { field ->
                                val meta = field.meta ?: return@forEach
                                val enabled = schema.isEnabled(settings, field)

                                RenderSettingField(
                                    field = field,
                                    meta = meta,
                                    settings = settings,
                                    enabled = enabled,
                                    onToggle = { value ->
                                        vm.updateSetting(field.name, value)
                                    },
                                    onOpenDropdown = {
                                        currentField = field
                                        showDropdown = true
                                    },
                                    onOpenSlider = {
                                        currentField = field
                                        showSlider = true
                                    }
                                )
                            }

                            if (categoryClass == Data::class) {
                                SettingsAction(
                                    title = "Export Battery Data",
                                    description = "Export battery history to file",
//                                    buttonText = "Open",
                                    onClick = onExportData
                                )

                                SettingsAction(
                                    title = "Clear All Data",
                                    description = "Delete all stored battery data",
//                                    buttonText = "Clear",
                                    onClick = { showClearDataDialog = true }
                                )
                            }
                        }
                    }
                }

                item(key = "advanced_stats") {
                    AdvancedStatsSettingsCard(snackbarHost)
                }
            }
        }
    }

    // Dropdown Dialog
    val cf = currentField
    if (showDropdown && cf?.meta != null) {
        val meta = cf.meta!!
        @Suppress("UNCHECKED_CAST")
        val anyField = cf as SettingField<AppSettings, Any?>
        val index = when (val value = anyField.get(settings)) {
            is Int -> value
            is Enum<*> -> value.ordinal
            else -> 0
        }

        if (meta.options.isNotEmpty()) {
            DropdownSettingDialog(
                title = meta.title,
                options = meta.options,
                selectedIndex = index,
                onDismiss = { showDropdown = false },
                onOptionSelected = { idx ->
                    vm.updateSetting(cf.name, idx)
                    showDropdown = false
                }
            )
        } else {
            showDropdown = false
        }
    }

    // Slider Dialog
    if (showSlider && cf?.meta != null) {
        val meta = cf.meta!!
        @Suppress("UNCHECKED_CAST")
        val anyField = cf as SettingField<AppSettings, Any?>
        val value = anyField.get(settings)

        val currentVal = when (value) {
            is Float -> value
            is Int -> value.toFloat()
            is Long -> value.toFloat()
            is Double -> value.toFloat()
            else -> 0f
        }

        SliderSettingDialog(
            title = meta.title,
            currentValue = currentVal,
            min = meta.min,
            max = meta.max,
            step = meta.step,
            onDismiss = { showSlider = false },
            onValueSelected = { v ->
                when (value) {
                    is Float -> vm.updateSetting(cf.name, v)
                    is Int -> vm.updateSetting(cf.name, v.toInt())
                    is Long -> vm.updateSetting(cf.name, v.toLong())
                    is Double -> vm.updateSetting(cf.name, v.toDouble())
                }
                showSlider = false
            }
        )
    }

    // Reset Dialog
    if (showResetDialog) {
        val uiSettingsResetMsg = stringResource(R.string.ui_settings_reset)
        val allSettingsResetMsg = stringResource(R.string.all_settings_reset)
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text(stringResource(R.string.reset_settings)) },
            text = {
                Column {
                    Text(stringResource(R.string.choose_reset))
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.reset_ui_settings), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.reset_all_settings), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        scope.launch {
                            vm.resetUISettings()
                            snackbarHost.showSnackbar(uiSettingsResetMsg)
                            showResetDialog = false
                        }
                    }) { Text(stringResource(R.string.reset_ui)) }
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        onClick = {
                            scope.launch {
                                vm.resetAll()
                                snackbarHost.showSnackbar(allSettingsResetMsg)
                                showResetDialog = false
                            }
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) { Text(stringResource(R.string.reset_all)) }
                }
            },
            dismissButton = { TextButton(onClick = { showResetDialog = false }) { Text(stringResource(R.string.cancel)) } }
        )
    }

    // Import Dialog
    if (showImportDialog) {
        var jsonInput by remember { mutableStateOf("") }
        val settingsImportedMsg = stringResource(R.string.settings_imported)
        val importFailedMsg = stringResource(R.string.import_failed)
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text(stringResource(R.string.import_settings)) },
            text = {
                Column {
                    Text(stringResource(R.string.paste_json))
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = jsonInput,
                        onValueChange = { jsonInput = it },
                        modifier = Modifier.fillMaxWidth().height(200.dp),
                        placeholder = { Text(stringResource(R.string.paste_json_hint)) }
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            when (val result = vm.import(jsonInput)) {
                                is ImportResult.Success -> snackbarHost.showSnackbar("${result.appliedCount} $settingsImportedMsg")
                                is ImportResult.Error -> snackbarHost.showSnackbar("$importFailedMsg: ${result.error}")
                            }
                            showImportDialog = false
                        }
                    },
                    enabled = jsonInput.isNotBlank()
                ) { Text(stringResource(R.string.import_action)) }
            },
            dismissButton = { TextButton(onClick = { showImportDialog = false }) { Text(stringResource(R.string.cancel)) } }
        )
    }

    // Clear Data Dialog
    if (showClearDataDialog) {
        val dataClearedMsg = stringResource(R.string.data_cleared)
        AlertDialog(
            onDismissRequest = { showClearDataDialog = false },
            icon = { Icon(Icons.Outlined.DeleteForever, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(stringResource(R.string.clear_all_data)) },
            text = { Text(stringResource(R.string.clear_data_warning)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            BatteryGraph.db.clearAllTables()
                            snackbarHost.showSnackbar(dataClearedMsg)
                            showClearDataDialog = false
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text(stringResource(R.string.delete_all)) }
            },
            dismissButton = { TextButton(onClick = { showClearDataDialog = false }) { Text(stringResource(R.string.cancel)) } }
        )
    }
}

@Composable
private fun AdvancedStatsSettingsCard(snackbarHost: SnackbarHostState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val shizukuBridge: ShizukuBridge = org.koin.compose.koinInject()
    val shellRunner: ShellRunner = org.koin.compose.koinInject()

    var hasShizuku by remember { mutableStateOf(false) }
    var hasAdbDump by remember { mutableStateOf(false) }
    var hasBatteryStats by remember { mutableStateOf(false) }
    var hasUsage by remember { mutableStateOf(false) }
    var hasRoot by remember { mutableStateOf(false) }
    var advMode by remember { mutableStateOf(ShellRunner.Mode.NONE) }

    fun refreshChecks() {
        scope.launch {
            hasShizuku = try { shizukuBridge.hasPermission() } catch (_: Exception) { false }
            hasAdbDump = PrivilegeChecker.hasDump(context)
            hasBatteryStats = PrivilegeChecker.hasBatteryStats(context)
            hasUsage = PrivilegeChecker.hasUsageStats(context)
            hasRoot = withContext(Dispatchers.IO) { RootStatsCollector.isRootAvailable() }
            advMode = shellRunner.detectMode()
        }
    }

    LaunchedEffect(Unit) { refreshChecks() }

    val pkg = context.packageName
    val cmds = remember(pkg) {
        listOf(
            "adb shell pm grant $pkg android.permission.BATTERY_STATS",
            "adb shell pm grant $pkg android.permission.DUMP",
            "adb shell pm grant $pkg android.permission.PACKAGE_USAGE_STATS",
            "adb shell pm grant $pkg android.permission.INTERACT_ACROSS_USERS"
        )
    }
    val oneLiner = remember(pkg) { "for p in DUMP BATTERY_STATS PACKAGE_USAGE_STATS INTERACT_ACROSS_USERS; do adb shell pm grant $pkg android.permission.\$p; done" }

    Column(modifier = Modifier.padding(horizontal = 0.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Advanced Stats",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        Card(modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Privilege status", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    PrivStatusChip("Shizuku", hasShizuku)
                    PrivStatusChip("DUMP", hasAdbDump)
                    PrivStatusChip("BATTERY_STATS", hasBatteryStats)
                    PrivStatusChip("Usage", hasUsage)
                    PrivStatusChip("Root", hasRoot)
                }
                Text("Active mode: ${advMode.name}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    if (advMode != ShellRunner.Mode.NONE) "✓ Advanced stats available via $advMode"
                    else "✗ No privileged access — grant via ADB/Shizuku/Root for per-app mAh, wakelocks, etc.",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (advMode != ShellRunner.Mode.NONE) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { refreshChecks() }) { Text("Recheck") }
                    TextButton(onClick = {
                        context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }) { Text("Usage access") }
                    if (!hasShizuku) {
                        TextButton(onClick = { try { shizukuBridge.requestPermission(1001) } catch (_: Exception) {} }) { Text("Request Shizuku") }
                    }
                }
                HorizontalDivider()
                Text("ADB grant (permanent, survives reboot)", style = MaterialTheme.typography.labelMedium)
                cmds.forEach { cmd ->
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(cmd, modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall)
                        IconButton(onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("adb", cmd))
                            scope.launch { snackbarHost.showSnackbar("Copied") }
                        }) { Icon(Icons.Outlined.ContentCopy, "Copy", modifier = androidx.compose.ui.Modifier.width(18.dp)) }
                    }
                }
                Text("One-liner:", style = MaterialTheme.typography.labelSmall)
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(oneLiner, modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall)
                    IconButton(onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("adb", oneLiner))
                        scope.launch { snackbarHost.showSnackbar("Copied") }
                    }) { Icon(Icons.Outlined.ContentCopy, "Copy") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("adb", cmds.joinToString("\n")))
                        scope.launch { snackbarHost.showSnackbar("All commands copied") }
                    }) { Text("Copy all") }
                    TextButton(onClick = {
                        if (hasRoot) {
                            scope.launch {
                                var ok = 0
                                listOf("android.permission.BATTERY_STATS","android.permission.DUMP","android.permission.PACKAGE_USAGE_STATS").forEach { perm ->
                                    val out = RootStatsCollector.runAsRoot("pm grant $pkg $perm")
                                    if (out != null && !out.contains("Exception") && !out.contains("Error")) ok++
                                }
                                refreshChecks()
                                snackbarHost.showSnackbar(if (ok>0) "Granted via root ($ok/3), recheck" else "Root grant failed — check su")
                            }
                        }
                    }, enabled = hasRoot) { Text("Grant via Root") }
                }
                Text("Enable Developer options → USB debugging. On Xiaomi/HyperOS/OnePlus also enable “USB debugging (Security settings)” / “Disable permission monitoring”, then reboot. After grant: force-stop app or reboot.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun PrivStatusChip(label: String, granted: Boolean) {
    AssistChip(
        onClick = {},
        label = { Text(label) },
        leadingIcon = { Icon(if (granted) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline, null, modifier = androidx.compose.ui.Modifier.width(16.dp), tint = if (granted) androidx.compose.ui.graphics.Color(0xFF2E7D32) else MaterialTheme.colorScheme.error) },
        colors = AssistChipDefaults.assistChipColors(containerColor = if (granted) androidx.compose.ui.graphics.Color(0xFFE8F5E9) else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f))
    )
}

@Composable
private fun RenderSettingField(
    field: SettingField<AppSettings, *>,
    meta: SettingMeta,
    settings: AppSettings,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onOpenDropdown: () -> Unit,
    onOpenSlider: () -> Unit
) {
    when (meta.type) {
        Toggle::class -> {
            @Suppress("UNCHECKED_CAST")
            val boolField = field as? SettingField<AppSettings, Boolean>
            if (boolField != null) {
                SettingsToggle(
                    title = meta.title,
                    description = meta.description.takeIf { it.isNotBlank() },
                    checked = boolField.get(settings),
                    enabled = enabled,
                    onCheckedChange = onToggle
                )
            }
        }
        Dropdown::class -> {
            @Suppress("UNCHECKED_CAST")
            val anyField = field as SettingField<AppSettings, Any?>
            val index = when (val value = anyField.get(settings)) { is Int -> value; is Enum<*> -> value.ordinal; else -> 0 }
            if (meta.options.isNotEmpty()) {
                SettingsItem(
                    title = meta.title,
                    subtitle = meta.options.getOrNull(index) ?: "Unknown",
                    description = meta.description.takeIf { it.isNotBlank() },
                    enabled = enabled,
                    onClick = onOpenDropdown
                )
            }
        }
        Slider::class -> {
            val currentLocale = LocalConfiguration.current.locales[0]
            @Suppress("UNCHECKED_CAST")
            val anyField = field as SettingField<AppSettings, Any?>
            val subtitle = when (val value = anyField.get(settings)) {
                is Float, is Double -> String.format(currentLocale, "%.1f", (value as Number).toDouble())
                is Int, is Long -> value.toString()
                else -> ""
            }
            SettingsItem(
                title = meta.title,
                subtitle = subtitle,
                description = meta.description.takeIf { it.isNotBlank() },
                enabled = enabled,
                onClick = onOpenSlider
            )
        }
    }
}