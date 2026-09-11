package app.batstats.battery.shizuku

import android.util.Log
import app.batstats.battery.data.db.AppEnergyDao
import app.batstats.battery.util.CheckinSource
import app.batstats.battery.util.PackageNameResolver
import app.batstats.battery.util.ShellRunner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * Records how much energy each app has used, in hourly buckets, so drain can be attributed
 * over a day or a week rather than only since the last charge.
 *
 * Works on differences: the checkin dump reports totals accumulated since the last full
 * charge, so each poll stores what changed since the previous one. A charge resets those
 * counters, which shows up as a negative difference and is discarded rather than recorded as
 * a drop.
 */
class BstatsCollector(
    private val dao: AppEnergyDao,
    private val checkinSource: CheckinSource,
    private val packageNames: PackageNameResolver,
    // backward compat
    private val shizuku: ShizukuBridge? = null
) {
    companion object {
        private const val TAG = "BstatsCollector"
        /** Cumulative batterystats deltas do not require a multi-hundred-KB dump every five minutes. */
        internal const val DEFAULT_POLL_SECONDS = 15 * 60L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val running = AtomicBoolean(false)
    private var job: Job? = null

    fun isRunning(): Boolean = running.get()

    fun start(pollSec: Long = DEFAULT_POLL_SECONDS) {
        if (!running.compareAndSet(false, true)) return
        job = scope.launch {
            var previous: CheckinParser.Snapshot? = null
            val intervalMs = pollSec.coerceIn(15L, 86_400L) * 1000L
            var retryMs = 15_000L
            while (isActive) {
                try {
                    // Shared with the Detailed Stats screen: whichever asks first pays for
                    // the dump, the other reads the same copy.
                    val result = checkinSource.get(maxAgeMs = intervalMs / 2)
                    if (result !is ShellRunner.Outcome.Success) {
                        Log.w(TAG, "No privileged access for batterystats --checkin")
                        delay(retryMs)
                        retryMs = (retryMs * 2).coerceAtMost(intervalMs)
                        continue
                    }

                    val snap = CheckinParser.parse(result.output.lineSequence())
                    val now = System.currentTimeMillis()

                    val deltas = mutableMapOf<String, Double>()
                    energyDeltas(previous, snap).forEach { (uid, delta) ->
                        val pkg = nameFor(uid, snap.uidToPackage)
                        deltas[pkg] = (deltas[pkg] ?: 0.0) + delta
                    }
                    dao.incrementBatch(deltas, now, result.mode.name)
                    previous = snap
                    retryMs = 15_000L
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Error in polling loop", e)
                }
                delay(intervalMs)
            }
        }
    }

    /**
     * The dump carries its own uid -> package map, but it is incomplete whenever the caller
     * could not see other packages - through ADB-granted DUMP, dumpsys runs as BatStats
     * itself. PackageManager fills the gaps so the stored rows are named, not "uid:10234".
     */
    private fun nameFor(uid: Int, fromDump: Map<Int, String>): String =
        fromDump[uid] ?: packageNames.nameFor(uid) ?: "uid:$uid"

    fun stop() {
        running.set(false)
        job?.cancel()
        job = null
    }
}
