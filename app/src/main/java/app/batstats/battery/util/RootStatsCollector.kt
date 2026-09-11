package app.batstats.battery.util

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Collects root-only battery statistics.
 * These require actual root access, not just Shizuku/ADB.
 */
object RootStatsCollector {

    private const val TAG = "RootStatsCollector"
    private const val ROOT_PROBE_TIMEOUT_MS = 4_000L
    private const val CMD_TIMEOUT_MS = 20_000L

    /** A negative probe is re-tried after this long, a positive one is kept for the session. */
    private const val NEGATIVE_CACHE_MS = 60_000L

    private val rootProbeLock = Mutex()

    @Volatile
    private var cachedRoot: Boolean? = null

    @Volatile
    private var cachedRootAt = 0L

    data class KernelBatteryInfo(
        val technology: String?,
        val cycleCount: Int?,
        val chargeFullDesign: Long?, // μAh
        val chargeFull: Long?, // μAh (actual current capacity)
        val chargeNow: Long?, // μAh
        val currentNow: Long?, // μA
        val voltageNow: Int?, // μV
        val tempNow: Int?, // 0.1°C
        val health: String?,
        val status: String?,
        val capacityLevel: String?,
        val timeToEmptyNow: Long?, // secs
        val timeToFullNow: Long?, // secs
        val batteryAge: Double? // percentage of design capacity
    )

    data class KernelWakelockInfo(
        val name: String,
        val count: Int,
        val expireCount: Int,
        val wakeCount: Int,
        val activeCount: Int,
        val totalTime: Long, // nanosecs
        val sleepTime: Long, // nanosecs
        val maxTime: Long, // nanosecs
        val lastChange: Long // nanosecs
    )

    data class CpuInfo(
        val cluster: Int,
        val currentFreq: Long, // kHz
        val minFreq: Long,
        val maxFreq: Long,
        val governor: String,
        val timeInState: Map<Long, Long> // freq -> time in jiffies
    )

    data class ThermalZone(
        val name: String,
        val type: String,
        val tempMilliC: Int,
        val tripPoints: List<TripPoint>
    )

    data class TripPoint(
        val type: String,
        val tempMilliC: Int
    )

    /**
     * Probes for root. The result is cached: this used to spawn a `su` process on every
     * single shell command (four times per stats refresh) and could block indefinitely
     * while a root manager waited for the user to answer its prompt.
     */
    suspend fun isRootAvailable(): Boolean {
        cachedRoot?.let { cached ->
            if (cached || SystemClock.elapsedRealtime() - cachedRootAt < NEGATIVE_CACHE_MS) return cached
        }
        return rootProbeLock.withLock {
            cachedRoot?.let { cached ->
                if (cached || SystemClock.elapsedRealtime() - cachedRootAt < NEGATIVE_CACHE_MS) {
                    return@withLock cached
                }
            }
            val available = withContext(Dispatchers.IO) {
                exec("id", ROOT_PROBE_TIMEOUT_MS)?.contains("uid=0") == true
            }
            cachedRoot = available
            cachedRootAt = SystemClock.elapsedRealtime()
            available
        }
    }

    /** Forces the next [isRootAvailable] call to probe again. */
    fun invalidateRootCache() {
        cachedRoot = null
        cachedRootAt = 0L
    }

    suspend fun resetBatteryStats(): Boolean = withContext(Dispatchers.IO) {
        val result = exec("dumpsys batterystats --reset", CMD_TIMEOUT_MS) ?: return@withContext false
        result.contains("Battery stats reset") || result.isBlank()
    }

    suspend fun getKernelBatteryInfo(): KernelBatteryInfo? = withContext(Dispatchers.IO) {
        val path = "/sys/class/power_supply/battery/"
        val files = readRootFiles(listOf("${path}*"))
        if (files.isEmpty()) return@withContext null
        fun read(name: String) = files[path + name]
        val design = read("charge_full_design")?.toLongOrNull()?.takeIf { it > 0 }
        val full = read("charge_full")?.toLongOrNull()?.takeIf { it > 0 }
        KernelBatteryInfo(
            technology = read("technology"), cycleCount = read("cycle_count")?.toIntOrNull(),
            chargeFullDesign = design, chargeFull = full,
            chargeNow = read("charge_now")?.toLongOrNull(),
            currentNow = read("current_now")?.toLongOrNull(),
            voltageNow = read("voltage_now")?.toIntOrNull(), tempNow = read("temp")?.toIntOrNull(),
            health = read("health"), status = read("status"), capacityLevel = read("capacity_level"),
            timeToEmptyNow = read("time_to_empty_now")?.toLongOrNull(),
            timeToFullNow = read("time_to_full_now")?.toLongOrNull(),
            batteryAge = if (design != null && full != null) full.toDouble() / design * 100 else null
        )
    }

