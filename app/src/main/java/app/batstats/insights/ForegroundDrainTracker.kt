package app.batstats.insights

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageEventsQuery
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process
import app.batstats.battery.data.BatteryRepository
import app.batstats.battery.data.db.AppEnergyDao
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max

/**
 * Tracks foreground app and attributes "excess" current to it (heuristic).
 * Requires Usage Access (PACKAGE_USAGE_STATS).
 */
class ForegroundDrainTracker(
    private val context: Context,
    private val batteryRepo: BatteryRepository,
    private val appEnergyDao: AppEnergyDao,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    private val running = AtomicBoolean(false)
    private var job: Job? = null

    /** Measured separately per screen state: the two idle draws are nothing alike. */
    private val screenOnBaseline = IdleBaseline()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        job = scope.launch {
            var lastTs = System.currentTimeMillis()
            var lastPkg: String? = null

            batteryRepo.realtimeFlow.collect { rt ->
                val now = rt.sample?.timestamp ?: System.currentTimeMillis()
                // Cap the interval: if the flow stalls - doze, a killed process - we have no
                // reason to believe one app held the foreground for the whole gap, and
                // uncapped this would attribute hours of drain to it on a single reading.
                val dtHours = ((now - lastTs).coerceIn(0L, MAX_ATTRIBUTED_GAP_MS)) / 3_600_000.0

                val screenOn = rt.sample?.screenOn == true
                val pkg = if (screenOn && hasUsageAccess()) {
                    currentForegroundPackage(lastTs, lastPkg)
                } else null

                // Only discharge is attributable. The current is positive on the charger, so
                // taking its magnitude used to credit a fast charge to whatever app happened
                // to be open - 1500 mA of charging read as 1420 mA of "excess app drain".
                val discharging = rt.plugged == 0 && rt.currentMa < 0
                if (discharging && screenOn) {
                    val ma = abs(rt.currentMa.toDouble())
                    val baselines = screenOnBaseline
                    baselines.observe(ma)

                    val baseline = baselines.baselineMilliAmps()
                    if (pkg != null && dtHours > 0.0 && baseline != null) {
                        val deltaMah = max(0.0, ma - baseline) * dtHours
                        appEnergyDao.incrementHour(pkg, now, deltaMah, addSamples = 1)
                    }
                }

                lastPkg = pkg
                lastTs = now
            }
        }
    }

    fun stop() {
        running.set(false)
        job?.cancel()
        job = null
    }

    fun isRunning(): Boolean = running.get()

    fun hasUsageAccess(): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = try {
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        } catch (_: Throwable) {
            return false
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun openUsageAccessSettings() {
        context.startActivity(
            android.content.Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun currentForegroundPackage(since: Long, previous: String?): String? {
        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return null
        val end = System.currentTimeMillis()
        val begin = since.coerceIn(end - MAX_ATTRIBUTED_GAP_MS, end)

        // API 35+: narrow the query to relevant event types
        val events = try { if (Build.VERSION.SDK_INT >= 35) {
            val q = UsageEventsQuery.Builder(begin, end)
                .setEventTypes(
                    UsageEvents.Event.ACTIVITY_RESUMED,
                    UsageEvents.Event.ACTIVITY_PAUSED,
                    UsageEvents.Event.ACTIVITY_STOPPED
                )
                .build()
            usm.queryEvents(q)
        } else {
            usm.queryEvents(begin, end)
        } } catch (_: SecurityException) { null } ?: return null

        var lastPkg = previous
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                lastPkg = e.packageName
            } else if (e.packageName == lastPkg &&
                (e.eventType == UsageEvents.Event.ACTIVITY_PAUSED || e.eventType == UsageEvents.Event.ACTIVITY_STOPPED)) {
                lastPkg = null
            }
        }
        return lastPkg
    }

    companion object {
        /** Beyond this, "the foreground app was responsible" stops being a fair assumption. */
        private const val MAX_ATTRIBUTED_GAP_MS = 5 * 60_000L
    }
}
