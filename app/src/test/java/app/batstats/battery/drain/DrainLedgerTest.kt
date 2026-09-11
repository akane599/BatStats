package app.batstats.battery.drain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DrainLedgerTest {
    private fun reading(time: Long, charge: Double?, screenOn: Boolean = true,
        powered: Boolean? = false, slept: Long = 0L) = DrainReading(time, slept, screenOn, powered, charge)

    @Test fun `reported twelve minute screen-on session has no idle or screen-off allocation`() {
        val ledger = DrainLedger()
        ledger.reset(reading(0L, 5_000.0), running = true)
        ledger.advance(reading(300_000L, 4_965.7)) // 5m, 34.3 mAh
        ledger.stop(reading(722_000L, 4_926.5)) // 7m 2s, 39.2 mAh
        assertEquals(722_000L, ledger.sessionElapsedMs)
        assertEquals(722_000L, ledger.totals.screenOnTimeMs)
        assertEquals(73.5, requireNotNull(ledger.totals.screenOnDrainMah), 0.0001)
        assertEquals(0L, ledger.totals.screenOffTimeMs)
        assertEquals(0L, ledger.totals.deepSleepTimeMs)
        assertEquals(0L, ledger.totals.awakeTimeMs)
        assertEquals(0.0, requireNotNull(ledger.totals.screenOffDrainMah), 0.0001)
    }

    @Test fun `screen boundary credits two seconds off instead of the whole polling minute`() {
        val ledger = DrainLedger()
        ledger.reset(reading(0L, 5_000.0), running = true)
        ledger.advance(reading(58_000L, 4_999.42, screenOn = false))
        ledger.stop(reading(60_000L, 4_999.40, slept = 1_000L))
        assertEquals(58_000L, ledger.totals.screenOnTimeMs)
        assertEquals(2_000L, ledger.totals.screenOffTimeMs)
        assertEquals(1_000L, ledger.totals.deepSleepTimeMs)
        assertEquals(1_000L, ledger.totals.awakeTimeMs)
        assertEquals(0.58, requireNotNull(ledger.totals.screenOnDrainMah), 0.0001)
        assertEquals(0.02, requireNotNull(ledger.totals.screenOffDrainMah), 0.0001)
    }

    @Test fun `plug boundary keeps preceding discharge while powered intervals are excluded`() {
        val ledger = DrainLedger()
        ledger.reset(reading(0L, 5_000.0), running = true)
        ledger.advance(reading(10_000L, 4_999.0, powered = true))
        ledger.advance(reading(70_000L, 5_020.0, powered = true))
        ledger.advance(reading(90_000L, 5_030.0))
        ledger.advance(reading(100_000L, 5_028.0, screenOn = false))
        ledger.stop(reading(110_000L, 5_027.0, screenOn = false))
        assertEquals(110_000L, ledger.sessionElapsedMs)
        assertEquals(20_000L, ledger.totals.screenOnTimeMs)
        assertEquals(10_000L, ledger.totals.screenOffTimeMs)
        assertEquals(3.0, requireNotNull(ledger.totals.screenOnDrainMah), 0.0001)
        assertEquals(1.0, requireNotNull(ledger.totals.screenOffDrainMah), 0.0001)
    }

    @Test fun `missing or rising counter prevents understated rates without losing time`() {
        listOf(null, 5_001.0).forEach { invalidEnd ->
            val ledger = DrainLedger()
            ledger.reset(reading(0L, 5_000.0), running = true)
            ledger.advance(reading(60_000L, invalidEnd))
            ledger.advance(reading(120_000L, 4_990.0, screenOn = false))
            ledger.stop(reading(180_000L, 4_989.0, screenOn = false))
            assertEquals(120_000L, ledger.totals.screenOnTimeMs)
            assertEquals(60_000L, ledger.totals.screenOffTimeMs)
            assertNull(ledger.totals.screenOnDrainMah)
            assertNull(drainRateOver(ledger.totals.screenOnDrainMah, ledger.totals.screenOnTimeMs))
            assertEquals(1.0, requireNotNull(ledger.totals.screenOffDrainMah), 0.0001)
        }
    }

    @Test fun `unknown power state is excluded until battery discharge is established`() {
        val ledger = DrainLedger()
        ledger.reset(reading(0L, 5_000.0, powered = null), running = true)
        ledger.advance(reading(60_000L, 4_990.0))
        ledger.stop(reading(120_000L, 4_989.0))
        assertEquals(120_000L, ledger.sessionElapsedMs)
        assertEquals(60_000L, ledger.totals.screenOnTimeMs)
        assertEquals(1.0, requireNotNull(ledger.totals.screenOnDrainMah), 0.0001)
    }

    @Test fun `stop freezes the session and reset permits a fresh valid measurement`() {
        val ledger = DrainLedger()
        ledger.reset(reading(0L, 5_000.0), running = true)
        ledger.stop(reading(120_000L, null))
        assertNull(ledger.totals.screenOnDrainMah)
        val stoppedTotals = ledger.totals.copy()
        ledger.advance(reading(500_000L, 4_500.0, screenOn = false, slept = 300_000L))
        assertEquals(120_000L, ledger.sessionElapsedMs)
        assertEquals(stoppedTotals, ledger.totals)

        ledger.reset(reading(600_000L, 4_500.0), running = false)
        ledger.advance(reading(660_000L, 4_400.0))
        assertEquals(0L, ledger.sessionElapsedMs)
        assertEquals(DrainTotals(), ledger.totals)

        ledger.reset(reading(700_000L, 4_400.0), running = true)
        ledger.stop(reading(820_000L, 4_399.0))
        assertEquals(120_000L, ledger.sessionElapsedMs)
        assertEquals(120_000L, ledger.totals.screenOnTimeMs)
        assertEquals(1.0, requireNotNull(ledger.totals.screenOnDrainMah), 0.0001)
    }

    @Test fun `unchanged valid charge counter is measured zero after the observation window`() {
        val ledger = DrainLedger()
        ledger.reset(reading(0L, 5_000.0), running = true)
        ledger.stop(reading(MIN_RATE_WINDOW_MS, 5_000.0))
        val rate = drainRateOver(ledger.totals.screenOnDrainMah, ledger.totals.screenOnTimeMs)
        assertEquals(0.0, requireNotNull(rate), 0.0)
        assertEquals("0 mA", formatDrainRate(rate))
    }
}
