package app.batstats.battery.drain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The buckets must reconcile. The previous sampling approach credited a whole poll interval
 * to whichever state the closing sample happened to see, which let deep sleep exceed
 * screen-off time and produced "1307% of screen-off time in deep sleep" on a real device.
 */
class DrainTotalsTest {

    private val minute = 60_000L

    @Test
    fun `a screen-on segment only touches screen-on buckets`() {
        val t = DrainTotals()
        t.credit(screenOn = true, active = true, elapsedMs = minute, sleptMs = 0L, drainMah = 10.0)

        assertEquals(minute, t.screenOnTimeMs)
        assertEquals(0L, t.screenOffTimeMs)
        assertEquals(minute, t.activeTimeMs)
        assertEquals(0L, t.idleTimeMs)
        assertEquals(0L, t.deepSleepTimeMs)
        assertEquals(0L, t.awakeTimeMs)
        assertEquals(10.0, t.screenOnDrainMah, 0.0001)
        assertEquals(10.0, t.activeDrainMah, 0.0001)
    }

    @Test
    fun `screen-off time splits by how long the CPU actually slept`() {
        val t = DrainTotals()
        // Ten minutes off, of which the CPU was suspended for eight.
        t.credit(
            screenOn = false,
            active = false,
            elapsedMs = 10 * minute,
            sleptMs = 8 * minute,
            drainMah = 20.0
        )

        assertEquals(10 * minute, t.screenOffTimeMs)
        assertEquals(8 * minute, t.deepSleepTimeMs)
        assertEquals(2 * minute, t.awakeTimeMs)
        // Drain follows the same split.
        assertEquals(16.0, t.deepSleepDrainMah, 0.0001)
        assertEquals(4.0, t.awakeDrainMah, 0.0001)
        assertEquals(20.0, t.screenOffDrainMah, 0.0001)
    }

    @Test
    fun `deep sleep can never exceed screen-off time`() {
        val t = DrainTotals()
        // The old code could book 35s of deep sleep against 2s of screen-off. Even when the
        // reported sleep is nonsense, the segment's own length bounds it.
        t.credit(screenOn = false, active = false, elapsedMs = 2_000L, sleptMs = 35_000L, drainMah = 1.0)

        assertEquals(2_000L, t.screenOffTimeMs)
        assertEquals(2_000L, t.deepSleepTimeMs)
        assertEquals(0L, t.awakeTimeMs)
        assertTrue(t.deepSleepTimeMs <= t.screenOffTimeMs)
    }

    @Test
    fun `buckets reconcile across a mixed session`() {
        val t = DrainTotals()
        t.credit(screenOn = true, active = true, elapsedMs = 5 * minute, sleptMs = 0L, drainMah = 50.0)
        t.credit(screenOn = true, active = false, elapsedMs = 3 * minute, sleptMs = 0L, drainMah = 6.0)
        t.credit(screenOn = false, active = false, elapsedMs = 20 * minute, sleptMs = 18 * minute, drainMah = 4.0)
        t.credit(screenOn = false, active = false, elapsedMs = 2 * minute, sleptMs = 0L, drainMah = 3.0)

        assertEquals(t.screenOnTimeMs, t.activeTimeMs + t.idleTimeMs)
        assertEquals(t.screenOffTimeMs, t.deepSleepTimeMs + t.awakeTimeMs)
        assertEquals(8 * minute, t.screenOnTimeMs)
        assertEquals(22 * minute, t.screenOffTimeMs)

        assertEquals(t.screenOnDrainMah, t.activeDrainMah + t.idleDrainMah, 0.0001)
        assertEquals(t.screenOffDrainMah, t.deepSleepDrainMah + t.awakeDrainMah, 0.0001)

        // And the shares the UI renders stay in range.
        val state = DrainState(
            screenOnTimeMs = t.screenOnTimeMs,
            screenOffTimeMs = t.screenOffTimeMs,
            deepSleepTimeMs = t.deepSleepTimeMs
        )
        assertEquals(30 * minute, state.trackedTimeMs)
        assertEquals(26.67f, state.screenOnPercentage, 0.01f)
        assertEquals(81.82f, state.deepSleepPercentage, 0.01f)
    }

    @Test
    fun `segments with no elapsed time are ignored`() {
        val t = DrainTotals()
        t.credit(screenOn = true, active = true, elapsedMs = 0L, sleptMs = 0L, drainMah = 5.0)
        t.credit(screenOn = false, active = false, elapsedMs = -1L, sleptMs = 0L, drainMah = 5.0)

        assertEquals(0L, t.screenOnTimeMs)
        assertEquals(0L, t.screenOffTimeMs)
        assertEquals(0.0, t.screenOnDrainMah, 0.0001)
    }

    @Test
    fun `a charge counter that ticks upward does not create negative drain`() {
        val t = DrainTotals()
        t.credit(screenOn = true, active = false, elapsedMs = minute, sleptMs = 0L, drainMah = -3.0)

        assertEquals(minute, t.screenOnTimeMs)
        assertEquals(0.0, t.screenOnDrainMah, 0.0001)
    }

    @Test
    fun `copy detaches so the open segment can be folded in without committing it`() {
        val t = DrainTotals()
        t.credit(screenOn = true, active = true, elapsedMs = minute, sleptMs = 0L, drainMah = 10.0)

        val withPending = t.copy()
        withPending.credit(screenOn = true, active = true, elapsedMs = minute, sleptMs = 0L, drainMah = 5.0)

        assertEquals(2 * minute, withPending.screenOnTimeMs)
        assertEquals(minute, t.screenOnTimeMs)
        assertEquals(10.0, t.screenOnDrainMah, 0.0001)
    }
}
