package app.batstats.battery.util

import app.batstats.battery.data.db.BatterySample
import kotlin.math.abs
import kotlin.math.roundToInt

object TimeEstimator {

    /**
     * Capacity to base an estimate on: derived from the sample itself where the device
     * reports a charge counter, otherwise the best figure established elsewhere.
     */
    fun capacityMahFor(sample: BatterySample): Double? =
        BatteryCapacity.fromChargeCounter(sample.chargeCounterUah, sample.levelPercent)
            ?.also { BatteryCapacity.remember(it) }
            ?: BatteryCapacity.rememberedMah

    /**
     * "Est. full in 1h 20m" / "Est. empty in 6h 5m", or null when there is nothing honest
     * to say.
     *
     * The capacity here used to be hardcoded to 4000 mAh, so the estimate was wrong by
     * whatever ratio the real battery differed by - and silently so, since a plausible
     * number looks exactly like a correct one. Without a real capacity we now show nothing.
     */
    fun etaString(sample: BatterySample?): String? {
        sample ?: return null
        val currentMa = ((sample.currentNowUa ?: return null) / 1000.0).roundToInt()
        if (currentMa == 0) return null
        val cap = capacityMahFor(sample) ?: return null
        val level = sample.levelPercent

        return when {
            sample.plugged != 0 && currentMa > 0 ->
                "Est. full in ${formatHours(cap * (100 - level) / 100.0 / currentMa)}"

            sample.plugged == 0 && currentMa < 0 ->
                "Est. empty in ${formatHours(cap * level / 100.0 / abs(currentMa))}"

            else -> null
        }
    }

    private fun formatHours(h: Double): String {
        val m = (h * 60).roundToInt().coerceAtLeast(0)
        val hh = m / 60
        val mm = m % 60
        return if (hh > 0) "${hh}h ${mm}m" else "${mm}m"
    }
}
