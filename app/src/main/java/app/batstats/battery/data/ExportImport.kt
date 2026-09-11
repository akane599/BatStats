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
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.InputStream

@Serializable
data class BatteryExport(val samples: List<BatterySample> = emptyList(), val sessions: List<ChargeSession> = emptyList())

class ExportImportManager(private val context: Context, private val db: BatteryDatabase) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    val sampleCount = db.batteryDao().sampleCount()

    suspend fun exportJson(dest: Uri, from: Long, to: Long, includeSamples: Boolean, includeSessions: Boolean): Boolean = attempt {
        require(includeSamples || includeSessions)
        val end = endBound(from, to)
        val sessions = if (includeSessions) db.sessionDao().sessionsOverlapping(from, end) else emptyList()
        val stream = context.contentResolver.openOutputStream(dest, "wt") ?: return@attempt false
        stream.bufferedWriter().use { writer ->
            writer.write("{\"samples\":[")
            var first = true
            if (includeSamples) forEachSamplePage(from, end) { batch ->
                batch.forEach { sample ->
                    if (!first) writer.write(",")
                    writer.write(json.encodeToString(BatterySample.serializer(), sample))
                    first = false
                }
            }
            writer.write("],\"sessions\":[")
            sessions.forEachIndexed { i, session ->
                if (i > 0) writer.write(",")
                writer.write(json.encodeToString(ChargeSession.serializer(), session))
            }
            writer.write("]}")
        }
        true
    }

    suspend fun exportCsvToFolder(tree: Uri, from: Long, to: Long, includeSamples: Boolean = true, includeSessions: Boolean = true): Boolean = attempt {
        require(includeSamples || includeSessions)
        val end = endBound(from, to)
        val dir = DocumentFile.fromTreeUri(context, tree) ?: return@attempt false
        val created = mutableListOf<DocumentFile>()
        var complete = false
        try {
            if (includeSamples) {
                val file = dir.createFile("text/csv", "battery_samples.csv") ?: return@attempt false
                created += file
                val stream = context.contentResolver.openOutputStream(file.uri, "wt") ?: return@attempt false
                stream.bufferedWriter().use { writer ->
                    writer.appendLine(BatteryCsv.SAMPLE_HEADER)
                    forEachSamplePage(from, end) { batch ->
                        batch.forEach { s -> writer.appendLine("${s.timestamp},${s.levelPercent},${s.status},${s.plugged},${s.currentNowUa ?: ""},${s.chargeCounterUah ?: ""},${s.voltageMv ?: ""},${s.temperatureDeciC ?: ""},${s.health ?: ""},${s.screenOn}") }
                    }
                }
            }
            if (includeSessions) {
                val file = dir.createFile("text/csv", "charge_sessions.csv") ?: return@attempt false
                created += file
                val stream = context.contentResolver.openOutputStream(file.uri, "wt") ?: return@attempt false
                stream.bufferedWriter().use { writer ->
                    writer.appendLine(BatteryCsv.SESSION_HEADER_V2)
                    db.sessionDao().sessionsOverlapping(from, end).forEach { s ->
                        validateSession(s)
                        writer.appendLine("${s.sessionId},${s.type},${s.startTime},${s.endTime ?: ""},${s.startLevel},${s.endLevel ?: ""},${s.deltaUah ?: ""},${s.avgCurrentUa ?: ""},${s.estCapacityMah ?: ""},${s.autoStarted}")
                    }
                }
            }
            complete = true
            true
        } finally {
            if (!complete) created.forEach { runCatching { it.delete() } }
        }
    }

    suspend fun importJson(src: Uri): Boolean = attempt {
        val stream = context.contentResolver.openInputStream(src) ?: return@attempt false
        // Parsing requires an object graph. Bound the input before allocating it; CSV is
        // streamed and supports larger archives.
        val text = stream.use { readImportText(it) }
        val payload = json.decodeFromString(BatteryExport.serializer(), text)
        db.withTransaction {
            payload.samples.chunked(900).forEach { importSamples(it) }
            importSessions(payload.sessions)
        }
        true
    }

    suspend fun importCsv(src: Uri): Boolean = attempt {
        val stream = context.contentResolver.openInputStream(src) ?: return@attempt false
        stream.bufferedReader(Charsets.UTF_8).use { reader ->
            val lines = boundedCsvLines(reader).iterator()
            val header = if (lines.hasNext()) lines.next().removePrefix("\uFEFF").trim() else return@attempt false
            require(header in setOf(BatteryCsv.SAMPLE_HEADER, BatteryCsv.SESSION_HEADER, BatteryCsv.SESSION_HEADER_V2))
            db.withTransaction {
                lines.asSequence().filter(String::isNotBlank).chunked(900).forEach { lines ->
                    if (header == BatteryCsv.SAMPLE_HEADER) importSamples(lines.map(BatteryCsv::parseSample))
                    else importSessions(lines.map(BatteryCsv::parseSession))
                }
            }
        }
        true
    }

    private suspend fun importSamples(samples: List<BatterySample>) {
        samples.forEach(::validateSample)
        val existing = db.batteryDao().existingTimestamps(samples.map { it.timestamp }).toHashSet()
        db.batteryDao().insertSamples(samples.filter { existing.add(it.timestamp) }.map { it.copy(id = 0) })
    }

    private suspend fun importSessions(sessions: List<ChargeSession>) {
        sessions.forEach(::validateSession)
        val open = sessions.filter { it.endTime == null }
        require(open.map { it.sessionId }.distinct().size <= 1)
        val active = db.sessionDao().active()
        require(active == null || open.all { it.sessionId == active.sessionId }) { "An active session already exists" }
        db.sessionDao().upsertAll(sessions)
    }

    private suspend fun forEachSamplePage(from: Long, to: Long, block: suspend (List<BatterySample>) -> Unit) {
        var after = 0L
        while (true) {
            val page = db.batteryDao().exportPage(from, to, after, 900)
            if (page.isEmpty()) return
            block(page)
            after = page.last().id
        }
    }

    private fun endBound(from: Long, to: Long): Long {
        val end = if (to == 0L) System.currentTimeMillis() else to
        require(from >= 0 && end >= from)
        return end
    }

    private suspend fun attempt(block: suspend () -> Boolean): Boolean = withContext(Dispatchers.IO) {
        try { block() } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
    }
}

/** A valid row is only a few hundred characters. A malformed single line must not exhaust
 * the heap before validation, even though the archive itself is streamed. */
internal fun boundedCsvLines(reader: java.io.Reader, maxChars: Int = 4096): Sequence<String> = sequence {
    val line = StringBuilder()
    while (true) {
        val ch = reader.read()
        if (ch < 0) { if (line.isNotEmpty()) yield(line.toString()); break }
        if (ch == '\n'.code) { yield(line.toString().trimEnd('\r')); line.setLength(0) }
        else { require(line.length < maxChars) { "CSV row too long" }; line.append(ch.toChar()) }
    }
}

internal fun readImportText(input: InputStream, maxBytes: Int = 32 * 1024 * 1024): String {
    val buffer = ByteArray(8192)
    val output = java.io.ByteArrayOutputStream()
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        require(output.size().toLong() + count <= maxBytes) { "JSON archive too large; use CSV" }
        output.write(buffer, 0, count)
    }
    return output.toString(Charsets.UTF_8.name()).removePrefix("\uFEFF")
}
