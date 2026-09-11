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

            // The date range applies here too. It used to be ignored for sessions, so a
            // "last 7 days" export quietly carried every session ever recorded.
            val sessions = if (includeSessions) {
                db.sessionDao().sessionsOverlapping(from, toBound)
