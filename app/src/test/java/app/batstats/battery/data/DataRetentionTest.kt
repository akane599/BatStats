package app.batstats.battery.data

import app.batstats.battery.data.DataRetentionManager.Companion.CLEANUP_INTERVAL_MS
import app.batstats.battery.data.DataRetentionManager.Companion.cutoff
import app.batstats.battery.data.DataRetentionManager.Companion.isDue
import app.batstats.settings.AppSettings
import app.batstats.settings.dataRetentionMs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val DAY = 24 * 60 * 60 * 1000L

class DataRetentionTest {

    @Test
    fun `cleanup waits out the interval between runs`() {
        val now = 1_000_000_000_000L
        assertFalse(isDue(enabled = true, lastCleanup = now, now = now))
        assertFalse(isDue(enabled = true, lastCleanup = now - CLEANUP_INTERVAL_MS + 1, now = now))
        assertTrue(isDue(enabled = true, lastCleanup = now - CLEANUP_INTERVAL_MS, now = now))
    }

    @Test
    fun `a database that has never been cleaned is due immediately`() {
        assertTrue(isDue(enabled = true, lastCleanup = 0L, now = 1_000_000_000_000L))
    }

    @Test
    fun `turning auto-cleanup off stops it`() {
        assertFalse(isDue(enabled = false, lastCleanup = 0L, now = 1_000_000_000_000L))
    }

    @Test
    fun `a clock that moved backwards does not strand the cleanup forever`() {
        // A time zone change or an NTP correction can leave the stored timestamp in the
        // future, where "now - last" is negative and would never reach the interval again.
        val now = 1_000_000_000_000L
        assertTrue(isDue(enabled = true, lastCleanup = now + 30 * DAY, now = now))
    }

    @Test
    fun `the cutoff is the retention window behind now`() {
        val now = 1_000_000_000_000L
        assertEquals(now - 90 * DAY, cutoff(90 * DAY, now))
    }

    @Test
    fun `keeping data forever deletes nothing`() {
        assertNull(cutoff(null, 1_000_000_000_000L))
    }

    @Test
    fun `each retention choice maps to its window`() {
        fun windowFor(index: Int) = AppSettings(dataRetentionIndex = index).dataRetentionMs

        assertEquals(7 * DAY, windowFor(0))
        assertEquals(30 * DAY, windowFor(1))
        assertEquals(90 * DAY, windowFor(2))
        assertEquals(180 * DAY, windowFor(3))
        assertEquals(365 * DAY, windowFor(4))
        assertNull(windowFor(5))
    }

    @Test
    fun `an out-of-range choice falls back to the default, not to forever`() {
        // Failing open here would quietly restore the unbounded growth this setting exists
        // to prevent, so an unreadable index keeps the declared 3-month default.
        assertEquals(90 * DAY, AppSettings(dataRetentionIndex = 99).dataRetentionMs)
        assertEquals(90 * DAY, AppSettings(dataRetentionIndex = -1).dataRetentionMs)
    }

    @Test
    fun `the default settings prune`() {
        val defaults = AppSettings()
        assertTrue(defaults.autoCleanupEnabled)
        assertEquals(90 * DAY, defaults.dataRetentionMs)
    }
}
