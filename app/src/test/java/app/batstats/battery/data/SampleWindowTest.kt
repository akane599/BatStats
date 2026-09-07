package app.batstats.battery.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SampleWindowTest {

    @Test
    fun `short windows still slide, but not on every tick`() {
        // A sixtieth of 15 minutes is 15 s; the floor keeps the query off that cadence.
        assertEquals(30_000L, windowSlideIntervalMs(15 * 60_000L))
        assertEquals(60_000L, windowSlideIntervalMs(60 * 60_000L))
    }

    @Test
    fun `long windows do not stop moving`() {
        // A sixtieth of a week is nearly three hours; cap it so the chart still advances
        // while you are looking at it.
        assertEquals(15 * 60_000L, windowSlideIntervalMs(7 * 24 * 60 * 60_000L))
    }

    @Test
    fun `the slide is always a small fraction of the window`() {
        listOf(15, 60, 6 * 60, 24 * 60, 7 * 24 * 60).forEach { minutes ->
            val window = minutes * 60_000L
            assertTrue(
                "slide should be well under the window for ${minutes}m",
                windowSlideIntervalMs(window) <= window / 2
            )
        }
    }
}
