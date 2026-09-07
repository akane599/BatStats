package app.batstats.battery.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BatteryCapacityTest {

    @Test
    fun `capacity follows from the charge left and the level it is left at`() {
        // 2.5 Ah remaining at 50% is a 5000 mAh battery.
        assertEquals(5000.0, BatteryCapacity.fromChargeCounter(2_500_000L, 50)!!, 0.001)
        assertEquals(4000.0, BatteryCapacity.fromChargeCounter(3_600_000L, 90)!!, 0.001)
    }

    @Test
    fun `a level too low to divide by is refused`() {
        // At 5% the level's own 1% quantisation is a 20% error on the answer.
        assertNull(BatteryCapacity.fromChargeCounter(250_000L, 5))
        assertNull(BatteryCapacity.fromChargeCounter(250_000L, 19))
        // 250 mAh at 20% is the first level the estimate is allowed to be made from.
        assertEquals(1250.0, BatteryCapacity.fromChargeCounter(250_000L, 20)!!, 0.001)
    }

    @Test
    fun `a device that reports no counter yields nothing`() {
        assertNull(BatteryCapacity.fromChargeCounter(null, 80))
        assertNull(BatteryCapacity.fromChargeCounter(0L, 80))
        assertNull(BatteryCapacity.fromChargeCounter(-1L, 80))
    }

    @Test
    fun `an implausible counter is not passed off as a capacity`() {
        // 12 mAh at 60% is 20 mAh of battery - a broken reading, not a phone.
        assertNull(BatteryCapacity.fromChargeCounter(12_000L, 60))
    }

    @Test
    fun `a session's charge delta implies the whole battery`() {
        // 2000 mAh moved across 40 points is a 5000 mAh battery.
        assertEquals(5000, BatteryCapacity.fromChargeDelta(2_000_000L, 40))
        // Direction does not matter: a discharge measures the same battery.
        assertEquals(5000, BatteryCapacity.fromChargeDelta(-2_000_000L, -40))
    }

    @Test
    fun `a short session says nothing about capacity`() {
        // Across 3 points, one point of rounding is a 33% error.
        assertNull(BatteryCapacity.fromChargeDelta(150_000L, 3))
        assertNull(BatteryCapacity.fromChargeDelta(700_000L, 14))
        assertEquals(5000, BatteryCapacity.fromChargeDelta(750_000L, 15))
    }

    @Test
    fun `a session with no counter data yields nothing`() {
        assertNull(BatteryCapacity.fromChargeDelta(null, 40))
        assertNull(BatteryCapacity.fromChargeDelta(0L, 40))
    }

    @Test
    fun `a remembered capacity survives a reading that produced none`() {
        BatteryCapacity.remember(4500.0)
        assertEquals(4500.0, BatteryCapacity.rememberedMah!!, 0.001)

        // Nonsense never displaces a good value.
        BatteryCapacity.remember(null)
        BatteryCapacity.remember(3.0)
        assertEquals(4500.0, BatteryCapacity.rememberedMah!!, 0.001)
    }
}