    suspend fun getKernelWakelocks(): List<KernelWakelockInfo> = withContext(Dispatchers.IO) {
        val files = readRootFiles(listOf("/sys/kernel/wakelock_stats", "/proc/wakelocks"))
        val content = files["/sys/kernel/wakelock_stats"] ?: files["/proc/wakelocks"]
            ?: return@withContext emptyList()
        content.lineSequence().drop(1).mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 6) return@mapNotNull null
            KernelWakelockInfo(
                name = parts[0].trim('"'), count = parts[1].toIntOrNull() ?: 0,
                expireCount = parts[2].toIntOrNull() ?: 0, wakeCount = parts[3].toIntOrNull() ?: 0,
                activeCount = parts[4].toIntOrNull() ?: 0, totalTime = parts[5].toLongOrNull() ?: 0,
                sleepTime = parts.getOrNull(6)?.toLongOrNull() ?: 0,
                maxTime = parts.getOrNull(7)?.toLongOrNull() ?: 0,
                lastChange = parts.getOrNull(8)?.toLongOrNull() ?: 0
            )
        }.sortedByDescending { it.totalTime }.toList()
    }

    suspend fun getCpuInfo(): List<CpuInfo> = withContext(Dispatchers.IO) {
        val root = "/sys/devices/system/cpu/cpu[0-9]*/cpufreq/"
        val files = readRootFiles(listOf("${root}affected_cpus", "${root}scaling_*", "${root}stats/time_in_state"))
        val paths = files.keys.map { it.substringBefore("/cpufreq/") + "/cpufreq/" }.distinct()
        paths.mapNotNull { path ->
            val cpu = path.substringBefore("/cpufreq/").substringAfterLast("cpu").toIntOrNull()
                ?: return@mapNotNull null
            fun read(name: String) = files[path + name]
            val cluster = read("affected_cpus")?.split(Regex("\\s+"))?.firstOrNull()?.toIntOrNull() ?: cpu
            val times = read("stats/time_in_state")?.lineSequence()?.mapNotNull {
                val values = it.trim().split(Regex("\\s+"))
                val freq = values.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
                val time = values.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
                freq to time
            }?.toMap().orEmpty()
            CpuInfo(cluster, read("scaling_cur_freq")?.toLongOrNull() ?: 0,
                read("scaling_min_freq")?.toLongOrNull() ?: 0, read("scaling_max_freq")?.toLongOrNull() ?: 0,
                read("scaling_governor") ?: "unknown", times)
        }.distinctBy { it.cluster }.sortedBy { it.cluster }
    }

    suspend fun getThermalZones(): List<ThermalZone> = withContext(Dispatchers.IO) {
        val root = "/sys/class/thermal/thermal_zone*/"
        val files = readRootFiles(listOf("${root}type", "${root}temp", "${root}trip_point_*"))
        val paths = files.keys.map { it.substringBeforeLast('/') + "/" }.distinct()
        paths.mapNotNull { path ->
            val temp = files[path + "temp"]?.toIntOrNull() ?: return@mapNotNull null
            val trips = files.keys.filter { it.startsWith(path + "trip_point_") && it.endsWith("_type") }
                .sorted().mapNotNull { key ->
                    val value = files[key.removeSuffix("_type") + "_temp"]?.toIntOrNull()
                        ?: return@mapNotNull null
                    TripPoint(files.getValue(key), value)
                }
            ThermalZone(path.trimEnd('/').substringAfterLast('/'), files[path + "type"] ?: "unknown", temp, trips)
        }.sortedBy { it.name }
    }

    /** Paths are fixed internal patterns. Read them in one privileged process per group;
     * java.io.File would still run under the application's UID after a successful su probe. */
    private fun readRootFiles(patterns: List<String>): Map<String, String> {
        val command = "for f in ${patterns.joinToString(" ")}; do " +
            "if [ -f \"${'$'}f\" ] && [ -r \"${'$'}f\" ]; then " +
            "printf '__BATSTATS_FILE__%s\\n' \"${'$'}f\"; cat \"${'$'}f\" 2>/dev/null; printf '\\n'; fi; done; exit 0"
        return parseRootFiles(exec(command, CMD_TIMEOUT_MS).orEmpty())
    }

    suspend fun runAsRoot(command: String): String? = withContext(Dispatchers.IO) {
        exec(command, CMD_TIMEOUT_MS)
    }

    /**
     * Runs [command] through `su` with a hard wall-clock timeout, merging stderr into the
     * output so permission failures surface instead of silently returning an empty string.
     */
    private fun exec(command: String, timeoutMs: Long): String? {
        var process: Process? = null
        var watchdog: Thread? = null
        val timedOut = AtomicBoolean(false)
        return try {
            val p = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            process = p
            runCatching { p.outputStream.close() }

            // A root manager waiting on its "grant access?" dialog keeps the process alive
            // without producing any output, so the read below would block forever.
            watchdog = Thread {
                try {
                    if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                        timedOut.set(true)
                        Log.w(TAG, "su timed out after $timeoutMs ms: $command")
                        p.destroyForcibly()
                    }
                } catch (_: InterruptedException) {
                    // Finished in time.
                }
            }.apply {
                isDaemon = true
                start()
            }

            val out = p.inputStream.use { readShellOutput(it, 12 * 1024 * 1024) }
            val exitCode = p.waitFor()
            if (timedOut.get() || exitCode != 0) null else out
        } catch (e: Exception) {
            Log.d(TAG, "su failed for '$command': ${e.message}")
            null
        } finally {
            watchdog?.interrupt()
            runCatching { process?.destroy() }
        }
    }
}

/** Delimited sysfs reads can contain multiple lines (CPU time-in-state, wakelock tables). */
internal fun parseRootFiles(output: String): Map<String, String> {
    val result = linkedMapOf<String, String>()
    var path: String? = null
    val value = StringBuilder()
    fun flush() {
        path?.let { key -> value.toString().trim().takeIf(String::isNotEmpty)?.let { result[key] = it } }
        value.setLength(0)
    }
    output.lineSequence().forEach { line ->
        if (line.startsWith("__BATSTATS_FILE__/")) {
            flush()
            path = line.removePrefix("__BATSTATS_FILE__")
        } else if (path != null) value.appendLine(line)
    }
    flush()
    return result
}
