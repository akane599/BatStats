package app.batstats.ui.screens

import android.os.BatteryManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.batstats.R
import app.batstats.battery.data.BatteryRepository
import app.batstats.battery.data.db.BatteryCurrentPoint
import app.batstats.battery.data.db.ChargeSession
import app.batstats.battery.data.db.SessionType
import app.batstats.battery.drain.formatLevelRatePerHour
import app.batstats.battery.util.TimeEstimator
import app.batstats.settings.useFahrenheit
import app.batstats.ui.components.StatusMessage
import app.batstats.viewmodel.DashboardViewModel
import org.koin.androidx.compose.koinViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun DashboardScreen(
    onOpenHistory: () -> Unit,
    onOpenAlarms: () -> Unit,
    vm: DashboardViewModel = koinViewModel()
) {
    val rt by vm.realtime.collectAsStateWithLifecycle()
    val session by vm.activeSession.collectAsStateWithLifecycle()
    val monitoring by vm.isMonitoring.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val points by vm.recentSamples.collectAsStateWithLifecycle(initialValue = emptyList())
    var confirmEnd by rememberSaveable { mutableStateOf(false) }

    LifecycleResumeEffect(Unit) {
        vm.refreshReading()
        onPauseOrDispose { }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.nav_battery), fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = onOpenHistory) {
                        Icon(Icons.Outlined.History, stringResource(R.string.history))
                    }
                    IconButton(onClick = onOpenAlarms) {
                        Icon(Icons.Outlined.Notifications, stringResource(R.string.alarms))
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).testTag("dashboard_list"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item(key = "battery") { BatteryOverviewCard(rt, settings.showCurrentInMa) }
            item(key = "monitor") {
                MonitoringCard(monitoring, busy, vm::toggleMonitoring)
            }
            error?.let { message ->
                item(key = "error") {
                    StatusMessage(stringResource(R.string.action_failed), stringResource(message),
                        action = stringResource(R.string.dismiss), onAction = vm::clearError, error = true)
                }
            }
            item(key = "metrics") { BatteryMetrics(rt, settings.useFahrenheit) }
            item(key = "session") {
                SessionControls(session, monitoring && !busy, vm::startManualSession, { confirmEnd = true }, onOpenHistory)
            }
            item(key = "chart") {
                val range = stringResource(when (settings.chartTimeRangeIndex) {
                    0 -> R.string.range_15m
                    2 -> R.string.range_6h
                    3 -> R.string.range_24h
                    4 -> R.string.range_7d
                    else -> R.string.range_1h
                })
                CurrentTrendCard(points, range, monitoring)
            }
        }
    }
    if (confirmEnd) {
        AlertDialog(
            onDismissRequest = { confirmEnd = false },
            title = { Text(stringResource(R.string.finish_session)) },
            text = { Text(stringResource(R.string.finish_session_body)) },
            confirmButton = { TextButton(onClick = { confirmEnd = false; vm.endSession() }) { Text(stringResource(R.string.end)) } },
            dismissButton = { TextButton(onClick = { confirmEnd = false }) { Text(stringResource(R.string.cancel)) } }
        )
    }
}

@Composable
internal fun BatteryOverviewCard(rt: BatteryRepository.Realtime, showCurrentInMa: Boolean) {
    val colors = MaterialTheme.colorScheme
    val known = rt.sample != null
    val status = stringResource(when {
        !known -> R.string.waiting_for_battery
        rt.sample?.status == BatteryManager.BATTERY_STATUS_FULL -> R.string.battery_full
        rt.sample?.status == BatteryManager.BATTERY_STATUS_CHARGING -> R.string.charging
        rt.plugged != 0 -> R.string.charging_paused
        else -> R.string.discharging
    })
    val current = if (rt.sample?.currentNowUa == null) "—" else {
        val rate = rt.sample?.let { formatLevelRatePerHour(rt.currentMa, TimeEstimator.capacityMahFor(it)) }
        if (showCurrentInMa) "${rt.currentMa} mA" else rate ?: "${rt.currentMa} mA"
    }
    val progress by animateFloatAsState(if (known) rt.level.coerceIn(0, 100) / 100f else 0f, label = "battery_level")
    Card(colors = CardDefaults.cardColors(containerColor = colors.primaryContainer), shape = RoundedCornerShape(28.dp)) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(if (known) "${rt.level}%" else "—", style = MaterialTheme.typography.displayLarge,
                        fontWeight = FontWeight.Bold, modifier = Modifier.testTag("battery_level"))
                    Text(status, style = MaterialTheme.typography.titleMedium)
                }
                Icon(if (rt.plugged != 0) Icons.Outlined.BatteryChargingFull else Icons.Outlined.BatteryFull,
                    null, Modifier.size(48.dp))
            }
            LinearProgressIndicator(
                progress = { progress }, modifier = Modifier.fillMaxWidth().height(8.dp),
                color = if (known && rt.level < 20 && rt.plugged == 0) colors.error else colors.primary,
                trackColor = colors.onPrimaryContainer.copy(alpha = .12f)
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Column {
                    Text(stringResource(R.string.current), style = MaterialTheme.typography.labelMedium)
                    Text(current, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                }
                TimeEstimator.etaString(rt.sample)?.let { eta ->
                    Text(eta, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.align(Alignment.CenterVertically))
                }
            }
            rt.sample?.let { sample ->
                val locale = LocalConfiguration.current.locales[0]
                val formatter = remember(locale) { DateTimeFormatter.ofPattern("HH:mm:ss", locale) }
                val time = Instant.ofEpochMilli(sample.timestamp).atZone(ZoneId.systemDefault()).format(formatter)
                Text(stringResource(R.string.updated, time), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun MonitoringCard(running: Boolean, busy: Boolean, onToggle: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.monitoring), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(if (running) R.string.monitoring_active_body else R.string.monitoring_paused_body),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onToggle, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("monitor_toggle")) {
                Icon(if (running) Icons.Default.Pause else Icons.Default.PlayArrow, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(if (busy) R.string.please_wait else if (running) R.string.pause_monitoring else R.string.start_monitoring))
            }
        }
    }
}

