package app.batstats.battery.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the `dumpsys batterystats --checkin` column layout. The indices are
 * easy to get wrong by one and the resulting screen just shows zeroes, so pin them down.
 */
class BatteryStatsParserTest {

    @Test
    fun `splits quoted fields containing commas`() {
        val parts = BatteryStatsParser.splitCheckinLine("""9,0,l,kwl,"alarmtimer,wake",1234,7""")
        assertEquals(listOf("9", "0", "l", "kwl", "alarmtimer,wake", "1234", "7"), parts)
    }

    @Test
    fun `plain lines split unchanged`() {
        assertEquals(
            listOf("9", "0", "l", "bt", "3", "7200000"),
            BatteryStatsParser.splitCheckinLine("9,0,l,bt,3,7200000")
        )
    }

    @Test
    fun `discharge amounts come from the screen-on and screen-off columns`() {
        // dc = low, high, screenOnAmount, screenOffAmount, ...
        val snapshot = BatteryStatsParser.parseCheckin("9,0,l,dc,11,12,21,34,0,0,0,0,0,0")
        assertEquals(21f, snapshot.screenOnDischargePercent, 0.001f)
        assertEquals(34f, snapshot.screenOffDischargePercent, 0.001f)
    }

    @Test
    fun `doze totals are read from the misc line`() {
        // 9,0,l,m,<screenOn>,<phoneOn>,<full>,<partial>,<radio>,<radioAdj>,<interactive>,
        //         <powerSave>,<connChanges>,<deepTime>,<deepCount>,<idlingTime>,<idlingCount>,
        //         <radioCount>,<radioUnknown>,<lightTime>,<lightCount>,...
        val misc = "9,0,l,m,3600000,0,0,0,0,0,3600000,0,4," +
            "1800000,5,2400000,6,0,0,900000,9,0,0,0,0"
        val snapshot = BatteryStatsParser.parseCheckin(misc)

        assertEquals(3_600_000L, snapshot.screenOnTimeMs)
        val doze = snapshot.doze
        assertNotNull(doze)
        assertEquals(1_800_000L, doze!!.deepIdleTimeMs)
        assertEquals(5, doze.deepIdleCount)
        assertEquals(900_000L, doze.lightIdleTimeMs)
        assertEquals(9, doze.lightIdleCount)
        assertEquals(2_400_000L, doze.maintenanceTimeMs)
    }

    @Test
    fun `misc line without doze columns yields no doze stats`() {
        val snapshot = BatteryStatsParser.parseCheckin("9,0,l,m,3600000,0,0,0")
        assertEquals(3_600_000L, snapshot.screenOnTimeMs)
        assertNull(snapshot.doze)
    }

    @Test
    fun `wifi signal uses the wsgt tag`() {
        val snapshot = BatteryStatsParser.parseCheckin("9,0,l,wsgt,1000,2000,3000,4000,0")
        assertEquals(5, snapshot.wifiSignal.size)
        assertEquals(2000L, snapshot.wifiSignal[1].durationMs)
        assertEquals(0.2f, snapshot.wifiSignal[1].percentOfTotal, 0.001f)
    }

    @Test
    fun `battery capacity survives a decimal value`() {
        assertEquals(4500, BatteryStatsParser.parseCheckin("9,0,l,pws,4500.00,120.5,10,20").estimatedCapacityMah)
        assertEquals(4500, BatteryStatsParser.parseCheckin("9,0,l,pws,4500,120,10,20").estimatedCapacityMah)
    }

    @Test
    fun `power use items accumulate per uid`() {
        val dump = """
            9,0,i,uid,10123,com.example.app
            9,10123,l,pwi,uid,12.50,0,0,0
            9,10123,l,pwi,uid,2.50,0,0,0
        """.trimIndent()

        val apps = BatteryStatsParser.parseCheckin(dump).apps
        assertEquals(1, apps.size)
        assertEquals("com.example.app", apps[0].packageName)
        assertEquals(15.0, apps[0].powerMah, 0.001)
    }

    @Test
    fun `device-wide power rows are not mistaken for apps`() {
        // Real rows from a device: only the "uid" label is per-app, the rest are global
        // component totals reported against uid 0.
        val dump = """
            9,0,i,uid,10123,com.example.app
            9,0,l,pwi,scrn,343,1,0,0
            9,0,l,pwi,cpu,230,0,0,0
            9,0,l,pwi,cell,256,1,0,0
            9,0,l,pwi,gnss,0.682,0,0,0
            9,0,l,pwi,???,15.8,0,0,0
            9,10123,l,pwi,uid,108,1,0,0
        """.trimIndent()

        val apps = BatteryStatsParser.parseCheckin(dump).apps
        assertEquals(1, apps.size)
        assertEquals("com.example.app", apps[0].packageName)
        assertEquals(108.0, apps[0].powerMah, 0.001)
    }

