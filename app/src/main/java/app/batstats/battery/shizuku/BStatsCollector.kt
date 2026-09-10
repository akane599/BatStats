package app.batstats.battery.shizuku

import android.util.Log
import app.batstats.battery.data.db.AppEnergyDao
import app.batstats.battery.util.CheckinSource
import app.batstats.battery.util.PackageNameResolver
import app.batstats.battery.util.ShellRunner
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
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val running = AtomicBoolean(false)
    private var job: Job? = null
    private var last: Map<Int, Double> = emptyMap()

    fun isRunning(): Boolean = running.get()

    fun start(pollSec: Long = 300L) {
        if (!running.compareAndSet(false, true)) return
        job = scope.launch {
            while (isActive) {
                try {
                    // Shared with the Detailed Stats screen: whichever asks first pays for
                    // the dump, the other reads the same copy.
                    val result = checkinSource.get(maxAgeMs = pollSec * 1000L / 2)
                    if (result !is ShellRunner.Outcome.Success) {
                        Log.w(TAG, "No privileged access for batterystats --checkin")
                        delay(5_000)
                        continue
                    }

                    val snap = CheckinParser.parse(result.output.lineSequence())
                    val now = System.currentTimeMillis()

                    if (last.isNotEmpty()) {
                        for ((uid, cur) in snap.perUidMah) {
                            val prev = last[uid] ?: 0.0
                            val delta = max(0.0, cur - prev)
                            if (delta > 0.0001) {
                                dao.incrementHour(
                                    packageName = nameFor(uid, snap.uidToPackage),
                                    atMillis = now,
                                    deltaMah = delta,
                                    addSamples = 1,
                                    mode = result.mode.name
                                )
                            }
                        }
                    }
                    last = snap.perUidMah
                } catch (e: Exception) {
                    Log.e(TAG, "Error in polling loop", e)
                }
                delay(pollSec * 1000L)
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
        last = emptyMap()
    }
}
