package app.batstats.battery.util

import android.os.BatteryManager
import app.batstats.battery.data.db.BatterySample
import org.junit.Assert.*
import org.junit.Test

class MonitorReadingsTest {
    private fun sample(current: Long? = -366_000, voltage: Int? = 4000, temperature: Int? = 250,
                       status: Int = BatteryManager.BATTERY_STATUS_DISCHARGING, plugged: Int = 0) = BatterySample(
        timestamp = 123L, levelPercent = 50, status = status, plugged = plugged,
        currentNowUa = current, chargeCounterUah = null, voltageMv = voltage,
        temperatureDeciC = temperature, health = null, screenOn = true
    )

    @Test fun absentSensorsNeverBecomeZeroMeasurements() {
        val reading = MonitorReadings.from(sample(current = null, voltage = null, temperature = null))
        assertNull(reading.currentMa)
        assertNull(reading.voltageMv)
        assertNull(reading.temperatureC)
        assertNull(reading.powerMw)
        assertEquals("—", formatMonitorValue(reading.powerMw, "mW"))
        assertEquals("—", formatMonitorCurrentMagnitude(reading.currentMa))
    }

    @Test fun genuineZeroSensorsStayZero() {
        val reading = MonitorReadings.from(sample(current = 0, temperature = 0))
        assertEquals(0.0, reading.currentMa!!, 0.0)
        assertEquals(0.0, reading.temperatureC!!, 0.0)
        assertEquals(0.0, reading.powerMw!!, 0.0)
    }

    @Test fun currentDirectionIsPreservedAndPowerNeedsBothSensors() {
        val discharge = MonitorReadings.from(sample())
        assertEquals(-366.0, discharge.currentMa!!, 0.0)
        assertEquals(1464.0, discharge.powerMw!!, 0.0)
        assertEquals(366.0, MonitorReadings.from(sample(current = 366_000)).currentMa!!, 0.0)
        assertNull(MonitorReadings.from(sample(voltage = null)).powerMw)
        assertNull(MonitorReadings.from(sample(voltage = 0)).powerMw)
    }

    @Test fun connectedDoesNotMeanCharging() {
        assertEquals(MonitorStatus.CONNECTED, MonitorReadings.from(sample(plugged = 1)).status)
        assertEquals(MonitorStatus.CHARGING, MonitorReadings.from(sample(status = BatteryManager.BATTERY_STATUS_CHARGING, plugged = 1)).status)
        assertEquals(MonitorStatus.FULL, MonitorReadings.from(sample(status = BatteryManager.BATTERY_STATUS_FULL, plugged = 1)).status)
    }

    @Test fun missingFirstReadingNeverClaimsAnEmptyBattery() {
        val missing = MonitorReadings.from(null)
        assertNull(missing.level)
        assertNull(missing.timestamp)
        assertEquals(MonitorStatus.UNKNOWN, missing.status)
    }

    @Test fun nonfiniteAndUnsupportedInputsAreUnknown() {
        assertEquals("—", formatMonitorValue(Double.NaN, "mW"))
        assertEquals("—", formatMonitorValue(Double.POSITIVE_INFINITY, "mW"))
        assertEquals("—", formatMonitorCurrentMagnitude(Double.NaN))
        assertNull(MonitorReadings.from(sample(current = Long.MIN_VALUE)).currentMa)
        assertNull(MonitorReadings.from(sample(current = Int.MIN_VALUE.toLong())).currentMa)
    }

    @Test fun subMilliampReadingIsNotTruncatedToZero() {
        assertEquals(-0.001, MonitorReadings.from(sample(current = -1)).currentMa!!, 0.0)
        assertEquals("< 0.1 mA", formatMonitorCurrentMagnitude(-0.001))
    }
}
