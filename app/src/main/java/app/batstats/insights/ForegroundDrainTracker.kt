package app.batstats.insights

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageEventsQuery
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import app.batstats.battery.data.BatteryRepository
import app.batstats.battery.data.db.AppEnergyDao
import app.batstats.battery.data.db.AppEnergyIncrement
import app.batstats.battery.data.db.hourBucketStart
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
            val writeBuffer = HeuristicWriteBuffer()

            suspend fun flushPending() {
                val entries = writeBuffer.snapshot()
                if (entries.isEmpty()) return
                try {
                    appEnergyDao.incrementEntries(entries)
                    writeBuffer.clear()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Keep the buffer intact and retry on the next reading. Per-app
                    // attribution is supplemental and must not stop core monitoring.
                    android.util.Log.w("ForegroundDrain", "Could not flush app-drain estimates", e)
                }
            }

            try {
                batteryRepo.realtimeFlow.collect { rt ->
                    val now = rt.sample?.timestamp ?: System.currentTimeMillis()
                    val elapsed = SystemClock.elapsedRealtime()
                    // Cap the interval: if the flow stalls - doze, a killed process - we have no
                    // reason to believe one app held the foreground for the whole gap, and
                    // uncapped this would attribute hours of drain to it on a single reading.
                    val dtHours = ((now - lastTs).coerceIn(0L, MAX_ATTRIBUTED_GAP_MS)) / 3_600_000.0

                    // Only discharge is attributable. The current is positive on the charger, so
                    // taking its magnitude used to credit a fast charge to whatever app happened
                    // to be open - 1500 mA of charging read as 1420 mA of "excess app drain".
                    val screenOn = rt.sample?.screenOn == true
                    val discharging = rt.plugged == 0 && rt.currentMa < 0
                    // UsageStats is a binder/database query. There is no attribution work to do
                    // while charging or screen-off, so avoid paying for that query on those samples.
                    val pkg = if (shouldQueryForegroundApp(screenOn, rt.plugged, rt.currentMa) && hasUsageAccess()) {
                        currentForegroundPackage(lastTs, lastPkg)
                    } else null
                    if (discharging && screenOn) {
                        val ma = abs(rt.currentMa.toDouble())
                        screenOnBaseline.observe(ma)

                        val baseline = screenOnBaseline.baselineMilliAmps()
                        if (pkg != null && dtHours > 0.0 && baseline != null) {
                            val deltaMah = max(0.0, ma - baseline) * dtHours
                            // Samples can arrive every five seconds. Preserve every estimate in
                            // memory, but commit them together once a minute instead of forcing a
                            // Room transaction for every current reading.
                            writeBuffer.add(pkg, now, elapsed, deltaMah)
                        }
                    }

                    if (writeBuffer.shouldFlush(elapsed)) flushPending()
                    lastPkg = pkg
                    lastTs = now
                }
            } finally {
                // A user-paused service should not lose the last partial minute.
                withContext(NonCancellable) { flushPending() }
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

internal fun shouldQueryForegroundApp(screenOn: Boolean, plugged: Int, currentMa: Int): Boolean =
    screenOn && plugged == 0 && currentMa < 0

internal const val HEURISTIC_FLUSH_INTERVAL_MS = 60_000L

/** Aggregates per-app estimates by their real hourly bucket before one Room transaction. */
internal class HeuristicWriteBuffer(
    private val flushIntervalMs: Long = HEURISTIC_FLUSH_INTERVAL_MS
) {
    private data class Key(val bucketStart: Long, val packageName: String)
    private data class Value(var energyMah: Double = 0.0, var samples: Int = 0)

    private val pending = linkedMapOf<Key, Value>()
    private var firstPendingElapsedMs: Long? = null

    fun add(packageName: String, atMillis: Long, elapsedMs: Long, energyMah: Double) {
        if (!energyMah.isFinite() || energyMah < 0.0) return
        val key = Key(hourBucketStart(atMillis), packageName)
        val value = pending.getOrPut(key) { Value() }
        value.energyMah += energyMah
        value.samples++
        if (firstPendingElapsedMs == null) firstPendingElapsedMs = elapsedMs
    }

    fun shouldFlush(elapsedMs: Long): Boolean = firstPendingElapsedMs?.let {
        elapsedMs < it || elapsedMs - it >= flushIntervalMs
    } == true

    fun snapshot(): List<AppEnergyIncrement> = pending.map { (key, value) ->
        AppEnergyIncrement(
            bucketStart = key.bucketStart,
            packageName = key.packageName,
            mode = "HEURISTIC",
            energyMah = value.energyMah,
            samples = value.samples
        )
    }

    fun clear() {
        pending.clear()
        firstPendingElapsedMs = null
    }
}
