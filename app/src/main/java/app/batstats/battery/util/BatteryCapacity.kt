package app.batstats.battery.util

import android.content.Context
import android.os.BatteryManager

/**
 * Works out how large this battery is, so drain figures can be expressed as a share of it.
 *
 * Every result is either measured or reported - never a guess. A percentage computed
 * against an assumed capacity looks exactly as authoritative as a real one, so callers get
 * null instead and show plain mAh.
 */
object BatteryCapacity {

    /** Below this a "capacity" is noise; above it, it is not a phone battery. */
    private val PLAUSIBLE_MAH = 500.0..30_000.0

    /**
     * Deriving capacity from the charge counter divides by the level, so a low level
     * magnifies the 1% quantisation error. Past this it is worth about 5%.
     */
    private const val MIN_LEVEL_FOR_ESTIMATE = 20

    fun isPlausible(mah: Double): Boolean = mah in PLAUSIBLE_MAH

    /**
     * Full capacity derived from the remaining charge and the current level, or null when
     * the device does not report a usable charge counter.
     */
    fun measuredMah(context: Context): Double? {
        val bm = context.getSystemService(BatteryManager::class.java) ?: return null
        return try {
            val chargeUah = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
            if (chargeUah <= 0L) return null
            val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (level < MIN_LEVEL_FOR_ESTIMATE || level > 100) return null
            ((chargeUah / 1000.0) / (level / 100.0)).takeIf(::isPlausible)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Prefers the capacity batterystats reports (the value Android itself attributes
     * against), falling back to a measurement. Null when neither is trustworthy.
     */
    fun resolveMah(context: Context, reportedMah: Int): Double? {
        val reported = reportedMah.toDouble()
        if (isPlausible(reported)) return reported
        return measuredMah(context)
    }
}
