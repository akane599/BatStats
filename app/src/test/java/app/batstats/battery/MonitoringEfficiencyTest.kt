package app.batstats.battery

import app.batstats.battery.shizuku.BstatsCollector
import app.batstats.battery.data.effectiveMonitoringIntervalMs
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
}
