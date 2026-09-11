package app.batstats.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.batstats.R
import app.batstats.viewmodel.*
import app.batstats.ui.components.StatusMessage
import org.koin.androidx.compose.koinViewModel
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
fun AppDrainScreen(onBack: () -> Unit, onOpenAccess: () -> Unit = {}, vm: AppDrainViewModel = koinViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val range by vm.range.collectAsStateWithLifecycle()
    val showSystem by vm.showSystemApps.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var selectedPackage by rememberSaveable { mutableStateOf<String?>(null) }
    val rows = remember(state.rows, query) {
        val search = query.trim()
        state.rows.filter { search.isEmpty() || it.label.contains(search, true) || it.packageName.contains(search, true) }
    }
    val context = LocalContext.current
    val selected = state.rows.firstOrNull { it.packageName == selectedPackage }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val openFailed = stringResource(R.string.app_settings_unavailable)

    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.app_drain), fontWeight = FontWeight.Bold) }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
        })
    }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).testTag("app_drain_list"),
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "range") {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DrainRange.entries.forEach { option ->
                        FilterChip(selected = range == option, onClick = { vm.setRange(option) }, label = { Text(stringResource(option.labelRes())) })
                    }
                }
            }
            item(key = "search") {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth().testTag("app_search"),
                    singleLine = true, label = { Text(stringResource(R.string.search_apps)) },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, stringResource(R.string.clear_search)) } }
                )
            }
            item(key = "filter") {
                FilterChip(selected = showSystem, onClick = vm::toggleSystemApps,
                    label = { Text(stringResource(R.string.system_apps)) },
                    leadingIcon = { Icon(Icons.Outlined.Android, null, Modifier.size(18.dp)) })
            }
            if (state.loading) {
                item(key = "loading") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            } else {
                item(key = "total") {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(String.format(Locale.getDefault(), "%.1f mAh", state.totalMah), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                            Text(stringResource(R.string.recorded_app_energy), style = MaterialTheme.typography.titleSmall)
                            Text(stringResource(if (state.source == DrainSource.MEASURED) R.string.drain_source_measured else R.string.drain_source_estimated), style = MaterialTheme.typography.bodySmall)
                            Text(stringResource(R.string.hourly_precision_note), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (rows.isEmpty()) {
                    item(key = "empty") {
                        val filtered = query.isNotBlank() || state.totalMah > 0
                        StatusMessage(
                            stringResource(if (filtered) R.string.no_matching_apps else R.string.no_app_drain_yet),
                            stringResource(if (filtered) R.string.no_matching_apps_body else R.string.no_app_drain_explanation),
                            action = stringResource(if (filtered) R.string.clear_filters else R.string.check_access),
                            onAction = { if (filtered) { query = ""; if (!showSystem) vm.toggleSystemApps() } else onOpenAccess() }
                        )
                    }
                } else {
                    item(key = "count") { Text(stringResource(R.string.app_count, rows.size), style = MaterialTheme.typography.labelLarge) }
                    items(rows, key = { it.packageName }, contentType = { "app" }) { row ->
                        AppEnergyRow(row) { selectedPackage = row.packageName }
                    }
                }
            }
        }
    }
    if (selected != null) {
        AlertDialog(
            onDismissRequest = { selectedPackage = null },
            title = { Text(selected.label) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(selected.packageName)
                    Text(String.format(Locale.getDefault(), "%.2f mAh · %.1f%%", selected.energyMah, selected.shareOfTotal * 100))
                    Text(stringResource(R.string.share_of_recorded_apps))
                }
            },
            confirmButton = {
                if (!selected.packageName.startsWith("uid:")) TextButton(onClick = {
                    try {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", selected.packageName, null)))
                    } catch (_: Exception) {
                        scope.launch { snackbar.showSnackbar(openFailed) }
                    }
                    selectedPackage = null
                }) { Text(stringResource(R.string.open_app_settings)) }
            },
            dismissButton = { TextButton(onClick = { selectedPackage = null }) { Text(stringResource(R.string.close)) } }
        )
    }
}

@Composable
private fun AppEnergyRow(row: AppDrainRow, onClick: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                    row.icon?.let { Image(it, null, Modifier.size(28.dp)) } ?: Icon(Icons.Outlined.Android, null)
                }
                Column(Modifier.weight(1f)) {
                    Text(row.label, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(row.packageName, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(String.format(Locale.getDefault(), "%.1f mAh", row.energyMah), fontWeight = FontWeight.SemiBold)
                Text(String.format(Locale.getDefault(), "%.1f%%", row.shareOfTotal * 100), style = MaterialTheme.typography.labelLarge)
            }
            LinearProgressIndicator(progress = { row.shareOfTotal.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        }
    }
}

private fun DrainRange.labelRes(): Int = when (this) {
    DrainRange.LAST_24H -> R.string.last_24_hours
    DrainRange.LAST_7D -> R.string.last_7_days
    DrainRange.LAST_30D -> R.string.last_30_days
}
