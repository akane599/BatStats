package app.batstats.battery.util

/** Android uses sentinel values when a fuel-gauge property is not supported. */
internal fun batteryProperty(value: Long): Long? =
    value.takeUnless { it == Long.MIN_VALUE || it == Int.MIN_VALUE.toLong() }

internal fun batteryCurrent(now: Long, average: Long): Long? {
    val instantaneous = batteryProperty(now)
    return if (instantaneous == null || instantaneous == 0L) {
        batteryProperty(average) ?: instantaneous
    } else instantaneous
}

/**
 * Android defines positive current as flowing into the battery, but a number of OEM fuel
 * gauges expose the opposite sign. Cable and charging state are more reliable whenever they
 * unambiguously identify the direction; connected-but-not-charging keeps the reported sign.
 */
internal fun normalizeBatteryCurrent(currentUa: Long?, plugged: Int, status: Int): Long? {
    currentUa ?: return null
    if (currentUa == Long.MIN_VALUE || currentUa == Int.MIN_VALUE.toLong()) return null
    if (currentUa == 0L) return 0L
    val magnitude = kotlin.math.abs(currentUa)
    return when {
        plugged == 0 -> -magnitude
        status == android.os.BatteryManager.BATTERY_STATUS_CHARGING -> magnitude
        else -> currentUa
    }
}

internal fun batteryLevel(level: Int, scale: Int): Int? =
    if (scale > 0 && level in 0..scale) (level.toLong() * 100 / scale).toInt() else null
