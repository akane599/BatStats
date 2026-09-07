package app.batstats.battery.data

import android.util.Log
import app.batstats.battery.data.db.BatteryDatabase
import app.batstats.settings.AppSettings
import app.batstats.settings.dataRetentionMs
import io.github.mlmgames.settings.core.SettingsRepository
import kotlinx.coroutines.flow.first

/**
 * Enforces the Data Retention setting.
 *
 * The setting, the "Auto-cleanup Old Data" toggle and both purge queries all existed
 * already - nothing ever called them. At the default 30 s interval the sample table grew by
 * roughly 2,880 rows a day and never shrank, while the switch in Settings did nothing at
 * all.
 */
class DataRetentionManager(
    private val db: BatteryDatabase,
    private val settingsRepository: SettingsRepository<AppSettings>,
    private val now: () -> Long = System::currentTimeMillis
) {

    /** Purges if enough time has passed since the last run. Returns true if it deleted. */
    suspend fun cleanupIfDue(): Boolean {
        val settings = settingsRepository.flow.first()
        if (!isDue(settings.autoCleanupEnabled, settings.lastDataCleanup, now())) return false
        return cleanup(settings.dataRetentionMs)
    }

    /** Purges everything older than [retentionMs]. A null retention means "Forever". */
    suspend fun cleanup(retentionMs: Long?): Boolean {
        val at = now()
        val cutoff = cutoff(retentionMs, at)
        if (cutoff != null) {
            try {
                db.batteryDao().purge(cutoff)
                db.appEnergyDao().purgeOlderThan(cutoff)
            } catch (t: Throwable) {
                // Housekeeping must never take the app down with it; try again next time.
                Log.w(TAG, "Retention purge failed", t)
                return false
            }
        }
        // "Forever" counts as having run too, so we stop re-checking every few hours.
        settingsRepository.update { it.copy(lastDataCleanup = at) }
        return cutoff != null
    }

    companion object {
        private const val TAG = "DataRetention"

        /** Twice a day is plenty for a boundary that moves in days. */
        const val CLEANUP_INTERVAL_MS = 12 * 60 * 60 * 1000L

        fun isDue(enabled: Boolean, lastCleanup: Long, now: Long): Boolean {
            if (!enabled) return false
            // A clock that moved backwards (time zone change, NTP correction) would
            // otherwise leave lastCleanup stranded in the future and never run again.
            if (lastCleanup > now) return true
            return now - lastCleanup >= CLEANUP_INTERVAL_MS
        }

        fun cutoff(retentionMs: Long?, now: Long): Long? = retentionMs?.let { now - it }
    }
}
