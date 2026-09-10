package app.batstats.battery.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CheckinParserTest {

    private fun parse(vararg lines: String) = CheckinParser.parse(lines.asSequence())

    @Test
    fun `per-app power is keyed by uid`() {
        val snapshot = parse(
            "9,0,i,uid,10234,com.example.app",
            "9,10234,l,pwi,uid,42.5,0,3.2,1.1"
        )
        assertEquals(42.5, snapshot.perUidMah[10234]!!, 0.001)
        assertEquals("com.example.app", snapshot.uidToPackage[10234])
    }

    @Test
    fun `device-wide components are not charged to an app`() {
        // These are reported against uid 0. Without the "uid" label check they were folded
        // into whichever package owns uid 0, so one app appeared to be responsible for the
        // screen and the radio.
        val snapshot = parse(
            "9,0,i,uid,0,android",
            "9,0,l,pwi,screen,220.6,0",
            "9,0,l,pwi,cell,88.1,0",
            "9,0,l,pwi,uid,4.0,0"
        )
        assertEquals(4.0, snapshot.perUidMah[0]!!, 0.001)
    }

    @Test
    fun `repeated rows for one uid accumulate`() {
        val snapshot = parse(
            "9,10234,l,pwi,uid,10.0,0",
            "9,10234,l,pwi,uid,5.5,0"
        )
        assertEquals(15.5, snapshot.perUidMah[10234]!!, 0.001)
    }

    @Test
    fun `a dump with no package map still reports energy`() {
        // Through ADB-granted DUMP the uid map comes back largely empty; the caller resolves
        // the names itself, so the energy must survive without them.
        val snapshot = parse("9,10234,l,pwi,uid,7.25,0")
        assertEquals(7.25, snapshot.perUidMah[10234]!!, 0.001)
        assertNull(snapshot.uidToPackage[10234])
    }

    @Test
    fun `quoted names containing commas do not shift the columns`() {
        val snapshot = parse(
            "9,0,i,uid,10234,\"com.example,weird\"",
            "9,10234,l,pwi,uid,3.5,0"
        )
        assertEquals("com.example,weird", snapshot.uidToPackage[10234])
        assertEquals(3.5, snapshot.perUidMah[10234]!!, 0.001)
    }

    @Test
    fun `malformed and unrelated lines are skipped`() {
        val snapshot = parse(
            "",
            "9,0",
            "9,10234,l,wl,SomeWakelock,p,3,1000",
            "9,10234,l,pwi,uid,not-a-number,0",
            "9,10234,l,pwi,uid,2.0,0"
        )
        assertEquals(2.0, snapshot.perUidMah[10234]!!, 0.001)
    }
}
