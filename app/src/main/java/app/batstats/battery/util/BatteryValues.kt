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

internal fun batteryLevel(level: Int, scale: Int): Int? =
    if (scale > 0 && level in 0..scale) (level.toLong() * 100 / scale).toInt() else null
