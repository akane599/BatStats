package app.batstats.battery.drain

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable

/**
 * Tracks drain rates across different device states.
 */
@Serializable
@Parcelize
data class DrainState(
    val timestamp: Long = System.currentTimeMillis(),
    
    // Current battery level
    val batteryLevel: Int = -1,
    val batteryLevelMah: Double? = null,

    /**
     * Full battery capacity, used to express drain as a share of the battery.
     * 0 when it could not be established - percentages are then omitted rather than
     * computed against a guess.
     */
    val capacityMah: Double = 0.0,
    
    // Device state
    val isScreenOn: Boolean = false,
    val isCharging: Boolean = false,
    val isPowered: Boolean = false,
    val hasBatteryReading: Boolean = false,
    val isDozing: Boolean = false,
    
    // Cumulative drain by state (mAh)
    val screenOnDrainMah: Double? = 0.0,
    val screenOffDrainMah: Double? = 0.0,
    
    // Time spent in each state (ms)
    val screenOnTimeMs: Long = 0L,
    val screenOffTimeMs: Long = 0L,
    val deepSleepTimeMs: Long = 0L,
    val awakeTimeMs: Long = 0L,
    
    // Drain rates (mA) - calculated averages
    val screenOnDrainRate: Double? = null,
    val screenOffDrainRate: Double? = null,
    
    // Session tracking
    val sessionElapsedMs: Long = 0L,
    val sessionStartTime: Long = System.currentTimeMillis(),
    val lastUpdateTime: Long = System.currentTimeMillis()
) : Parcelable {
    
    val totalDrainMah: Double?
        get() = if (screenOnDrainMah != null && screenOffDrainMah != null)
            screenOnDrainMah + screenOffDrainMah else null
    
    /** Monotonic monitoring duration, including powered time; frozen when paused. */
    val totalTimeMs: Long
        get() = sessionElapsedMs.coerceAtLeast(0L)

    /**
     * Time actually accounted for. Powered stretches are excluded, even when battery
     * protection pauses charging, so this is what the shares below divide by.
     */
    val trackedTimeMs: Long
        get() = screenOnTimeMs + screenOffTimeMs

    val averageDrainRate: Double?
        get() = drainRateOver(totalDrainMah, trackedTimeMs)

    // The buckets reconcile by construction, so these are already within range; the clamp
    // is a guard against a partially-updated state rather than the arithmetic.
    val screenOnPercentage: Float
        get() = if (trackedTimeMs > 0) {
            (screenOnTimeMs.toFloat() / trackedTimeMs * 100f).coerceIn(0f, 100f)
        } else 0f

    val screenOffPercentage: Float
        get() = if (trackedTimeMs > 0) {
            (screenOffTimeMs.toFloat() / trackedTimeMs * 100f).coerceIn(0f, 100f)
        } else 0f

    val deepSleepPercentage: Float
        get() = if (screenOffTimeMs > 0) {
            (deepSleepTimeMs.toFloat() / screenOffTimeMs * 100f).coerceIn(0f, 100f)
        } else 0f
}

/**
 * A drain rate is only meaningful once enough time has been observed. Extrapolating from a
 * couple of seconds turns a single charge-counter step into thousands of mA - a two-second
 * screen-off window really did read as "12999 mA · 260%/h".
 */
const val MIN_RATE_WINDOW_MS = 120_000L

/** Null means insufficient observation or an unavailable/reset charge counter. */
fun drainRateOver(drainMah: Double?, timeMs: Long): Double? =
    if (timeMs >= MIN_RATE_WINDOW_MS && drainMah != null && drainMah.isFinite() && drainMah >= 0.0)
        drainMah / (timeMs / 3600000.0) else null

/**
 * Snapshot of drain metrics at a point in time
 */
