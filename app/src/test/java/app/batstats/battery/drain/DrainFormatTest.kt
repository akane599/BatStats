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
    fun `capacity is only accepted when it could be a real battery`() {
        assertEquals(true, app.batstats.battery.util.BatteryCapacity.isPlausible(5000.0))
        assertEquals(false, app.batstats.battery.util.BatteryCapacity.isPlausible(0.0))
        assertEquals(false, app.batstats.battery.util.BatteryCapacity.isPlausible(12.0))
        assertEquals(false, app.batstats.battery.util.BatteryCapacity.isPlausible(500_000.0))
    }
}
