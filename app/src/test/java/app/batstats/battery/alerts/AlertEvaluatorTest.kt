package app.batstats.battery.alerts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertEvaluatorTest {

    private fun reading(
        level: Int = 50,
        temperatureC: Float = 30f,
        currentMa: Int = -300,
        plugged: Boolean = false,
        statusFull: Boolean = false
    ) = AlertReading(level, temperatureC, currentMa, plugged, statusFull)

    private fun evaluate(
        reading: AlertReading,
        thresholds: AlertThresholds,
        previous: Set<BatteryAlert> = emptySet()
    ) = AlertEvaluator.evaluate(reading, thresholds, previous)

    // --- low battery -------------------------------------------------------------------

    @Test
    fun `low battery fires at the threshold while unplugged`() {
        val thresholds = AlertThresholds(lowEnabled = true, lowThreshold = 20)
        assertTrue(BatteryAlert.LOW_BATTERY in evaluate(reading(level = 20), thresholds))
        assertTrue(BatteryAlert.LOW_BATTERY in evaluate(reading(level = 5), thresholds))
        assertEquals(emptySet<BatteryAlert>(), evaluate(reading(level = 21), thresholds))
    }

    @Test
    fun `low battery does not fire on the charger`() {
        val thresholds = AlertThresholds(lowEnabled = true, lowThreshold = 20)
        assertEquals(
            emptySet<BatteryAlert>(),
            evaluate(reading(level = 10, plugged = true), thresholds)
        )
    }

    @Test
    fun `a level sitting on the threshold does not re-notify`() {
        // Without the margin, a battery oscillating between 20% and 21% would fire on every
        // reading that came back to 20.
        val thresholds = AlertThresholds(lowEnabled = true, lowThreshold = 20)
        val held = setOf(BatteryAlert.LOW_BATTERY)

        val stillActive = evaluate(reading(level = 21), thresholds, held)
        assertTrue(BatteryAlert.LOW_BATTERY in stillActive)
        assertEquals(emptySet<BatteryAlert>(), AlertEvaluator.rising(held, stillActive))

        // Recovering past the margin re-arms it.
        assertEquals(emptySet<BatteryAlert>(), evaluate(reading(level = 23), thresholds, held))
    }

    @Test
    fun `plugging in clears a low battery alert immediately`() {
        val thresholds = AlertThresholds(lowEnabled = true, lowThreshold = 20)
        val held = setOf(BatteryAlert.LOW_BATTERY)
        assertEquals(
            emptySet<BatteryAlert>(),
            evaluate(reading(level = 15, plugged = true), thresholds, held)
        )
    }

    // --- high battery ------------------------------------------------------------------

    @Test
    fun `high battery fires only while charging`() {
        val thresholds = AlertThresholds(highEnabled = true, highThreshold = 80)
        assertTrue(
            BatteryAlert.HIGH_BATTERY in evaluate(reading(level = 80, plugged = true), thresholds)
        )
        assertEquals(
            emptySet<BatteryAlert>(),
            evaluate(reading(level = 90, plugged = false), thresholds)
        )
    }

    // --- temperature -------------------------------------------------------------------

    @Test
    fun `temperature fires at the threshold and needs to cool before re-arming`() {
        val thresholds = AlertThresholds(temperatureEnabled = true, temperatureThresholdC = 45f)
        assertTrue(BatteryAlert.TEMPERATURE in evaluate(reading(temperatureC = 45f), thresholds))

        val held = setOf(BatteryAlert.TEMPERATURE)
        assertTrue(BatteryAlert.TEMPERATURE in evaluate(reading(temperatureC = 44f), thresholds, held))
        assertEquals(
            emptySet<BatteryAlert>(),
            evaluate(reading(temperatureC = 42f), thresholds, held)
        )
    }

    @Test
    fun `temperature is watched on the charger too`() {
        // A hot battery is a hot battery; this is the one alert that is not about drain.
        val thresholds = AlertThresholds(temperatureEnabled = true, temperatureThresholdC = 45f)
        assertTrue(
            BatteryAlert.TEMPERATURE in
                evaluate(reading(temperatureC = 46f, plugged = true), thresholds)
        )
    }

    // --- discharge ---------------------------------------------------------------------

    @Test
    fun `heavy discharge fires on magnitude, not sign`() {
        val thresholds = AlertThresholds(dischargeEnabled = true, dischargeThresholdMa = 600)
        assertTrue(
            BatteryAlert.HIGH_DISCHARGE in evaluate(reading(currentMa = -700), thresholds)
        )
        assertEquals(emptySet<BatteryAlert>(), evaluate(reading(currentMa = -500), thresholds))
    }

    @Test
    fun `charging current is never a discharge alert`() {
        // The current is positive and large on a fast charger; that is not a drain.
        val thresholds = AlertThresholds(dischargeEnabled = true, dischargeThresholdMa = 600)
        assertEquals(
            emptySet<BatteryAlert>(),
            evaluate(reading(currentMa = 1800, plugged = true), thresholds)
        )
    }

    // --- charging complete -------------------------------------------------------------

    @Test
    fun `charging complete accepts either a full status or a full level`() {
        val thresholds = AlertThresholds(chargingCompleteEnabled = true)
        assertTrue(
            BatteryAlert.CHARGING_COMPLETE in
                evaluate(reading(level = 100, plugged = true), thresholds)
        )
        // Some devices report FULL a little short of 100.
        assertTrue(
            BatteryAlert.CHARGING_COMPLETE in
                evaluate(reading(level = 98, plugged = true, statusFull = true), thresholds)
        )
        assertEquals(
            emptySet<BatteryAlert>(),
            evaluate(reading(level = 100, plugged = false), thresholds)
        )
    }

    // --- general -----------------------------------------------------------------------

    @Test
    fun `a disabled alert never fires however extreme the reading`() {
        val nothingEnabled = AlertThresholds()
        val extreme = reading(level = 1, temperatureC = 60f, currentMa = -3000)
        assertEquals(emptySet<BatteryAlert>(), evaluate(extreme, nothingEnabled))
    }

    @Test
    fun `only newly held conditions are worth notifying about`() {
        val previous = setOf(BatteryAlert.LOW_BATTERY)
        val current = setOf(BatteryAlert.LOW_BATTERY, BatteryAlert.TEMPERATURE)
        assertEquals(setOf(BatteryAlert.TEMPERATURE), AlertEvaluator.rising(previous, current))
    }

    @Test
    fun `several conditions can hold at once`() {
        val thresholds = AlertThresholds(
            lowEnabled = true, lowThreshold = 20,
            temperatureEnabled = true, temperatureThresholdC = 45f,
            dischargeEnabled = true, dischargeThresholdMa = 600
        )
        val active = evaluate(reading(level = 10, temperatureC = 47f, currentMa = -900), thresholds)
        assertEquals(
            setOf(
                BatteryAlert.LOW_BATTERY,
                BatteryAlert.TEMPERATURE,
                BatteryAlert.HIGH_DISCHARGE
            ),
            active
        )
    }
}
