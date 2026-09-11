package app.batstats.battery.shizuku

import android.util.Log
import app.batstats.battery.data.db.AppEnergyDao
import app.batstats.battery.util.CheckinSource
import app.batstats.battery.util.PackageNameResolver
import app.batstats.battery.util.ShellRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
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
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val running = AtomicBoolean(false)
    private var job: Job? = null

    fun isRunning(): Boolean = running.get()

    fun start(pollSec: Long = 300L) {
        if (!running.compareAndSet(false, true)) return
        job = scope.launch {
            var previous: CheckinParser.Snapshot? = null
            val pollIntervalMs = pollSec.coerceIn(15L, 86_400L) * 1000L
            var retryDelayMs = 15_000L
            while (isActive) {
                try {
                    // Shared with the Detailed Stats screen: whichever asks first pays for
                    // the dump, the other reads the same copy.
                    val result = checkinSource.get(maxAgeMs = pollIntervalMs / 2)
                    if (result !is ShellRunner.Outcome.Success) {
                        Log.w(TAG, "No privileged access for batterystats --checkin")
                        // Shizuku may be off for hours. Avoid dumping/probing every five
                        // seconds for the whole outage, while retrying quickly at first.
                        delay(retryDelayMs)
                        retryDelayMs = (retryDelayMs * 2).coerceAtMost(pollIntervalMs)
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
                    retryDelayMs = 15_000L
                } catch (ce: CancellationException) {
                    throw ce
