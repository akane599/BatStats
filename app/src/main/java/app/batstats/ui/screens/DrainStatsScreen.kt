package app.batstats.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.batstats.R
import app.batstats.battery.drain.DrainState
import app.batstats.battery.drain.formatBatteryPercentRate
import app.batstats.battery.drain.formatDrainRate
import app.batstats.battery.drain.formatDrainRateWithPercent
import app.batstats.battery.drain.formatDuration
import app.batstats.battery.drain.formatMahWithPercent
import app.batstats.viewmodel.DrainStatsViewModel
import app.batstats.viewmodel.DashboardViewModel
import java.util.Locale
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DrainStatsScreen(
    onBack: () -> Unit,
    vm: DrainStatsViewModel = koinViewModel(),
    monitorVm: DashboardViewModel = koinViewModel()
) {
    var confirmReset by rememberSaveable { mutableStateOf(false) }
    val drainState by vm.drainState.collectAsStateWithLifecycle()
    val isTracking by vm.isTracking.collectAsStateWithLifecycle()
    val isMonitoring by monitorVm.isMonitoring.collectAsStateWithLifecycle()
    val monitorBusy by monitorVm.busy.collectAsStateWithLifecycle()
    val monitorError by monitorVm.error.collectAsStateWithLifecycle()
    val snapshots by vm.snapshots.collectAsStateWithLifecycle()
    
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.drain_statistics))
                        Text(
                            if (isTracking) "Tracking active" else "Tracking paused",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (isTracking) 
                                MaterialTheme.colorScheme.primary 
                            else 
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { confirmReset = true }) {
                        Icon(Icons.Outlined.RestartAlt, "Reset Session")
                    }
                    IconButton(
                        onClick = monitorVm::toggleMonitoring,
                        enabled = !monitorBusy
                    ) {
                        Icon(
                            if (isMonitoring) Icons.Default.Pause else Icons.Default.PlayArrow,
                            stringResource(if (isMonitoring) R.string.pause_monitoring else R.string.start_monitoring)
                        )
                    }
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text(stringResource(R.string.drain_estimate_note), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            monitorError?.let { message ->
                item {
                    Text(stringResource(message), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = monitorVm::clearError) { Text(stringResource(R.string.dismiss)) }
                }
            }
            item {
                CurrentStateCard(drainState)
            }
            item {
                DrainRatesCard(drainState)
            }
            item {
                ScreenBreakdownCard(drainState)
            }
            item {
                ScreenOffSleepBreakdownCard(drainState)
            }
            item {
                SessionSummaryCard(drainState)
            }
            item {
                DrainHistoryCard(snapshots)
            }
            
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.reset_drain_title)) },
            text = { Text(stringResource(R.string.reset_drain_body)) },
            confirmButton = { TextButton(onClick = { confirmReset = false; vm.resetSession() }) { Text(stringResource(R.string.reset_action)) } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text(stringResource(R.string.cancel)) } }
        )
    }
}

