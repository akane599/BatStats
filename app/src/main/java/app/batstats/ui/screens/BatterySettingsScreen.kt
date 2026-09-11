package app.batstats.ui.screens

import android.net.Uri
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.batstats.R
import app.batstats.battery.BatteryGraph
import app.batstats.settings.AppSettings
import app.batstats.settings.AppSettingsSchema
import app.batstats.settings.Data
import app.batstats.settings.Display
import app.batstats.settings.General
import app.batstats.settings.Notifications
import app.batstats.viewmodel.SettingsViewModel
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
    initialCategory: String? = null,
    onOpenAccess: () -> Unit = {},
    vm: SettingsViewModel = koinViewModel(),
    stringProvider: StringResourceProvider = koinInject()
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    var showActions by rememberSaveable { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
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

    val listState = rememberLazyListState()

    LaunchedEffect(initialCategory) {
        if (initialCategory != null) {
            val idx = categoryOrder.indexOfFirst { it.second == initialCategory }
            if (idx >= 0) {
                val target = idx * 2 + 2
                // delay to allow LazyColumn to be composed
                kotlinx.coroutines.delay(100)
                listState.animateScrollToItem(target)
            }
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.settings))
                        Text(
                            stringResource(R.string.customize_behavior),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = { showActions = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.settings_more)) }
                    DropdownMenu(expanded = showActions, onDismissRequest = { showActions = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.data_export_import)) }, onClick = { showActions = false; onExportData() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.export_settings)) }, onClick = { showActions = false; createSettingsBackup.launch("BatStats_Settings_Backup.json") })
                        DropdownMenuItem(text = { Text(stringResource(R.string.import_settings_desc)) }, onClick = { showActions = false; showImportDialog = true })
                        DropdownMenuItem(text = { Text(stringResource(R.string.reset_settings_desc)) }, onClick = { showActions = false; showResetDialog = true })
                    }
                },
                scrollBehavior = scrollBehavior
            )
        },
        snackbarHost = { SnackbarHost(snackbarHost) }
    ) { padding ->
        ProvideStringResources(stringProvider) {
            LazyColumn(
                state = listState,
                modifier = Modifier.padding(padding).testTag("settings_list"),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item(key = "search") {
                    OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        label = { Text(stringResource(R.string.search_settings)) },
                        leadingIcon = { Icon(Icons.Outlined.Search, null) },
                        trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, stringResource(R.string.clear_search)) } })
                }
                item(key = "access") {
                    OutlinedCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.background_access), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.background_access_body), style = MaterialTheme.typography.bodySmall)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = onOpenAccess) { Text(stringResource(R.string.check_access)) }
                                TextButton(onClick = { runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } }) { Text(stringResource(R.string.battery_system_settings)) }
                                TextButton(onClick = { runCatching { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) } }) { Text(stringResource(R.string.usage_access_settings)) }
                            }
                        }
                    }
                }
                categoryOrder.forEach { (categoryClass, categoryTitle) ->
                    val fields = grouped[categoryClass].orEmpty().filter { field ->
                        query.isBlank() || field.meta?.title?.contains(query.trim(), true) == true ||
                            field.meta?.description?.contains(query.trim(), true) == true || categoryTitle.contains(query.trim(), true)
                    }
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
                                    title = stringResource(R.string.export_battery_data),
                                    description = "Export battery history to file",
//                                    buttonText = "Open",
                                    onClick = onExportData
                                )

                                SettingsAction(
                                    title = stringResource(R.string.clear_all_data),
                                    description = "Delete all stored battery data",
//                                    buttonText = "Clear",
                                    onClick = { showClearDataDialog = true }
                                )
                            }
                        }
                    }
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
        val clearFailedMsg = stringResource(R.string.data_clear_failed)
        AlertDialog(
            onDismissRequest = { showClearDataDialog = false },
            icon = { Icon(Icons.Outlined.DeleteForever, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(stringResource(R.string.clear_all_data)) },
            text = { Text(stringResource(R.string.clear_data_warning)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (!clearing) {
                            clearing = true
                            scope.launch {
                                try {
                                    vm.clearBatteryData()
                                    showClearDataDialog = false
                                    snackbarHost.showSnackbar(dataClearedMsg)
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    throw e
                                } catch (_: Exception) {
                                    showClearDataDialog = false
                                    snackbarHost.showSnackbar(clearFailedMsg)
                                } finally { clearing = false }
                            }
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    enabled = !clearing,
                ) { Text(stringResource(R.string.delete_all)) }
            },
            dismissButton = { TextButton(onClick = { showClearDataDialog = false }) { Text(stringResource(R.string.cancel)) } }
        )
    }
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
