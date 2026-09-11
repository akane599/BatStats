package app.batstats.battery

import app.batstats.battery.shizuku.BstatsCollector
import app.batstats.battery.data.effectiveMonitoringIntervalMs
import app.batstats.battery.drain.DRAIN_SNAPSHOT_INTERVAL_MS
import app.batstats.battery.drain.DrainSnapshot
import app.batstats.battery.drain.shouldRecordDrainSnapshot
import app.batstats.insights.HeuristicWriteBuffer
import app.batstats.insights.shouldQueryForegroundApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitoringEfficiencyTest {
    @Test fun cumulativePerAppStatsUseLowOverheadCadence() {
        assertEquals(15 * 60L, BstatsCollector.DEFAULT_POLL_SECONDS)
    }

    @Test fun usageEventsAreQueriedOnlyForAttributableReadings() {
        assertTrue(shouldQueryForegroundApp(screenOn = true, plugged = 0, currentMa = -300))
        assertFalse(shouldQueryForegroundApp(screenOn = false, plugged = 0, currentMa = -300))
        assertFalse(shouldQueryForegroundApp(screenOn = true, plugged = 1, currentMa = 500))
        assertFalse(shouldQueryForegroundApp(screenOn = true, plugged = 0, currentMa = 0))
    }

    @Test fun screenOffSamplingAvoidsHighFrequencyWakeups() {
        assertEquals(5_000L, effectiveMonitoringIntervalMs(5_000L, screenOn = true))
        assertEquals(60_000L, effectiveMonitoringIntervalMs(5_000L, screenOn = false))
        assertEquals(300_000L, effectiveMonitoringIntervalMs(300_000L, screenOn = false))
    }

    @Test fun identicalDrainHistoryPointsAreCoalescedWithoutMissingStateChanges() {
        fun snapshot(level: Int = 50, screenOn: Boolean = true) = DrainSnapshot(
            timestamp = 1L,
            batteryLevel = level,
            batteryMah = 2_500.0,
            currentMa = -300,
            isScreenOn = screenOn,
            isCharging = false,
            isDozing = false,
            cpuAwakeTimeMs = 1L,
            deepSleepTimeMs = 0L
        )
        val previous = snapshot()
        assertTrue(shouldRecordDrainSnapshot(null, null, previous, 0L))
        assertFalse(shouldRecordDrainSnapshot(previous, 0L, snapshot(), 5_000L))
        assertTrue(shouldRecordDrainSnapshot(previous, 0L, snapshot(), DRAIN_SNAPSHOT_INTERVAL_MS))
        assertTrue(shouldRecordDrainSnapshot(previous, 0L, snapshot(level = 49), 5_000L))
        assertTrue(shouldRecordDrainSnapshot(previous, 0L, snapshot(screenOn = false), 5_000L))
    }

    @Test fun heuristicSamplesShareOneWriteAndKeepHourBoundaries() {
        val buffer = HeuristicWriteBuffer(flushIntervalMs = 60_000L)
        buffer.add("app.one", 3_599_000L, 1_000L, 0.2)
        buffer.add("app.one", 3_599_500L, 6_000L, 0.3)
        buffer.add("app.one", 3_600_000L, 7_000L, 0.4)
        assertFalse(buffer.shouldFlush(60_999L))
        assertTrue(buffer.shouldFlush(61_000L))

        val entries = buffer.snapshot().sortedBy { it.bucketStart }
        assertEquals(2, entries.size)
        assertEquals(0L, entries[0].bucketStart)
        assertEquals(0.5, entries[0].energyMah, 0.0001)
        assertEquals(2, entries[0].samples)
        assertEquals(3_600_000L, entries[1].bucketStart)
        assertEquals(0.4, entries[1].energyMah, 0.0001)

        buffer.clear()
        assertTrue(buffer.snapshot().isEmpty())
        assertFalse(buffer.shouldFlush(Long.MAX_VALUE))
    }
}
