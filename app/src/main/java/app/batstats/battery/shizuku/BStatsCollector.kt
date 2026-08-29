package app.batstats.battery.shizuku

import android.util.Log
import app.batstats.battery.data.db.AppEnergyDao
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
            while (isActive) {
                try {
                    val result = shellRunner.run("dumpsys batterystats --checkin")
                    if (result == null) {
                        Log.w(TAG, "No privileged access for batterystats --checkin")
                        delay(5_000)
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
                } catch (e: Exception) {
                    Log.e(TAG, "Error in polling loop", e)
                }
                delay(pollSec * 1000L)
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
