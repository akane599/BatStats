package app.batstats.battery.util

import android.content.Context
import android.text.format.DateFormat
import app.batstats.R
import app.batstats.battery.data.db.BatterySample
import app.batstats.settings.NotificationStyle
import java.util.Date

internal data class MonitorNotificationText(val title: String, val text: String, val details: String?) {
    companion object {
        fun from(
            context: Context,
            sample: BatterySample?,
            style: NotificationStyle,
            useFahrenheit: Boolean
        ): MonitorNotificationText {
            val reading = MonitorReadings.from(sample)
            val title = context.getString(R.string.monitoring_battery)
            if (sample == null) return MonitorNotificationText(title, context.getString(R.string.waiting_for_battery), null)
            val level = context.getString(R.string.notif_level, reading.level?.let { "$it%" } ?: "—")
            if (style == NotificationStyle.MINIMAL) return MonitorNotificationText(title, level, null)
            val magnitude = formatMonitorCurrentMagnitude(reading.currentMa)
            val direction = when {
                reading.currentMa == null -> magnitude
                reading.currentMa < 0 -> context.getString(R.string.notif_current_out, magnitude)
                reading.currentMa > 0 -> context.getString(R.string.notif_current_in, magnitude)
                else -> magnitude
            }
            val temperature = formatMonitorValue(
                reading.temperatureC?.let { if (useFahrenheit) it * 9.0 / 5.0 + 32 else it },
                if (useFahrenheit) "°F" else "°C",
                decimals = 1
            )
            val voltage = formatMonitorValue(reading.voltageMv?.toDouble(), "mV")
            // Keep the original compact layout; only the ambiguous signed value is replaced
            // by an explicit in/out direction and absent sensors remain unavailable.
            val text = "$level • $direction • $voltage"
            val details = if (style == NotificationStyle.DETAILED) buildString {
                appendLine(text)
                appendLine(context.getString(R.string.notif_temperature_and_power, temperature,
                    formatMonitorValue(reading.powerMw, "mW")))
                appendLine(context.getString(R.string.notif_status, monitorStatusTitle(context, reading)))
                append(context.getString(R.string.notif_last_reading, DateFormat.getTimeFormat(context).format(Date(sample.timestamp))))
            } else null
            return MonitorNotificationText(title, text, details)
        }
    }
}

internal fun monitorStatusTitle(context: Context, reading: MonitorReadings): String {
    val status = context.getString(when (reading.status) {
        MonitorStatus.UNKNOWN -> R.string.monitoring_battery
        MonitorStatus.CHARGING -> R.string.charging
        MonitorStatus.FULL -> R.string.battery_full
        MonitorStatus.CONNECTED -> R.string.charging_paused
        MonitorStatus.ON_BATTERY -> R.string.discharging
    })
    return status
}
