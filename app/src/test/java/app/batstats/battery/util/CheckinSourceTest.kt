package app.batstats.battery.util

import app.batstats.battery.util.CheckinSource.Companion.DEFAULT_MAX_AGE_MS
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CheckinSourceTest {

    @Test
    fun `a dump taken moments ago is reused`() {
        val now = 1_000_000_000_000L
        assertTrue(isFresh(takenAt = now, now = now, maxAgeMs = DEFAULT_MAX_AGE_MS))
        assertTrue(isFresh(takenAt = now - 5_000, now = now, maxAgeMs = DEFAULT_MAX_AGE_MS))
        assertTrue(
            isFresh(takenAt = now - DEFAULT_MAX_AGE_MS, now = now, maxAgeMs = DEFAULT_MAX_AGE_MS)
        )
    }

    @Test
    fun `an older dump is taken again`() {
        val now = 1_000_000_000_000L
        assertFalse(
            isFresh(takenAt = now - DEFAULT_MAX_AGE_MS - 1, now = now, maxAgeMs = DEFAULT_MAX_AGE_MS)
        )
        assertFalse(isFresh(takenAt = now - 60_000, now = now, maxAgeMs = DEFAULT_MAX_AGE_MS))
    }

    @Test
    fun `a timestamp in the future is stale, not permanently fresh`() {
        // A clock correction between the two reads would otherwise pin the cache open.
        val now = 1_000_000_000_000L
        assertFalse(isFresh(takenAt = now + 60_000, now = now, maxAgeMs = DEFAULT_MAX_AGE_MS))
    }

    @Test
    fun `a zero max age still coalesces callers that arrive together`() {
        // The lock is what serialises them; a same-millisecond hit is still a hit.
        val now = 1_000_000_000_000L
        assertTrue(isFresh(takenAt = now, now = now, maxAgeMs = 0L))
        assertFalse(isFresh(takenAt = now - 1, now = now, maxAgeMs = 0L))
    }
}
