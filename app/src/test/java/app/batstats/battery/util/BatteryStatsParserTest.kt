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
    fun `cpu and process-state times are read from a real dump`() {
        // Verbatim lines from a device dump (uid 10049 and 10071).
        val dump = """
            9,0,i,uid,10049,com.example.sync
            9,0,i,uid,10071,com.example.app
            9,10049,l,pwi,uid,50,1,0,0
            9,10049,l,fgs,90135,18
            9,10049,l,st,0,9370650,7596684,0,0,0,0
            9,10049,l,cpu,122404,67158,0
            9,10071,l,pwi,uid,20,1,0,0
            9,10071,l,fg,61588,7
            9,10071,l,st,62137,0,0,6,0,0,12239865
            9,10071,l,cpu,9508,3312,0
        """.trimIndent()

        val apps = BatteryStatsParser.parseCheckin(dump).apps.associateBy { it.packageName }

        val sync = apps.getValue("com.example.sync")
        assertEquals(189_562L, sync.cpuTimeMs)              // 122404 user + 67158 system
        assertEquals(9_370_650L, sync.foregroundServiceTimeMs)
        assertEquals(7_596_684L, sync.foregroundTimeMs)
        assertEquals(0L, sync.topTimeMs)

        val app = apps.getValue("com.example.app")
        assertEquals(12_820L, app.cpuTimeMs)                // 9508 + 3312
        assertEquals(62_137L, app.topTimeMs)
        assertEquals(6L, app.backgroundTimeMs)
        assertEquals(12_239_865L, app.cachedTimeMs)
    }

    @Test
    fun `standalone fg and fgs timers fill in when there is no state line`() {
        val dump = """
            9,0,i,uid,10071,com.example.app
            9,10071,l,pwi,uid,20,1,0,0
            9,10071,l,fg,61588,7
            9,10071,l,fgs,1234,2
        """.trimIndent()

        val app = BatteryStatsParser.parseCheckin(dump).apps.single()
        assertEquals(61_588L, app.foregroundTimeMs)
        assertEquals(1_234L, app.foregroundServiceTimeMs)
    }

    @Test
    fun `short state lines still yield top time`() {
        // Older releases emit fewer process-state columns; "top" is the stable one.
        val dump = """
            9,0,i,uid,10071,com.example.app
            9,10071,l,pwi,uid,20,1,0,0
            9,10071,l,st,5000,0,0
        """.trimIndent()

        val app = BatteryStatsParser.parseCheckin(dump).apps.single()
        assertEquals(5_000L, app.topTimeMs)
        assertEquals(0L, app.cachedTimeMs)
    }

    @Test
    fun `network line from a real dump maps to the right columns`() {
        val dump = """
            9,0,i,uid,10069,com.example.app
            9,10069,l,pwi,uid,20,1,0,0
            9,10069,l,nt,12570,9930,0,0,28,20,0,0,0,2,0,0,0,0,0,0,0,0,0,0,0,0
        """.trimIndent()

        val app = BatteryStatsParser.parseCheckin(dump).apps.single()
        assertEquals(12_570L, app.mobileRxBytes)
        assertEquals(9_930L, app.mobileTxBytes)
        assertEquals(0L, app.wifiRxBytes)
        assertEquals(0L, app.wifiTxBytes)
    }

    @Test
    fun `estimated power use rows are parsed into per-state power`() {
        // Verbatim rows from a device, including the variants that trip up a naive regex:
        // a system uid with no duration, a state with power but no bracketed duration,
        // and values small enough to need more than two decimals.
        val dump = """
              Estimated power use (mAh):
              UID u0a285: 219 fg: 99.9 (22m 19s 961ms) bg: 2.80 (15m 40s 873ms) cached: 42.3 (4h 0m 31s 150ms)
              UID u0a479: 109 fg: 48.8 (12m 58s 247ms) bg: 16.7 (24m 12s 545ms) fgs: 25.1 (6m 36s 85ms) cached: 13.1 (3h 26m 4s 105ms)
              UID 1000: 87.4 bg: 87.4
              UID u0a432: 25.6 fg: 2.60 (6m 14s 125ms) bg: 0.544 fgs: 1.44 (2h 52m 7s 789ms) cached: 0.226 (19s 299ms)
              UID u0a528: 8.11 fg: 0.140 (2m 15s 35ms) bg: 0.139 (1s 415ms) fgs: 0.0000790 (2s 585ms) cached: 0.290 (3h 28m 19s 275ms)
              UID 0: 33.3 bg: 33.3
        """.trimIndent()

        val byUid = BatteryStatsParser.parseEstimatedPowerUse(dump)

        // u0a285 -> 10000 + 285
        val app = byUid.getValue(10285)
        assertEquals(listOf("fg", "bg", "cached"), app.map { it.state })
        assertEquals(99.9, app[0].powerMah, 0.001)
        assertEquals(22 * 60_000L + 19_000L + 961L, app[0].durationMs)
        assertEquals("Foreground", app[0].label)
        assertEquals(4 * 3_600_000L + 31_000L + 150L, app[2].durationMs)

        // Plain numeric uids stay as they are.
        val system = byUid.getValue(1000)
        assertEquals(1, system.size)
        assertEquals(87.4, system[0].powerMah, 0.001)
        assertEquals(0L, system[0].durationMs)   // no bracketed duration on this row
        assertTrue(byUid.containsKey(0))

        // A state can carry power without a duration mid-line.
        val mixed = byUid.getValue(10432)
        assertEquals(listOf("fg", "bg", "fgs", "cached"), mixed.map { it.state })
        assertEquals(0.544, mixed[1].powerMah, 0.0001)
        assertEquals(0L, mixed[1].durationMs)

        // Very small values must survive parsing.
        val tiny = byUid.getValue(10528).first { it.state == "fgs" }
        assertEquals(0.0000790, tiny.powerMah, 1e-9)
        assertEquals(2_585L, tiny.durationMs)
    }

    @Test
    fun `a state with zero power but a real duration is kept`() {
        // Real rows: a state can be credited no power yet still report time spent there.
        // Dropping those would silently lose "this app ran, it just cost nothing".
        val dump = """
              UID u0a83: 0.0585 fg: 0 (806ms) bg: 0.00845 (7ms) cached: 0.00521 (2m 0s 434ms)
              UID u0a86: 0.00406 bg: 0.00400 (1m 54s 227ms) fgs: 0.0000620 (6s 2ms) cached: 0 (95ms)
              UID u0a143: 0.000401 fgs: 0.0000620 (30s 62ms) cached: 0.000339 (8m 6s 497ms)
        """.trimIndent()

        val byUid = BatteryStatsParser.parseEstimatedPowerUse(dump)

        val zeroFg = byUid.getValue(10083).first { it.state == "fg" }
        assertEquals(0.0, zeroFg.powerMah, 1e-9)
        assertEquals(806L, zeroFg.durationMs)

        val zeroCached = byUid.getValue(10086).first { it.state == "cached" }
        assertEquals(0.0, zeroCached.powerMah, 1e-9)
        assertEquals(95L, zeroCached.durationMs)

        // A row can start at fgs with no fg or bg at all.
        assertEquals(listOf("fgs", "cached"), byUid.getValue(10143).map { it.state })
    }

    @Test
    fun `fgs is not misread as fg`() {
        val byUid = BatteryStatsParser.parseEstimatedPowerUse(
            "  UID u0a616: 5.78 fg: 0.0303 (1m 38s 445ms) bg: 0.000344 (9s 286ms) fgs: 0.0510 (6m 48s 831ms)"
        )
        val states = byUid.getValue(10616)
        assertEquals(listOf("fg", "bg", "fgs"), states.map { it.state })
        assertEquals(0.0303, states[0].powerMah, 1e-6)
        assertEquals(0.0510, states[2].powerMah, 1e-6)
        assertEquals(6 * 60_000L + 48_000L + 831L, states[2].durationMs)
    }

    @Test
    fun `power states are folded onto the matching app row`() {
        val snapshot = BatteryStatsParser.parseCheckin(
            """
            9,0,i,uid,10285,com.example.app
            9,0,i,uid,10999,com.example.other
            9,10285,l,pwi,uid,219,1,0,0
            9,10999,l,pwi,uid,5,1,0,0
            """.trimIndent()
        )
        val byUid = BatteryStatsParser.parseEstimatedPowerUse(
            "  UID u0a285: 219 fg: 99.9 (22m 19s 961ms) bg: 2.80 (15m 40s 873ms)"
        )

        val apps = BatteryStatsParser.applyPowerStates(snapshot, byUid).apps
            .associateBy { it.packageName }
        assertEquals(2, apps.getValue("com.example.app").powerByState.size)
        assertTrue(apps.getValue("com.example.other").powerByState.isEmpty())
    }

    @Test
    fun `unparseable power-use text yields nothing rather than bad rows`() {
        assertTrue(BatteryStatsParser.parseEstimatedPowerUse("").isEmpty())
        assertTrue(BatteryStatsParser.parseEstimatedPowerUse("Wake locks: size=3").isEmpty())
        assertTrue(BatteryStatsParser.parseEstimatedPowerUse("  UID u0a1: 5").isEmpty())
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
