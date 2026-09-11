package app.batstats.battery.drain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DrainTotalsTest {
    private val minute = 60_000L

    @Test fun `screen-on time never becomes screen-off sleep or awake time`() {
        val totals = DrainTotals()
        // Even a suspend-clock discontinuity during an interactive segment cannot create
        // screen-off time or split screen-on energy into guessed active and idle buckets.
        totals.credit(screenOn = true, elapsedMs = minute, sleptMs = 20_000L, drainMah = 10.0)
        assertEquals(minute, totals.screenOnTimeMs)
        assertEquals(0L, totals.screenOffTimeMs)
        assertEquals(0L, totals.deepSleepTimeMs)
        assertEquals(0L, totals.awakeTimeMs)
        assertEquals(10.0, requireNotNull(totals.screenOnDrainMah), 0.0001)
        assertEquals(0.0, requireNotNull(totals.screenOffDrainMah), 0.0001)
    }

    @Test fun `CPU sleep divides screen-off duration while energy stays measured as one total`() {
        val totals = DrainTotals()
        totals.credit(screenOn = false, elapsedMs = 10 * minute, sleptMs = 8 * minute, drainMah = 20.0)
        assertEquals(10 * minute, totals.screenOffTimeMs)
        assertEquals(8 * minute, totals.deepSleepTimeMs)
        assertEquals(2 * minute, totals.awakeTimeMs)
        assertEquals(20.0, requireNotNull(totals.screenOffDrainMah), 0.0001)
        assertEquals(0.0, requireNotNull(totals.screenOnDrainMah), 0.0001)
    }

    @Test fun `sleep-clock discontinuities cannot exceed the observed screen-off interval`() {
        val totals = DrainTotals()
        totals.credit(screenOn = false, elapsedMs = 2_000L, sleptMs = 35_000L, drainMah = 1.0)
        totals.credit(screenOn = false, elapsedMs = 1_000L, sleptMs = -10_000L, drainMah = 1.0)
        assertEquals(3_000L, totals.screenOffTimeMs)
        assertEquals(2_000L, totals.deepSleepTimeMs)
        assertEquals(1_000L, totals.awakeTimeMs)
        assertTrue(totals.deepSleepTimeMs <= totals.screenOffTimeMs)
    }

    @Test fun `measured time and energy reconcile across mixed screen states`() {
        val totals = DrainTotals()
        totals.credit(screenOn = true, elapsedMs = 5 * minute, sleptMs = 0L, drainMah = 50.0)
        totals.credit(screenOn = true, elapsedMs = 3 * minute, sleptMs = 0L, drainMah = 6.0)
        totals.credit(screenOn = false, elapsedMs = 20 * minute, sleptMs = 18 * minute, drainMah = 4.0)
        totals.credit(screenOn = false, elapsedMs = 2 * minute, sleptMs = 0L, drainMah = 3.0)
        val state = DrainState(
            screenOnTimeMs = totals.screenOnTimeMs, screenOffTimeMs = totals.screenOffTimeMs,
            deepSleepTimeMs = totals.deepSleepTimeMs, awakeTimeMs = totals.awakeTimeMs,
            screenOnDrainMah = totals.screenOnDrainMah, screenOffDrainMah = totals.screenOffDrainMah
        )
        assertEquals(8 * minute, state.screenOnTimeMs)
        assertEquals(22 * minute, state.screenOffTimeMs)
        assertEquals(state.screenOffTimeMs, state.deepSleepTimeMs + state.awakeTimeMs)
        assertEquals(30 * minute, state.trackedTimeMs)
        assertEquals(63.0, requireNotNull(state.totalDrainMah), 0.0001)
        assertEquals(26.67f, state.screenOnPercentage, 0.01f)
        assertEquals(73.33f, state.screenOffPercentage, 0.01f)
        assertEquals(81.82f, state.deepSleepPercentage, 0.01f)
    }

    @Test fun `zero or negative elapsed segments cannot credit energy or invalidate totals`() {
        val totals = DrainTotals()
        totals.credit(screenOn = true, elapsedMs = 0L, sleptMs = 0L, drainMah = null)
        totals.credit(screenOn = false, elapsedMs = -1L, sleptMs = 0L, drainMah = 5.0)
        assertEquals(DrainTotals(), totals)
    }

    @Test fun `invalid energy keeps measured time and invalidates only its screen-state total`() {
        listOf(null, -3.0, Double.NaN, Double.POSITIVE_INFINITY).forEach { invalid ->
            val totals = DrainTotals()
            totals.credit(screenOn = true, elapsedMs = minute, sleptMs = 0L, drainMah = 10.0)
            totals.credit(screenOn = true, elapsedMs = minute, sleptMs = 0L, drainMah = invalid)
            totals.credit(screenOn = true, elapsedMs = minute, sleptMs = 0L, drainMah = 5.0)
            totals.credit(screenOn = false, elapsedMs = minute, sleptMs = 0L, drainMah = 2.0)
            assertEquals(3 * minute, totals.screenOnTimeMs)
            assertNull(totals.screenOnDrainMah)
            assertEquals(2.0, requireNotNull(totals.screenOffDrainMah), 0.0001)
            val state = DrainState(screenOnDrainMah = totals.screenOnDrainMah,
                screenOffDrainMah = totals.screenOffDrainMah, screenOnTimeMs = totals.screenOnTimeMs,
                screenOffTimeMs = totals.screenOffTimeMs)
            assertNull(state.totalDrainMah)
            assertNull(state.averageDrainRate)
        }
    }

    @Test fun `unsupported charge counter zero cannot drain an apparently nonempty battery`() {
        assertEquals(5_000.0, requireNotNull(chargeCounterMah(5_000_000L, 50)), 0.0)
        assertEquals(0.0, requireNotNull(chargeCounterMah(0L, 0)), 0.0)
        assertNull(chargeCounterMah(0L, 50))
        assertNull(chargeCounterMah(0L, -1))
        listOf(null, -1L, Long.MIN_VALUE, Int.MIN_VALUE.toLong()).forEach { unsupported ->
            assertNull(chargeCounterMah(unsupported, 50))
        }
    }
}