@Composable
private fun CurrentStateCard(state: DrainState) {
    val knownLevel = state.hasBatteryReading && state.batteryLevel in 0..100
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Battery Level Circle
            Box(
                modifier = Modifier.size(80.dp),
                contentAlignment = Alignment.Center
            ) {
                val progress by animateFloatAsState(
                    targetValue = if (knownLevel) state.batteryLevel / 100f else 0f,
                    animationSpec = tween(1000),
                    label = "battery_progress"
                )
                
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val strokeWidth = 8.dp.toPx()
                    val radius = (size.minDimension - strokeWidth) / 2
                    
                    drawCircle(
                        color = Color.Gray.copy(alpha = 0.3f),
                        radius = radius,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(strokeWidth)
                    )
                    
                    if (knownLevel) drawArc(
                        color = when {
                            state.batteryLevel < 20 -> Color(0xFFE53935)
                            state.batteryLevel < 50 -> Color(0xFFFF9800)
                            else -> Color(0xFF4CAF50)
                        },
                        startAngle = -90f,
                        sweepAngle = progress * 360f,
                        useCenter = false,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(
                            strokeWidth,
                            cap = StrokeCap.Round
                        )
                    )
                }
                
                Text(
                    if (knownLevel) "${state.batteryLevel}%" else "—",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
            
            Spacer(Modifier.width(20.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                val (icon, label, color) = when {
                    !state.hasBatteryReading -> Triple(Icons.Outlined.BatteryUnknown, stringResource(R.string.unknown_value), MaterialTheme.colorScheme.onSurfaceVariant)
                    state.isCharging -> Triple(Icons.Default.BatteryChargingFull, stringResource(R.string.charging), MaterialTheme.colorScheme.primary)
                    state.isPowered -> Triple(Icons.Default.Power, stringResource(R.string.charging_paused), MaterialTheme.colorScheme.primary)
                    state.isScreenOn -> Triple(Icons.Default.Smartphone, stringResource(R.string.screen_on), Color(0xFFFF9800))
                    state.isDozing -> Triple(Icons.Default.BedtimeOff, stringResource(R.string.screen_off_doze), Color(0xFF9C27B0))
                    else -> Triple(Icons.Default.PhonelinkErase, stringResource(R.string.screen_off), Color(0xFF2196F3))
                }
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        icon,
                        null,
                        modifier = Modifier.size(24.dp),
                        tint = color
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        label,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                }
                
                Spacer(Modifier.height(4.dp))
                
                Text(
                    "Session: ${formatDuration(state.totalTimeMs)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Text(
                    "Total drain: ${formatMahWithPercent(state.totalDrainMah, state.capacityMah)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun DrainRatesCard(state: DrainState) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Drain Rates",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
            
            Spacer(Modifier.height(16.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                    DrainRateItem(
                        capacityMah = state.capacityMah,
                        icon = Icons.Outlined.Smartphone,
                        label = stringResource(R.string.screen_on),
                        rate = state.screenOnDrainRate,
                        color = Color(0xFFFF9800)
                    )
                    DrainRateItem(
                        capacityMah = state.capacityMah,
                        icon = Icons.Outlined.PhonelinkErase,
                        label = stringResource(R.string.screen_off),
                        rate = state.screenOffDrainRate,
                        color = Color(0xFF2196F3)
                    )
            }
        }
    }
}

@Composable
private fun RowScope.DrainRateItem(
    capacityMah: Double,
    icon: ImageVector,
    label: String,
    rate: Double?,
    color: Color
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        // Two columns on phones leave room for rates and larger accessibility text.
        modifier = Modifier.weight(1f)
    ) {
        Surface(
            shape = CircleShape,
            color = color.copy(alpha = 0.15f),
            modifier = Modifier.size(48.dp)
        ) {
            Icon(
                icon,
                null,
                modifier = Modifier.padding(12.dp),
                tint = color
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            formatDrainRate(rate),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        val percent = formatBatteryPercentRate(rate, capacityMah)
        if (percent.isNotEmpty()) {
            Text(
                percent,
                style = MaterialTheme.typography.labelSmall,
                color = color,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

@Composable
private fun ScreenBreakdownCard(state: DrainState) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Screen Time Breakdown",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
            
            Spacer(Modifier.height(16.dp))
            
            // Screen On
            DrainStatRow(
                icon = Icons.Outlined.Smartphone,
                label = stringResource(R.string.screen_on),
                capacityMah = state.capacityMah,
                drainRate = state.screenOnDrainRate,
                drainTotal = state.screenOnDrainMah,
                time = state.screenOnTimeMs,
                color = Color(0xFFFF9800),
                percentage = state.screenOnPercentage
            )
            
            Spacer(Modifier.height(12.dp))
            
            // Screen Off
            DrainStatRow(
                icon = Icons.Outlined.PhonelinkErase,
                label = stringResource(R.string.screen_off),
                capacityMah = state.capacityMah,
                drainRate = state.screenOffDrainRate,
                drainTotal = state.screenOffDrainMah,
                time = state.screenOffTimeMs,
                color = Color(0xFF2196F3),
                percentage = state.screenOffPercentage
            )
        }
    }
}

@Composable
private fun DrainStatRow(
    capacityMah: Double,
    icon: ImageVector,
    label: String,
    drainRate: Double?,
    drainTotal: Double?,
    time: Long,
    color: Color,
    percentage: Float
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Text(
                formatDrainRateWithPercent(drainRate, capacityMah),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = color,
                modifier = Modifier.weight(1.25f),
                textAlign = TextAlign.End
            )
        }

        Spacer(Modifier.height(4.dp))
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                formatDuration(time),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                formatMahWithPercent(drainTotal, capacityMah),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        
        Spacer(Modifier.height(6.dp))
        
        LinearWavyProgressIndicator(
            progress = { (percentage / 100f).coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = color,
            trackColor = color.copy(alpha = 0.2f)
        )
    }
}

@Composable
private fun ScreenOffSleepBreakdownCard(state: DrainState) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth().testTag("screen_off_breakdown"),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.screen_off_sleep_breakdown), style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium)
            if (state.screenOffTimeMs <= 0L) {
                Text(stringResource(R.string.no_screen_off_recorded), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(stringResource(R.string.screen_off_sleep_note), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                ScreenOffDurationRow(
                    label = stringResource(R.string.deep_sleep),
                    icon = Icons.Outlined.NightsStay,
                    timeMs = state.deepSleepTimeMs,
                    screenOffTimeMs = state.screenOffTimeMs,
                    color = Color(0xFF4CAF50)
                )
                ScreenOffDurationRow(
                    label = stringResource(R.string.awake),
                    icon = Icons.Outlined.WbSunny,
                    timeMs = state.awakeTimeMs,
                    screenOffTimeMs = state.screenOffTimeMs,
                    color = Color(0xFFE91E63)
                )
            }
        }
    }
}

