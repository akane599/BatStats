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
            val title = monitorStatusTitle(context, reading)
            if (sample == null) return MonitorNotificationText(title, context.getString(R.string.waiting_for_battery), null)
            if (style == NotificationStyle.MINIMAL) {
                return MonitorNotificationText(title, context.getString(R.string.monitoring_battery), null)
            }
            val magnitude = formatMonitorCurrentMagnitude(reading.currentMa)
            val direction = when {
                reading.currentMa == null -> magnitude
                reading.currentMa < 0 -> context.getString(R.string.notif_current_out, magnitude)
                reading.currentMa > 0 -> context.getString(R.string.notif_current_in, magnitude)
                else -> magnitude
            }
            val current = context.getString(R.string.notif_current_reading, direction)
            val temperature = formatMonitorValue(
                reading.temperatureC?.let { if (useFahrenheit) it * 9.0 / 5.0 + 32 else it },
                if (useFahrenheit) "°F" else "°C",
                decimals = 1
            )
            val text = "$current · $temperature"
            val details = if (style == NotificationStyle.DETAILED) buildString {
                appendLine(current)
                appendLine("${context.getString(R.string.widget_temperature)}: $temperature")
                appendLine(context.getString(R.string.notif_voltage, formatMonitorValue(reading.voltageMv?.toDouble(), "mV")))
                appendLine(context.getString(R.string.notif_battery_power, formatMonitorValue(reading.powerMw, "mW")))
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
    return reading.level?.let { context.getString(R.string.battery_level, it, status) } ?: status
}
