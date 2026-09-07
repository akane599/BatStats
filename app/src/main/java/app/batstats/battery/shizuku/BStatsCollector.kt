package app.batstats.battery.shizuku

import android.util.Log
import app.batstats.battery.data.db.AppEnergyDao
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

class BstatsCollector(
    private val dao: AppEnergyDao,
    private val shellRunner: ShellRunner,
    // backward compat
    private val shizuku: ShizukuBridge? = null
) {
    companion object {
        private const val TAG = "BstatsCollector"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val running = AtomicBoolean(false)
    private var job: Job? = null
    private var last: Map<String, Double> = emptyMap()

    fun isRunning(): Boolean = running.get()

    fun start(pollSec: Long = 300L) {
        if (!running.compareAndSet(false, true)) return
        job = scope.launch {
            val pollIntervalMs = pollSec.coerceIn(15L, 86_400L) * 1000L
            var retryDelayMs = 15_000L
            while (isActive) {
                try {
                    val result = shellRunner.run("dumpsys batterystats --checkin")
                    if (result == null) {
                        Log.w(TAG, "No privileged access for batterystats --checkin")
                        // Shizuku may be off for hours. Avoid dumping/probing every five
                        // seconds for the whole outage, while retrying quickly at first.
                        delay(retryDelayMs)
                        retryDelayMs = (retryDelayMs * 2).coerceAtMost(pollIntervalMs)
                        continue
                    }

                    val snap = CheckinParser.parse(result.output.lineSequence())
                    val now = System.currentTimeMillis()

                    if (last.isNotEmpty()) {
                        for ((pkg, cur) in snap.perPackageMah) {
                            val prev = last[pkg] ?: 0.0
                            val delta = max(0.0, cur - prev)
                            if (delta > 0.0001) {
                                dao.incrementHour(
                                    packageName = pkg,
                                    atMillis = now,
                                    deltaMah = delta,
                                    addSamples = 1,
                                    mode = result.mode.name
                                )
                            }
                        }
                    }
                    last = snap.perPackageMah
                    retryDelayMs = 15_000L
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    Log.e(TAG, "Error in polling loop", e)
                }
                delay(pollIntervalMs)
            }
        }
    }

    fun stop() {
        running.set(false)
        job?.cancel()
        job = null
        last = emptyMap()
    }
}
