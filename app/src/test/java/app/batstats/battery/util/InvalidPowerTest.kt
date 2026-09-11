package app.batstats.battery.util

import org.junit.Assert.*
import org.junit.Test

class InvalidPowerTest {
    @Test fun malformedPowerCannotPoisonTotalsOrChartProgress() {
        val snapshot = BatteryStatsParser.parseCheckin("""
            9,10001,l,pwi,uid,NaN
            9,10002,l,pwi,uid,Infinity
            9,10003,l,pwi,uid,-1
            9,10004,l,pwi,uid,12.5,0,NaN
            9,0,l,pws,Infinity,0,0,0
        """.trimIndent())
        assertEquals(1, snapshot.apps.size)
        assertEquals(12.5, snapshot.apps.single().powerMah, 0.001)
        assertEquals(0.0, snapshot.apps.single().screenPowerMah, 0.0)
        assertEquals(0, snapshot.estimatedCapacityMah)
    }

    @Test fun cpuSuspensionUsesBatteryUptimeRatherThanDozeModeTime() {
        val snapshot = BatteryStatsParser.parseCheckin("9,0,l,bt,1,900000,300000,0,0")
        assertEquals(900000L, snapshot.batteryRealtimeMs)
        assertEquals(300000L, snapshot.batteryUptimeMs)
    }
}
