package app.batstats.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.batstats.R
import app.batstats.battery.drain.formatBatteryPercent
import app.batstats.viewmodel.AppDrainRow
import app.batstats.viewmodel.AppDrainViewModel
import app.batstats.viewmodel.DrainRange
import app.batstats.viewmodel.DrainSource
import org.koin.androidx.compose.koinViewModel
import java.util.Locale

/**
 * What each app has drained over a window of hours or days.
 *
 * The rest of the app reports since the last full charge, which cannot answer "what ate my
 * battery overnight". The hourly buckets behind this screen can.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppDrainScreen(
    onBack: () -> Unit,
    vm: AppDrainViewModel = koinViewModel()
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val range by vm.range.collectAsStateWithLifecycle()
    val showSystem by vm.showSystemApps.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_drain)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DrainRange.entries.forEach { option ->
                    FilterChip(
                        selected = range == option,
                        onClick = { vm.setRange(option) },
                        label = { Text(stringResource(option.labelRes())) }
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = showSystem,
                    onClick = { vm.toggleSystemApps() },
                    label = { Text(stringResource(R.string.system_apps)) }
                )
            }

            when {
                state.loading -> CentredMessage { CircularProgressIndicator() }

                state.rows.isEmpty() -> CentredMessage {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            stringResource(R.string.no_app_drain_yet),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            stringResource(R.string.no_app_drain_explanation),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 32.dp)
                        )
                    }
                }

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        TotalCard(
                            totalMah = state.totalMah,
                            appCount = state.rows.size,
                            source = state.source
                        )
                    }
                    items(state.rows, key = { it.packageName }) { row ->
                        AppDrainRowItem(row, state.totalMah)
                    }
                }
            }
        }
    }
}

@Composable
private fun CentredMessage(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun TotalCard(totalMah: Double, appCount: Int, source: DrainSource?) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                String.format(Locale.getDefault(), "%.0f mAh", totalMah),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                stringResource(R.string.app_count, appCount),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (source != null) {
                Text(
                    stringResource(
                        when (source) {
                            DrainSource.MEASURED -> R.string.drain_source_measured
                            DrainSource.ESTIMATED -> R.string.drain_source_estimated
                        }
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun AppDrainRowItem(row: AppDrainRow, totalMah: Double) {
    // A bar relative to the heaviest app would make everything look alike; relative to the
    // total, the shape of the list is the answer.
    val share by animateFloatAsState(row.shareOfTotal, label = "share")

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            val icon = row.icon
            if (icon != null) {
                Image(icon, contentDescription = null, Modifier.size(28.dp))
            } else {
                Icon(
                    Icons.Outlined.Android,
                    contentDescription = null,
                    Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            Text(
                row.label,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Box(
                Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Box(
                    Modifier.fillMaxWidth(share.coerceIn(0f, 1f)).height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(horizontalAlignment = Alignment.End) {
            Text(
                String.format(Locale.getDefault(), "%.1f mAh", row.energyMah),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            val percent = formatBatteryPercent(row.energyMah, totalMah)
            if (percent.isNotEmpty()) {
                Text(
                    percent,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun DrainRange.labelRes(): Int = when (this) {
    DrainRange.LAST_24H -> R.string.last_24_hours
    DrainRange.LAST_7D -> R.string.last_7_days
    DrainRange.LAST_30D -> R.string.last_30_days
}