@Composable
private fun ScreenOffDurationRow(label: String, icon: ImageVector, timeMs: Long, screenOffTimeMs: Long, color: Color) {
    val share = (timeMs.toFloat() / screenOffTimeMs).coerceIn(0f, 1f)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(formatDuration(timeMs), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
        }
        Text(stringResource(R.string.screen_off_time_share, share * 100f),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LinearProgressIndicator(progress = { share }, modifier = Modifier.fillMaxWidth(), color = color)
    }
}

@Composable
private fun SessionSummaryCard(state: DrainState) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Session Summary",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
            
            Spacer(Modifier.height(16.dp))
            
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                SummaryItem(
                    label = stringResource(R.string.duration),
                    value = formatDuration(state.totalTimeMs),
                    icon = Icons.Outlined.Timer
                )
                SummaryItem(
                    label = stringResource(R.string.total_drain),
                    value = formatMahWithPercent(state.totalDrainMah, state.capacityMah),
                    icon = Icons.Outlined.BatteryAlert
                )
                SummaryItem(
                    label = stringResource(R.string.avg_rate),
                    value = formatDrainRateWithPercent(state.averageDrainRate, state.capacityMah),
                    icon = Icons.Outlined.Speed
                )
            }
        }
    }
}

@Composable
private fun SummaryItem(
    label: String,
    value: String,
    icon: ImageVector
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1.4f), textAlign = TextAlign.End)
    }
}

@Composable
private fun DrainHistoryCard(snapshots: List<app.batstats.battery.drain.DrainSnapshot>) {
    val knownSnapshots = remember(snapshots) { snapshots.filter { it.batteryLevel in 0..100 } }
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.battery_level_history),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
            
            Spacer(Modifier.height(16.dp))
            
            AnimatedVisibility(
                visible = knownSnapshots.isNotEmpty(),
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                val values = knownSnapshots.map { it.batteryLevel.toFloat() }
                
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                ) {
                    if (values.isEmpty()) return@Canvas
                    
                    val min = values.minOrNull() ?: 0f
                    val max = values.maxOrNull() ?: 100f
                    val range = (max - min).coerceAtLeast(1f)
                    
                    val stepX = size.width / (values.size - 1).coerceAtLeast(1)
                    
                    // Draw gradient fill
                    val path = androidx.compose.ui.graphics.Path().apply {
                        values.forEachIndexed { i, v ->
                            val x = i * stepX
                            val y = size.height - ((v - min) / range) * size.height
                            if (i == 0) moveTo(x, y) else lineTo(x, y)
                        }
                        lineTo(size.width, size.height)
                        lineTo(0f, size.height)
                        close()
                    }
                    
                    drawPath(
                        path,
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color(0xFF4CAF50).copy(alpha = 0.3f),
                                Color.Transparent
                            )
                        )
                    )
                    
                    // Draw line
                    var prev: Offset? = null
                    values.forEachIndexed { i, v ->
                        val x = i * stepX
                        val y = size.height - ((v - min) / range) * size.height
                        val current = Offset(x, y)
                        
                        prev?.let { pr ->
                            drawLine(
                                color = Color(0xFF4CAF50),
                                start = pr,
                                end = current,
                                strokeWidth = 3.dp.toPx(),
                                cap = StrokeCap.Round
                            )
                        }
                        prev = current
                    }
                }
            }
            
            AnimatedVisibility(
                visible = knownSnapshots.isEmpty(),
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Collecting data...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
