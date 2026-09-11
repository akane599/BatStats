package app.batstats.battery.shizuku

import org.junit.Assert.*
import org.junit.Test

class EnergyDeltasTest {
    private fun snapshot(time: Long, vararg readings: Pair<Int, Double>) =
        CheckinParser.Snapshot(mapOf(*readings), emptyMap(), time)

    @Test fun newUidsAreBaselinedAndExistingUidsUseDifferences() {
        val result = energyDeltas(snapshot(100, 1 to 10.0), snapshot(200, 1 to 12.0, 2 to 300.0))
        assertEquals(mapOf(1 to 2.0), result)
    }

    @Test fun resetDoesNotMixAccountingEpochs() {
        assertTrue(energyDeltas(snapshot(100, 1 to 10.0), snapshot(50, 1 to 20.0)).isEmpty())
    }

    @Test fun missingNegativeAndNonFiniteDataCannotBecomeDrain() {
        assertTrue(energyDeltas(null, snapshot(10, 1 to 100.0)).isEmpty())
        assertTrue(energyDeltas(snapshot(10, 1 to 10.0), snapshot(20, 1 to 1.0)).isEmpty())
        val parsed = CheckinParser.parse(sequenceOf("9,1,l,pwi,uid,NaN", "9,2,l,pwi,uid,Infinity", "9,3,l,pwi,uid,-1"))
        assertTrue(parsed.perUidMah.isEmpty())
    }
}
