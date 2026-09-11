package app.batstats.battery.util

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
}
