package app.batstats.battery.data

import app.batstats.battery.data.db.SessionType
import org.junit.Assert.*
import org.junit.Test

class BatteryCsvTest {
    @Test fun `sample preserves missing measurements and signed current`() {
        val sample = BatteryCsv.parseSample("1000,80,3,0,-120000,,4000,320,,true")
        assertEquals(80, sample.levelPercent)
        assertEquals(-120000L, sample.currentNowUa)
        assertNull(sample.chargeCounterUah)
        assertNull(sample.health)
        assertTrue(sample.screenOn)
    }

    @Test fun `active session preserves trailing empty columns`() {
        val session = BatteryCsv.parseSession("test-session,DISCHARGE,1000,,80,,,,")
        assertEquals(SessionType.DISCHARGE, session.type)
        assertNull(session.endTime)
        assertNull(session.endLevel)
        assertNull(session.estCapacityMah)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invalid boolean must not silently become screen off`() {
        BatteryCsv.parseSample("1000,80,3,0,-120000,,4000,320,,invalid")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `missing columns fail the import`() {
        BatteryCsv.parseSample("1000,80,3")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `extra columns are not silently discarded`() {
        BatteryCsv.parseSample("1000,80,3,0,-120000,,4000,320,,true,extra")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unknown session type fails the import`() {
        BatteryCsv.parseSession("test-session,UNKNOWN,1000,,80,,,,")
    }
}
