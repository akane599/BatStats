package app.batstats.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.batstats.R
import app.batstats.battery.data.db.SessionType
import app.batstats.viewmodel.SessionDetailsViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun SessionDetailsScreen(sessionId: String, onBack: () -> Unit, vm: SessionDetailsViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val fahrenheit by vm.fahrenheit.collectAsStateWithLifecycle()
    val formatter = remember(Locale.getDefault()) { DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.getDefault()) }
    fun date(at: Long) = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(formatter)
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.session_details)) }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
        })
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (ui.loading) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else if (ui.type == null) {
                Text(stringResource(R.string.no_data_available))
            } else {
                Card {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val type = stringResource(if (ui.type == SessionType.CHARGE) R.string.charging else R.string.discharging)
                        Text("$type · ${ui.levelRange}", style = MaterialTheme.typography.titleLarge)
                        Text(stringResource(R.string.start_time, date(ui.start)))
                        Text(stringResource(R.string.end_time, ui.end?.let(::date) ?: stringResource(R.string.now_label)))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            ui.capacityMah?.let { Text(stringResource(R.string.milliamp_hours_approx, it)) }
                            ui.avgCurrent?.let { Text(stringResource(R.string.milliamp_average, (it / 1000).toInt())) }
                        }
                    }
                }
                val times = ui.points.map { it.timestamp }
                SessionChart("${stringResource(R.string.current)} (mA)", times, ui.points.map { it.currentMa }, MaterialTheme.colorScheme.primary)
                SessionChart("${stringResource(R.string.voltage)} (mV)", times, ui.points.map { it.voltageMv }, MaterialTheme.colorScheme.tertiary)
                SessionChart("${stringResource(R.string.temperature)} (${if (fahrenheit) "°F" else "°C"})", times,
                    ui.points.map { it.tempC?.let { c -> if (fahrenheit) c * 9 / 5 + 32 else c } }, MaterialTheme.colorScheme.secondary)
            }
        }
    }
}

@Composable
private fun SessionChart(title: String, times: List<Long>, values: List<Double?>, color: Color) {
    val known = values.filterNotNull().filter(Double::isFinite)
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (known.isEmpty()) {
                Text(stringResource(R.string.no_data_available), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                val min = known.min()
                val max = known.max()
                val label = String.format(Locale.getDefault(), "%.1f – %.1f", min, max)
                Text(label, style = MaterialTheme.typography.labelMedium)
                Canvas(Modifier.fillMaxWidth().height(150.dp).semantics { contentDescription = "$title: $label" }) {
                    val span = (times.last() - times.first()).coerceAtLeast(1).toDouble()
                    val range = max - min
                    var previous: Offset? = null
                    values.forEachIndexed { index, value ->
                        if (value == null || !value.isFinite()) { previous = null; return@forEachIndexed }
                        val x = if (times.size == 1) size.width / 2 else ((times[index] - times.first()) / span * size.width).toFloat()
                        val y = if (range > 0.000001) (size.height * (1 - (value - min) / range)).toFloat() else size.height / 2
                        val point = Offset(x, y.coerceIn(3.dp.toPx(), size.height - 3.dp.toPx()))
                        previous?.let { drawLine(color, it, point, strokeWidth = 2.dp.toPx()) }
                            ?: drawCircle(color, 2.dp.toPx(), point)
                        previous = point
                    }
                }
                val format = remember(Locale.getDefault()) { DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.getDefault()) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    listOf(times.first(), times.last()).forEach {
                        Text(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(format), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}
