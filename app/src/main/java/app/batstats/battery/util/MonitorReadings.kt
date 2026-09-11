package app.batstats.battery.util

import android.os.BatteryManager
import app.batstats.battery.data.db.BatterySample
import java.util.Locale
import kotlin.math.abs

/** Notification values must retain the distinction between an absent sensor and a real zero. */
internal data class MonitorReadings(
    val level: Int?,
    val status: MonitorStatus,
    val currentMa: Double?,
    val voltageMv: Int?,
    val temperatureC: Double?,
    val powerMw: Double?,
    val timestamp: Long?
) {
    companion object {
        fun from(sample: BatterySample?): MonitorReadings {
            val current = sample?.currentNowUa
                ?.takeUnless { it == Long.MIN_VALUE || it == Int.MIN_VALUE.toLong() }
                ?.div(1000.0)
            val voltage = sample?.voltageMv?.takeIf { it > 0 }
            return MonitorReadings(
                level = sample?.levelPercent?.takeIf { it in 0..100 },
                status = when {
                    sample == null -> MonitorStatus.UNKNOWN
                    sample.status == BatteryManager.BATTERY_STATUS_FULL -> MonitorStatus.FULL
                    sample.status == BatteryManager.BATTERY_STATUS_CHARGING -> MonitorStatus.CHARGING
                    sample.plugged != 0 -> MonitorStatus.CONNECTED
                    else -> MonitorStatus.ON_BATTERY
                },
                currentMa = current,
                voltageMv = voltage,
                temperatureC = sample?.temperatureDeciC?.div(10.0),
                powerMw = if (current != null && voltage != null) abs(current) * voltage / 1000.0 else null,
                timestamp = sample?.timestamp
            )
        }
    }
}

internal enum class MonitorStatus { UNKNOWN, CHARGING, FULL, CONNECTED, ON_BATTERY }

internal fun formatMonitorValue(value: Double?, unit: String, decimals: Int = 0): String =
    if (value == null || !value.isFinite()) "—"
    else String.format(Locale.getDefault(), "%.${decimals}f %s", value, unit)

/** Keep sub-milliamp readings instead of truncating them to a misleading zero. */
internal fun formatMonitorCurrentMagnitude(currentMa: Double?): String = when {
    currentMa == null || !currentMa.isFinite() -> "—"
    currentMa != 0.0 && abs(currentMa) < 0.1 -> "< 0.1 mA"
    else -> formatMonitorValue(abs(currentMa), "mA", if (abs(currentMa) < 10.0) 1 else 0)
}