    @Test
    fun `per-uid wakelock, network and sensor detail is joined onto the app row`() {
        val dump = """
            9,0,i,uid,10123,com.example.app
            9,10123,l,pwi,uid,108,1,0,0
            9,10123,l,wl,LockA,0,f,0,0,0,60000,p,12,0,0,0
            9,10123,l,wl,LockB,0,f,0,0,0,30000,p,4,0,0,0
            9,10123,l,nt,111,222,333,444,1,2,3,4,0,0,5000,7
            9,10123,l,sr,-10000,90000,3
            9,10123,l,sr,4,45000,9
        """.trimIndent()

        val app = BatteryStatsParser.parseCheckin(dump).apps.single()
        assertEquals(90_000L, app.wakeLockTimeMs)   // 60000 + 30000
        assertEquals(90_000L, app.gpsTimeMs)        // sensor handle -10000
        assertEquals(45_000L, app.sensorTimeMs)     // everything else
        assertEquals(111L, app.mobileRxBytes)
        assertEquals(222L, app.mobileTxBytes)
        assertEquals(333L, app.wifiRxBytes)
        assertEquals(444L, app.wifiTxBytes)
    }

    @Test
    fun `apps with no detail lines keep zeroed counters`() {
        val dump = """
            9,0,i,uid,10123,com.example.app
            9,10123,l,pwi,uid,108,1,0,0
        """.trimIndent()

        val app = BatteryStatsParser.parseCheckin(dump).apps.single()
        assertEquals(0L, app.wakeLockTimeMs)
        assertEquals(0L, app.mobileRxBytes)
        assertEquals(108.0, app.powerMah, 0.001)
    }

    @Test
    fun `wakelock partial block is located by its marker`() {
        // wl = tag, then "<time>,f,<count>,...", "<time>,p,<count>,...", "<time>,bp,<count>,..."
        val dump = """
            9,0,i,uid,10123,com.example.app
            9,10123,l,wl,MyLock,0,f,0,0,0,60000,p,12,0,0,0,15000,bp,3,0,0,0
        """.trimIndent()

        val wl = BatteryStatsParser.parseCheckin(dump).wakelocks.single()
        assertEquals("MyLock", wl.tag)
        assertEquals(60_000L, wl.totalTimeMs)
        assertEquals(12, wl.count)
        assertEquals(15_000L, wl.backgroundTimeMs)
        assertEquals(3, wl.backgroundCount)
    }

    @Test
    fun `repeated uid and tag pairs are merged rather than duplicated`() {
        // Compose LazyColumn throws on duplicate item keys, so the parser must collapse these.
        val dump = """
            9,0,i,uid,10123,com.example.app
            9,10123,l,wl,MyLock,0,f,0,0,0,1000,p,1,0,0,0
            9,10123,l,wl,MyLock,0,f,0,0,0,2000,p,2,0,0,0
            9,10123,l,wua,MyAlarm,3,1000,2
            9,10123,l,wua,MyAlarm,4,2000,1
        """.trimIndent()

        val snapshot = BatteryStatsParser.parseCheckin(dump)

        val wl = snapshot.wakelocks.single()
        assertEquals(3_000L, wl.totalTimeMs)
        assertEquals(3, wl.count)

        val alarm = snapshot.alarms.single()
        assertEquals(7, alarm.count)
        assertEquals(3, alarm.wakeups)

        assertEquals(
            snapshot.wakelocks.size,
            snapshot.wakelocks.distinctBy { it.uid to it.tag }.size
        )
    }

    @Test
    fun `device idle whitelists are collected under every heading variant`() {
        val dump = """
            Settings:
              mDeepEnabled=true
              mLightEnabled=false
            mState=IDLE
            mLightState=ACTIVE
            Whitelist (except idle) system apps:
              com.android.providers.downloads
            Whitelist user apps:
              com.example.app
            Temp whitelist (via system):
              UID=10123: 5000ms - reason
        """.trimIndent()

        val info = BatteryStatsParser.parseDeviceIdle(dump)
        assertEquals("IDLE", info.currentState)
        assertEquals("ACTIVE", info.lightState)
        assertTrue(info.deepEnabled)
        assertTrue(!info.lightEnabled)
        assertTrue(info.whitelistedApps.contains("com.android.providers.downloads"))
        assertTrue(info.whitelistedApps.contains("com.example.app"))
        assertEquals(listOf("UID=10123"), info.tempWhitelistedApps)
    }

    @Test
    fun `power manager sections are detected despite the size suffix`() {
        val dump = """
            Power Manager State:
              mWakefulness=Awake
              mIsPowered=false
              mBatteryLevel=87
              mLowPowerModeEnabled=true
              mDeviceIdleMode=false

            Wake Locks: size=2
              PARTIAL_WAKE_LOCK 'AudioMix'
              PARTIAL_WAKE_LOCK 'NetworkStats'

            Suspend Blockers: size=1
              PowerManagerService.WakeLocks: 1
        """.trimIndent()

        val info = BatteryStatsParser.parsePowerManager(dump)
        assertTrue(info.isScreenOn)
        assertEquals(87, info.batteryLevel)
        assertEquals("Discharging", info.batteryStatus)
        assertTrue(info.lowPowerMode)
        assertEquals(2, info.holdingWakeLocks.size)
        assertEquals(1, info.suspendBlockers.size)
    }
}