@Serializable
data class DrainSnapshot(
    val timestamp: Long,
    val batteryLevel: Int,
    val batteryMah: Double?,
    val currentMa: Int?,
    val isScreenOn: Boolean,
    val isCharging: Boolean,
    val isDozing: Boolean,
    val cpuAwakeTimeMs: Long,
    val deepSleepTimeMs: Long
)


fun formatDuration(ms: Long): String {
    val seconds = ms.coerceAtLeast(0L) / 1000
    val minutes = seconds / 60
    val hours = minutes / 60
    val days = hours / 24
    
    return when {
        days > 0 -> "${days}d ${hours % 24}h"
        hours > 0 -> "${hours}h ${minutes % 60}m"
        minutes > 0 -> "${minutes}m ${seconds % 60}s"
        else -> "${seconds}s"
    }
}

fun formatDrainRate(rate: Double?): String {
    return when {
        rate == null || !rate.isFinite() || rate < 0.0 -> "\u2014"
        rate == 0.0 -> "0 mA"
        rate < 0.1 -> "< 0.1 mA"
        rate < 10 -> String.format(java.util.Locale.getDefault(), "%.1f mA", rate)
        else -> String.format(java.util.Locale.getDefault(), "%.0f mA", rate)
    }
}

/** Formats [mah] as a share of [capacityMah]; empty when the capacity is unknown. */
fun formatBatteryPercent(mah: Double?, capacityMah: Double): String {
    if (!capacityMah.isFinite() || capacityMah <= 0.0 || mah == null || !mah.isFinite() || mah <= 0.0) return ""
    val percent = mah / capacityMah * 100.0
    return when {
        percent < 0.01 -> "< 0.01%"
        percent < 1 -> String.format(java.util.Locale.getDefault(), "%.2f%%", percent)
        else -> String.format(java.util.Locale.getDefault(), "%.1f%%", percent)
    }
}

/** Same, per hour - a drain rate in mA is mAh per hour. */
fun formatBatteryPercentRate(ratePerHour: Double?, capacityMah: Double): String {
    val percent = formatBatteryPercent(ratePerHour, capacityMah)
    return if (percent.isEmpty()) "" else "$percent/h"
}

/** "22.8 mAh · 0.5%", or just the mAh when the capacity is unknown. */
fun formatMahWithPercent(mah: Double?, capacityMah: Double): String {
    if (mah == null || !mah.isFinite() || mah < 0.0) return "—"
    val value = String.format(java.util.Locale.getDefault(), "%.1f mAh", mah)
    val percent = formatBatteryPercent(mah, capacityMah)
    return if (percent.isEmpty()) value else "$value · $percent"
}

/** "45 mA · 1.1%/h", or just the rate when the capacity is unknown. */
fun formatDrainRateWithPercent(rate: Double?, capacityMah: Double): String {
    val value = formatDrainRate(rate)
    val percent = formatBatteryPercentRate(rate, capacityMah)
    return if (percent.isEmpty()) value else "$value · $percent"
}

/**
 * "1.1%/h" - the share of the battery per hour, falling back to plain mA where the capacity
 * could not be established. For places with no room for both, like a notification line.
 */
fun formatDrainRatePreferPercent(rate: Double?, capacityMah: Double): String {
    val percent = formatBatteryPercentRate(rate, capacityMah)
    return if (percent.isEmpty()) formatDrainRate(rate) else percent
}

/**
 * An instantaneous current as a share of the battery per hour: "-4.1%/h" while discharging,
 * "+12.3%/h" on the charger. The sign is what distinguishes the two at a glance, so it is
 * always shown. Null when there is no capacity to measure against.
 */
fun formatLevelRatePerHour(currentMa: Int, capacityMah: Double?): String? {
    if (capacityMah == null || !capacityMah.isFinite() || capacityMah <= 0.0 || currentMa == 0) return null
    return String.format(
        java.util.Locale.getDefault(),
        "%+.1f%%/h",
        currentMa / capacityMah * 100.0
    )
}