@Composable
private fun BatteryMetrics(rt: BatteryRepository.Realtime, fahrenheit: Boolean) {
    val sample = rt.sample
    val locale = LocalConfiguration.current.locales[0]
    val temp = sample?.temperatureDeciC?.let { value ->
        val c = value / 10.0
        String.format(locale, if (fahrenheit) "%.1f °F" else "%.1f °C", if (fahrenheit) c * 9 / 5 + 32 else c)
    } ?: "—"
    val health = stringResource(when (sample?.health) {
        BatteryManager.BATTERY_HEALTH_GOOD -> R.string.health_good
        BatteryManager.BATTERY_HEALTH_OVERHEAT -> R.string.health_overheat
        BatteryManager.BATTERY_HEALTH_COLD -> R.string.health_cold
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> R.string.health_overvoltage
        BatteryManager.BATTERY_HEALTH_DEAD, BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> R.string.health_failure
        else -> R.string.unknown_value
    })
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricTile(stringResource(R.string.temperature), temp, Icons.Outlined.Thermostat, Modifier.weight(1f))
            MetricTile(stringResource(R.string.voltage), sample?.voltageMv?.let { "$it mV" } ?: "—", Icons.Outlined.ElectricBolt, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val power = if (sample?.currentNowUa != null && sample.voltageMv != null) String.format(locale, "%.0f mW", rt.powerMw) else "—"
            MetricTile(stringResource(R.string.power_label), power, Icons.Outlined.Speed, Modifier.weight(1f))
            MetricTile(stringResource(R.string.health), health, Icons.Outlined.FavoriteBorder, Modifier.weight(1f))
        }
    }
}

@Composable
private fun MetricTile(label: String, value: String, icon: ImageVector, modifier: Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SessionControls(session: ChargeSession?, enabled: Boolean, onStart: (SessionType) -> Unit, onEnd: () -> Unit, onHistory: () -> Unit) {
    OutlinedCard {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.session_tracking), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(if (session == null) R.string.session_idle_body else if (session.autoStarted) R.string.session_auto_body else R.string.session_manual_body),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (session == null) {
                    OutlinedButton(onClick = { onStart(SessionType.CHARGE) }, enabled = enabled) { Text(stringResource(R.string.charge)) }
                    OutlinedButton(onClick = { onStart(SessionType.DISCHARGE) }, enabled = enabled) { Text(stringResource(R.string.discharge)) }
                } else {
                    FilledTonalButton(onClick = onEnd, enabled = enabled) { Text(stringResource(R.string.finish_session)) }
                }
                TextButton(onClick = onHistory) { Text(stringResource(R.string.history)) }
            }
        }
    }
}

@Composable
private fun CurrentTrendCard(points: List<BatteryCurrentPoint>, range: String, running: Boolean) {
    val values = remember(points) { points.filter { it.currentMa?.isFinite() == true } }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.current_trend, range), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(if (running) R.string.chart_live_hint else R.string.chart_paused_hint),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (values.isEmpty()) {
                Text(stringResource(R.string.no_chart_data), Modifier.padding(vertical = 24.dp))
            } else {
                val min = values.minOf { it.currentMa!! }.toFloat()
                val max = values.maxOf { it.currentMa!! }.toFloat()
                val locale = LocalConfiguration.current.locales[0]
                val description = stringResource(R.string.chart_accessibility, values.size,
                    String.format(locale, "%.0f", min), String.format(locale, "%.0f", max))
                Text(String.format(locale, "%.0f … %.0f mA", min, max), style = MaterialTheme.typography.labelMedium)
                val color = MaterialTheme.colorScheme.primary
                val grid = MaterialTheme.colorScheme.outlineVariant
                Canvas(Modifier.fillMaxWidth().height(140.dp).semantics { contentDescription = description }) {
                    val span = (values.last().timestamp - values.first().timestamp).coerceAtLeast(1L)
                    val yRange = (max - min).takeIf { it > .01f } ?: 1f
                    val inset = 4.dp.toPx()
                    fun point(i: Int): Offset {
                        val x = if (values.size == 1) size.width / 2 else (values[i].timestamp - values.first().timestamp).toFloat() / span * size.width
                        val y = if (max == min) size.height / 2 else inset + (1 - (values[i].currentMa!!.toFloat() - min) / yRange) * (size.height - 2 * inset)
                        return Offset(x, y)
                    }
                    repeat(3) { index ->
                        val y = inset + (size.height - 2 * inset) * index / 2
                        drawLine(grid, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                    }
                    val path = Path()
                    values.indices.forEach { i ->
                        val p = point(i)
                        if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                    }
                    drawPath(path, color, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx(), cap = StrokeCap.Round))
                    drawCircle(color, 4.dp.toPx(), point(values.lastIndex))
                }
            }
        }
    }
}
