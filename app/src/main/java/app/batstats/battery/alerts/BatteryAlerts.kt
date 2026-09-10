package app.batstats.battery.alerts

import kotlin.math.abs

/** The alerts Settings offers. */
enum class BatteryAlert {
    LOW_BATTERY,
    HIGH_BATTERY,
    TEMPERATURE,
    HIGH_DISCHARGE,
    CHARGING_COMPLETE
}

/** Just enough of a battery reading to decide whether anything should fire. */
data class AlertReading(
    val levelPercent: Int,
    val temperatureC: Float,
    /** Negative while discharging, as the hardware reports it. */
    val currentMa: Int,
    val plugged: Boolean,
    val statusFull: Boolean
)

data class AlertThresholds(
    val lowEnabled: Boolean = false,
    val lowThreshold: Int = 20,
    val highEnabled: Boolean = false,
    val highThreshold: Int = 80,
    val temperatureEnabled: Boolean = false,
    val temperatureThresholdC: Float = 45f,
    val dischargeEnabled: Boolean = false,
    val dischargeThresholdMa: Int = 600,
    val chargingCompleteEnabled: Boolean = false
)

/**
 * Decides which alert conditions currently hold.
 *
 * Alerts fire on the rising edge - the caller notifies for whatever is newly in the set - so
 * the interesting part is when a condition *stops* holding and re-arms. A reading sitting
 * exactly on its threshold jitters by a percent or a tenth of a degree constantly, and
 * without a margin that would notify the user every few seconds. So a condition that already
 * holds keeps holding until the value clears the threshold by [.._MARGIN], and only then can
 * it fire again.
 */
object AlertEvaluator {

    /** Battery level has to recover this far past the threshold before re-arming. */
    private const val LEVEL_MARGIN = 2

    /** Degrees Celsius the temperature must fall past the threshold before re-arming. */
    private const val TEMPERATURE_MARGIN = 2f

    /** Milliamps the draw must fall past the threshold before re-arming. */
    private const val DISCHARGE_MARGIN = 50

    fun evaluate(
        reading: AlertReading,
        thresholds: AlertThresholds,
        previouslyActive: Set<BatteryAlert>
    ): Set<BatteryAlert> {
        val active = mutableSetOf<BatteryAlert>()

        fun held(alert: BatteryAlert) = alert in previouslyActive

        if (thresholds.lowEnabled && !reading.plugged) {
            val limit = if (held(BatteryAlert.LOW_BATTERY)) {
                thresholds.lowThreshold + LEVEL_MARGIN
            } else {
                thresholds.lowThreshold
            }
            if (reading.levelPercent <= limit) active += BatteryAlert.LOW_BATTERY
        }

        if (thresholds.highEnabled && reading.plugged) {
            val limit = if (held(BatteryAlert.HIGH_BATTERY)) {
                thresholds.highThreshold - LEVEL_MARGIN
            } else {
                thresholds.highThreshold
            }
            if (reading.levelPercent >= limit) active += BatteryAlert.HIGH_BATTERY
        }

        if (thresholds.temperatureEnabled) {
            val limit = if (held(BatteryAlert.TEMPERATURE)) {
                thresholds.temperatureThresholdC - TEMPERATURE_MARGIN
            } else {
                thresholds.temperatureThresholdC
            }
            if (reading.temperatureC >= limit) active += BatteryAlert.TEMPERATURE
        }

        // Only a discharge counts. On the charger the current is positive and large, which
        // is not a drain worth warning about.
        if (thresholds.dischargeEnabled && !reading.plugged && reading.currentMa < 0) {
            val limit = if (held(BatteryAlert.HIGH_DISCHARGE)) {
                thresholds.dischargeThresholdMa - DISCHARGE_MARGIN
            } else {
                thresholds.dischargeThresholdMa
            }
            if (abs(reading.currentMa) >= limit) active += BatteryAlert.HIGH_DISCHARGE
        }

        if (thresholds.chargingCompleteEnabled && reading.plugged) {
            // Some devices report FULL a little before 100%, others sit at 100% without ever
            // setting the status; either is "done charging".
            if (reading.statusFull || reading.levelPercent >= 100) {
                active += BatteryAlert.CHARGING_COMPLETE
            }
        }

        return active
    }

    /** The alerts that just started holding, which are the ones worth notifying about. */
    fun rising(previous: Set<BatteryAlert>, current: Set<BatteryAlert>): Set<BatteryAlert> =
        current - previous
}
