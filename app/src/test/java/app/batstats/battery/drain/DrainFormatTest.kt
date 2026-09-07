package app.batstats.battery.drain

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class DrainFormatTest {

    @Before
    fun fixLocale() {
        // The formatters use the default locale; pin it so the decimal separator is stable.
        Locale.setDefault(Locale.US)
    }

    @Test
    fun `power is expressed as a share of the battery`() {
        // 22.8 mAh of a 5000 mAh battery.
        assertEquals("0.46%", formatBatteryPercent(22.8, 5000.0))
        assertEquals("4.0%", formatBatteryPercent(200.0, 5000.0))
        assertEquals("100.0%", formatBatteryPercent(5000.0, 5000.0))
    }

    @Test
    fun `tiny shares stay distinguishable from zero`() {
        assertEquals("< 0.01%", formatBatteryPercent(0.0001, 5000.0))
        assertEquals("0.02%", formatBatteryPercent(1.0, 5000.0))
    }

    @Test
    fun `a rate needs enough observed time before it means anything`() {
        // 9.8 mAh over two seconds extrapolates to ~17600 mA. Refuse rather than report it.
        assertEquals(0.0, drainRateOver(9.8, 2_000L), 0.0001)
        assertEquals(0.0, drainRateOver(9.8, MIN_RATE_WINDOW_MS - 1), 0.0001)
        // Once the window is long enough the arithmetic is the plain one.
        assertEquals(60.0, drainRateOver(60.0, 3_600_000L), 0.0001)
    }

    @Test
    fun `a rate with no usable window reads as unknown, not as zero draw`() {
        assertEquals("\u2014", formatDrainRate(0.0))
        assertEquals("\u2014", formatDrainRateWithPercent(0.0, 5000.0))
        assertEquals("< 0.1 mA", formatDrainRate(0.05))
    }

    @Test
    fun `state shares never exceed the whole`() {
        // Time in a state and the drain samples advance on different cadences, which once
        // rendered "1307% of screen-off time in deep sleep".
        val state = DrainState(screenOffTimeMs = 2_000L, deepSleepTimeMs = 35_000L)
        assertEquals(100f, state.deepSleepPercentage, 0.001f)
    }

    @Test
    fun `an unknown capacity yields no percentage rather than a made-up one`() {
        assertEquals("", formatBatteryPercent(22.8, 0.0))
        assertEquals("", formatBatteryPercent(22.8, -1.0))
        assertEquals("22.8 mAh", formatMahWithPercent(22.8, 0.0))
        assertEquals("45 mA", formatDrainRateWithPercent(45.0, 0.0))
    }

    @Test
    fun `zero power has no share worth showing`() {
        assertEquals("", formatBatteryPercent(0.0, 5000.0))
    }

    @Test
    fun `mAh and rates carry the share alongside the raw value`() {
        assertEquals("22.8 mAh · 0.46%", formatMahWithPercent(22.8, 5000.0))
        // A drain rate in mA is mAh per hour, so its share is per hour too.
        assertEquals("250 mA · 5.0%/h", formatDrainRateWithPercent(250.0, 5000.0))
        assertEquals("5.0%/h", formatBatteryPercentRate(250.0, 5000.0))
    }

    @Test
    fun `a notification rate drops the mA and keeps the share`() {
        assertEquals("5.0%/h", formatDrainRatePreferPercent(250.0, 5000.0))
        // Without a capacity there is no share to show, so the mA reading stays.
        assertEquals("250 mA", formatDrainRatePreferPercent(250.0, 0.0))
        assertEquals("—", formatDrainRatePreferPercent(0.0, 5000.0))
    }

    @Test
    fun `the level rate is signed so charging and draining are distinguishable`() {
        assertEquals("-15.6%/h", formatLevelRatePerHour(-780, 5000.0))
        assertEquals("+30.0%/h", formatLevelRatePerHour(1500, 5000.0))
    }

    @Test
    fun `the level rate is withheld without a capacity to measure against`() {
        assertEquals(null, formatLevelRatePerHour(-780, null))
        assertEquals(null, formatLevelRatePerHour(-780, 0.0))
        // No current is not a rate either way.
        assertEquals(null, formatLevelRatePerHour(0, 5000.0))
    }

    @Test
    fun `capacity is only accepted when it could be a real battery`() {
        assertEquals(true, app.batstats.battery.util.BatteryCapacity.isPlausible(5000.0))
        assertEquals(false, app.batstats.battery.util.BatteryCapacity.isPlausible(0.0))
        assertEquals(false, app.batstats.battery.util.BatteryCapacity.isPlausible(12.0))
        assertEquals(false, app.batstats.battery.util.BatteryCapacity.isPlausible(500_000.0))
    }
}
