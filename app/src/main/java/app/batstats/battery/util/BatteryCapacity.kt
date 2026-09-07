package app.batstats.battery.util

import android.content.Context
import android.os.BatteryManager
import kotlin.math.abs
import kotlin.math.roundToInt

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

    /**
     * A charge/discharge session has to move the level by at least this much before the
     * charge it moved says anything about the whole battery: across one or two points the
     * level's own rounding dominates the answer.
     */
    private const val MIN_LEVEL_SWING_FOR_CAPACITY = 15

    /**
     * The best capacity established so far. Devices expose a usable charge counter only
     * intermittently - some report 0 below a certain level, some only while charging - and
     * a screen that showed real percentages a minute ago should not fall back to bare mAh
     * because of one unlucky reading.
     */
    @Volatile
    private var remembered: Double? = null

    val rememberedMah: Double? get() = remembered

    fun isPlausible(mah: Double): Boolean = mah in PLAUSIBLE_MAH

    /** Records a capacity worth reusing. Implausible values and nulls are ignored. */
    fun remember(mah: Double?) {
        if (mah != null && isPlausible(mah)) remembered = mah
    }

    /**
     * Full capacity implied by [chargeUah] of charge remaining at [levelPercent].
     * Null when the device reports no counter, or the level is too low to divide by.
     */
    fun fromChargeCounter(chargeUah: Long?, levelPercent: Int): Double? {
        if (chargeUah == null || chargeUah <= 0L) return null
        if (levelPercent < MIN_LEVEL_FOR_ESTIMATE || levelPercent > 100) return null
        return ((chargeUah / 1000.0) / (levelPercent / 100.0)).takeIf(::isPlausible)
    }

    /**
     * Full capacity implied by a session that moved [deltaUah] of charge across
     * [levelDeltaPercent] points of battery level. Null unless the swing was large enough
     * to mean something.
     */
    fun fromChargeDelta(deltaUah: Long?, levelDeltaPercent: Int): Int? {
        if (deltaUah == null || deltaUah == 0L) return null
        val points = abs(levelDeltaPercent)
        if (points < MIN_LEVEL_SWING_FOR_CAPACITY) return null
        val mah = (abs(deltaUah) / 1000.0) / (points / 100.0)
        return if (isPlausible(mah)) mah.roundToInt() else null
    }

    /**
     * Full capacity derived from the remaining charge and the current level, or null when
     * the device does not report a usable charge counter.
     */
    fun measuredMah(context: Context): Double? {
        val bm = context.getSystemService(BatteryManager::class.java) ?: return null
        return try {
            val chargeUah = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
            val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            fromChargeCounter(chargeUah, level)?.also { remember(it) }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Prefers the capacity batterystats reports (the value Android itself attributes
     * against), falling back to a measurement and then to whatever was last established.
     * Null when none of those is trustworthy.
     */
    fun resolveMah(context: Context, reportedMah: Int): Double? {
        val reported = reportedMah.toDouble()
        if (isPlausible(reported)) {
            remember(reported)
            return reported
        }
        return measuredMah(context) ?: remembered
    }
}
