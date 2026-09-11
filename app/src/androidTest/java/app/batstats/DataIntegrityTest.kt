package app.batstats

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.batstats.battery.data.*
import app.batstats.battery.data.db.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DataIntegrityTest {
    private lateinit var db: BatteryDatabase
    private lateinit var manager: ExportImportManager
    private lateinit var dir: File

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, BatteryDatabase::class.java).build()
        manager = ExportImportManager(context, db)
        dir = File(context.cacheDir, "import-tests").apply { mkdirs() }
    }

    @After fun teardown() { db.close(); dir.deleteRecursively() }

    private fun file(name: String, text: String): Uri = Uri.fromFile(File(dir, name).apply { writeText(text) })
    private fun sample(at: Long, ua: Long? = -200_000) = BatterySample(
        timestamp = at, levelPercent = 50, status = 3, plugged = 0, currentNowUa = ua,
        chargeCounterUah = 2_000_000, voltageMv = 4000, temperatureDeciC = 250, health = 2, screenOn = true
    )
    private fun session(id: String, start: Long, end: Long?) = ChargeSession(
        id, SessionType.DISCHARGE, start, end, 80, end?.let { 60 }, null, null, null, autoStarted = true
    )

    @Test fun csvIsIdempotentAcrossMultipleBatchesAndRejectsPartialImports() = runBlocking {
        val rows = (1..1001).joinToString("\n") { "$it,50,3,0,-200000,2000000,4000,250,2,true" }
        val good = file("good.csv", BatteryCsv.SAMPLE_HEADER + "\n" + rows)
        assertTrue(manager.importCsv(good))
        assertTrue(manager.importCsv(good))
        assertEquals(1001L, db.batteryDao().sampleCount().first())
        db.clearAllTables()
        val bad = file("bad.csv", BatteryCsv.SAMPLE_HEADER + "\n" + rows + "\n1002,101,3,0,,,,,,true")
        assertFalse(manager.importCsv(bad))
        assertEquals(0L, db.batteryDao().sampleCount().first())
    }

    @Test fun conflictingOpenSessionRollsBackSamplesToo() = runBlocking {
        db.sessionDao().upsert(session("current", 10, null))
        val archive = BatteryExport(listOf(sample(20)), listOf(session("another", 20, null)))
        assertFalse(manager.importJson(file("conflict.json", Json.encodeToString(BatteryExport.serializer(), archive))))
        assertEquals(0L, db.batteryDao().sampleCount().first())
        assertEquals("current", db.sessionDao().active()?.sessionId)
    }

    @Test fun exportIncludesSessionsOverlappingWindowAndHonorsSelection() = runBlocking {
        db.sessionDao().upsertAll(listOf(session("overlap", 10, 90), session("earlier", 1, 9), session("active", 20, null)))
        db.batteryDao().insertSamples(listOf(sample(5), sample(50), sample(100)))
        val output = file("export.json", "stale contents that must be truncated")
        assertTrue(manager.exportJson(output, 40, 60, false, true))
        val archive = Json.decodeFromString(BatteryExport.serializer(), File(output.path!!).readText())
        assertTrue(archive.samples.isEmpty())
        assertEquals(setOf("overlap", "active"), archive.sessions.map { it.sessionId }.toSet())
        assertTrue(archive.sessions.all { it.autoStarted })
    }

    @Test fun currentChartAveragesKnownReadingsAndBoundsTheNumberOfPoints() = runBlocking {
        db.batteryDao().insertSamples((0..999).map { sample(it * 1000L, if (it % 2 == 0) -200_000 else null) })
        val points = db.batteryDao().currentChart(0, 999_000, 10_000).first()
        assertEquals(100, points.size)
        assertTrue(points.all { it.currentMa == -200.0 })
    }

    @Test fun systemSourcesAreCombinedWithoutMixingForegroundEstimates() = runBlocking {
        val dao = db.appEnergyDao()
        dao.incrementBatch(mapOf("app.one" to 2.0), 1000, "ROOT")
        dao.incrementBatch(mapOf("app.one" to 3.0), 1000, "SHIZUKU")
        dao.incrementBatch(mapOf("app.one" to 100.0), 1000, "HEURISTIC")
        assertEquals(5.0, dao.drainersInRange(0, 2000, "MEASURED").first().single().energyMah, 0.001)
    }
}
