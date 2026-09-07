package app.batstats.battery.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.room.withTransaction
import app.batstats.battery.data.db.BatteryDatabase
import app.batstats.battery.data.db.BatterySample
import app.batstats.battery.data.db.ChargeSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class BatteryExport(
    val samples: List<BatterySample> = emptyList(),
    val sessions: List<ChargeSession> = emptyList()
)

class ExportImportManager(
    private val context: Context,
    private val db: BatteryDatabase
) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    suspend fun exportJson(
        dest: Uri,
        from: Long,
        to: Long,
        includeSamples: Boolean,
        includeSessions: Boolean
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val toBound = if (to == 0L) Long.MAX_VALUE else to

            val samples = if (includeSamples) {
                db.batteryDao().samplesBetween(if (from == 0L) 0L else from, toBound).first()
            } else emptyList()

            val sessions = if (includeSessions) {
                db.sessionDao().sessionsOverlapping(from, toBound)
            } else emptyList()

            val out = context.contentResolver.openOutputStream(dest, "wt") ?: return@withContext false
            out.use {
                it.write(
                    json.encodeToString(
                        BatteryExport.serializer(),
                        BatteryExport(samples, sessions)
                    ).toByteArray()
                )
            }
            true
        }.getOrElse { failure ->
            if (failure is CancellationException) throw failure
            false
        }
    }

    suspend fun exportCsvToFolder(tree: Uri, from: Long, to: Long): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val dir = DocumentFile.fromTreeUri(context, tree) ?: return@withContext false
                val cr = context.contentResolver
                val toBound = if (to == 0L) Long.MAX_VALUE else to

                // Samples
                val f1 = dir.createFile("text/csv", "battery_samples.csv") ?: return@withContext false
                cr.openOutputStream(f1.uri)?.bufferedWriter()?.use { w ->
                    w.appendLine(BatteryCsv.SAMPLE_HEADER)
                    db.batteryDao()
                        .samplesBetween(if (from == 0L) 0L else from, toBound).first()
                        .forEach { s ->
                            w.appendLine("${s.timestamp},${s.levelPercent},${s.status},${s.plugged},${s.currentNowUa ?: ""},${s.chargeCounterUah ?: ""},${s.voltageMv ?: ""},${s.temperatureDeciC ?: ""},${s.health ?: ""},${s.screenOn}")
                        }
                } ?: return@withContext false

                // Sessions
                val f2 = dir.createFile("text/csv", "charge_sessions.csv") ?: return@withContext false
                cr.openOutputStream(f2.uri)?.bufferedWriter()?.use { w ->
                    w.appendLine(BatteryCsv.SESSION_HEADER)
                    db.sessionDao().sessionsOverlapping(from, toBound).forEach { s ->
                        w.appendLine("${s.sessionId},${s.type},${s.startTime},${s.endTime ?: ""},${s.startLevel},${s.endLevel ?: ""},${s.deltaUah ?: ""},${s.avgCurrentUa ?: ""},${s.estCapacityMah ?: ""}")
                    }
                } ?: return@withContext false
                true
            }.getOrElse { failure ->
                if (failure is CancellationException) throw failure
                false
            }
        }

    suspend fun importJson(src: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val input = context.contentResolver.openInputStream(src) ?: return@withContext false
            val payload = input.use { json.decodeFromString(BatteryExport.serializer(), it.readBytes().toString(Charsets.UTF_8)) }

            val bd = db.batteryDao()
            val sd = db.sessionDao()

            db.withTransaction {
                payload.samples.chunked(1_000).forEach { batch ->
                    bd.insertSamples(batch.map { it.copy(id = 0) })
                }
                sd.upsertAll(payload.sessions)
            }
            true
        }.getOrElse { failure ->
            if (failure is CancellationException) throw failure
            false
        }
    }

    suspend fun importCsv(src: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val input = context.contentResolver.openInputStream(src) ?: return@withContext false
            input.bufferedReader(Charsets.UTF_8).use { reader ->
                val header = reader.readLine()?.removePrefix("\uFEFF")?.trim()
                    ?: return@withContext false
                require(header == BatteryCsv.SAMPLE_HEADER || header == BatteryCsv.SESSION_HEADER) {
                    "Unrecognised CSV header"
                }
                // A bad row or cancellation rolls back all preceding batches as well.
                db.withTransaction {
                    reader.lineSequence().filter(String::isNotBlank).chunked(1_000).forEach { lines ->
                        if (header == BatteryCsv.SAMPLE_HEADER) {
                            db.batteryDao().insertSamples(lines.map(BatteryCsv::parseSample))
                        } else {
                            db.sessionDao().upsertAll(lines.map(BatteryCsv::parseSession))
                        }
                    }
                }
            }
            true
        }.getOrElse { failure ->
            if (failure is CancellationException) throw failure
            false
        }
    }
}
