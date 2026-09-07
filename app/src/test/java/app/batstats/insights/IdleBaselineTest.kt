package app.batstats.insights

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IdleBaselineTest {

    @Test
    fun `nothing is claimed until enough has been seen`() {
        val baseline = IdleBaseline(minReadings = 16)
        repeat(15) { baseline.observe(200.0) }
        assertNull(baseline.baselineMilliAmps())

        baseline.observe(200.0)
        assertEquals(200.0, baseline.baselineMilliAmps()!!, 0.001)
    }

    @Test
    fun `the floor tracks the device rather than a constant`() {
        // A tablet idling at 300 mA and a phone idling at 40 mA get their own floors,
        // where the old code assumed 80 mA for both.
        val tablet = IdleBaseline(minReadings = 4, windowSize = 16)
        repeat(16) { tablet.observe(300.0 + it) }
        assertEquals(301.0, tablet.baselineMilliAmps()!!, 1.5)

        val phone = IdleBaseline(minReadings = 4, windowSize = 16)
        repeat(16) { phone.observe(40.0 + it) }
        assertEquals(41.0, phone.baselineMilliAmps()!!, 1.5)
    }

    @Test
    fun `one bogus reading does not define the floor forever`() {
        // A single 0 mA reading from a driver hiccup would pin the minimum at zero, so the
        // estimate is a low percentile rather than the outright lowest value.
        val baseline = IdleBaseline(minReadings = 4, windowSize = 20)
        baseline.observe(0.0)
        repeat(19) { baseline.observe(120.0) }
        assertEquals(120.0, baseline.baselineMilliAmps()!!, 0.001)
    }

    @Test
    fun `the window forgets old readings`() {
        val baseline = IdleBaseline(minReadings = 4, windowSize = 8)
        repeat(8) { baseline.observe(500.0) }
        assertEquals(500.0, baseline.baselineMilliAmps()!!, 0.001)

        // A device that has settled down should not be judged against how it used to draw.
        repeat(8) { baseline.observe(60.0) }
        assertEquals(60.0, baseline.baselineMilliAmps()!!, 0.001)
    }

    @Test
    fun `nonsense readings are ignored`() {
        val baseline = IdleBaseline(minReadings = 2, windowSize = 8)
        baseline.observe(-50.0)
        baseline.observe(Double.NaN)
        assertNull(baseline.baselineMilliAmps())

        baseline.observe(90.0)
        baseline.observe(90.0)
        assertEquals(90.0, baseline.baselineMilliAmps()!!, 0.001)
    }
}
