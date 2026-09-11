package app.batstats.battery.util

import android.os.BatteryManager
import org.junit.Assert.*
import org.junit.Test

class BatteryValuesTest {
    @Test fun unsupportedSensorsStayUnknown() {
        assertNull(batteryCurrent(Long.MIN_VALUE, Long.MIN_VALUE))
        assertNull(batteryProperty(Int.MIN_VALUE.toLong()))
        assertNull(batteryProperty(Long.MIN_VALUE))
    }

    @Test fun usesAverageOnlyWhenInstantaneousIsUnavailable() {
        assertEquals(-500_000L, batteryCurrent(Long.MIN_VALUE, -500_000L))
        assertEquals(-100_000L, batteryCurrent(-100_000L, -500_000L))
        assertEquals(0L, batteryCurrent(0, Long.MIN_VALUE))
    }

    @Test fun rejectsMissingOrInvalidLevelsWithoutReportingAnEmptyBattery() {
        assertNull(batteryLevel(-1, 100))
        assertNull(batteryLevel(50, 0))
        assertNull(batteryLevel(101, 100))
        assertEquals(50, batteryLevel(100, 200))
        assertEquals(100, batteryLevel(Int.MAX_VALUE, Int.MAX_VALUE))
    }

    @Test fun powerStateCorrectsOemInvertedCurrentWithoutInventingConnectedFlow() {
        assertEquals(-900_000L, normalizeBatteryCurrent(900_000L, 0, BatteryManager.BATTERY_STATUS_DISCHARGING))
        assertEquals(-900_000L, normalizeBatteryCurrent(-900_000L, 0, BatteryManager.BATTERY_STATUS_UNKNOWN))
        assertEquals(900_000L, normalizeBatteryCurrent(-900_000L, 1, BatteryManager.BATTERY_STATUS_CHARGING))
        assertEquals(-20_000L, normalizeBatteryCurrent(-20_000L, 1, BatteryManager.BATTERY_STATUS_FULL))
        assertEquals(0L, normalizeBatteryCurrent(0L, 0, BatteryManager.BATTERY_STATUS_DISCHARGING))
        assertNull(normalizeBatteryCurrent(null, 0, BatteryManager.BATTERY_STATUS_DISCHARGING))
        assertNull(normalizeBatteryCurrent(Long.MIN_VALUE, 0, BatteryManager.BATTERY_STATUS_DISCHARGING))
    }
}
