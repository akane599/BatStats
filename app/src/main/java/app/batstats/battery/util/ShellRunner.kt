package app.batstats.battery.util

import android.content.Context
import android.os.SystemClock
import android.util.Log
import app.batstats.battery.shizuku.ShizukuBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs privileged shell commands through whichever backend is available:
 * root, Shizuku, or ADB-granted DUMP/BATTERY_STATS.
 */
class ShellRunner(
    private val context: Context,
    private val shizuku: ShizukuBridge
) {
    companion object {
        private const val TAG = "ShellRunner"
        private const val CMD_TIMEOUT_SEC = 25L

        /**
         * How long a detected mode stays valid. A single stats refresh issues several
         * commands; without this, each one re-probed root and Shizuku from scratch.
         */
        private const val MODE_CACHE_MS = 10_000L
    }

    enum class Mode { ROOT, SHIZUKU, ADB, NONE }

    data class ShellResult(
        val output: String,
        val mode: Mode
    )

    sealed class Outcome {
        data class Success(val output: String, val mode: Mode) : Outcome()

        /** [mode] is the backend that was tried, or [Mode.NONE] when there was none. */
        data class Failure(val mode: Mode, val message: String) : Outcome()
    }

    private val modeLock = Mutex()

    @Volatile
    private var cachedMode: Mode? = null

    @Volatile
    private var cachedModeAt = 0L

    suspend fun run(cmd: String): ShellResult? =
        (exec(cmd) as? Outcome.Success)?.let { ShellResult(it.output, it.mode) }

    /**
     * Runs [cmd] and reports *why* it failed, so callers can tell "no privileged access"
     * apart from "the command itself failed" instead of blaming a missing grant.
     *
     * Set [allowEmpty] for commands that legitimately print nothing on success
     * (`batterystats --reset`, `settings put`, ...).
     */
    suspend fun exec(cmd: String, allowEmpty: Boolean = false): Outcome = withContext(Dispatchers.IO) {
        var lastFailure: Outcome.Failure? = null

        fun usable(out: String?): Boolean =
            out != null && (allowEmpty || out.isNotBlank()) && !isErrorOutput(out)

        if (RootStatsCollector.isRootAvailable()) {
            val out = RootStatsCollector.runAsRoot(cmd)
            if (usable(out)) {
                Log.d(TAG, "run via ROOT: $cmd (${out!!.length} chars)")
                return@withContext Outcome.Success(out, Mode.ROOT)
            }
            Log.w(TAG, "Root run failed for: $cmd")
            lastFailure = Outcome.Failure(Mode.ROOT, "Root command produced no usable output")
        }

        when (val r = shizuku.run(cmd, TimeUnit.SECONDS.toMillis(CMD_TIMEOUT_SEC))) {
            is ShizukuBridge.RunResult.Success -> {
                val out = r.output
                if (usable(out)) {
                    Log.d(TAG, "run via SHIZUKU: $cmd (${out.length} chars)")
                    return@withContext Outcome.Success(out, Mode.SHIZUKU)
                }
                Log.w(TAG, "Shizuku returned empty/error output for: $cmd")
                lastFailure = Outcome.Failure(
                    Mode.SHIZUKU,
                    if (out.isBlank()) "Shizuku returned no output"
                    else shellOutputError(out) ?: out.take(200).trim()
                )
            }

            is ShizukuBridge.RunResult.Error -> {
                Log.w(TAG, "Shizuku run error (${r.reason}): ${r.message}")
                // "Shizuku is not installed/running" is not a failure worth reporting when
                // another backend might still work, so only remember the real ones.
                if (r.reason != ShizukuBridge.Failure.NOT_RUNNING) {
                    lastFailure = Outcome.Failure(Mode.SHIZUKU, r.message)
                }
            }
        }

        if (PrivilegeChecker.hasAdvancedViaAdb(context)) {
            val direct = runDirect(cmd)
            if (direct is Outcome.Success && usable(direct.output)) {
                Log.d(TAG, "run via ADB: $cmd (${direct.output.length} chars)")
                return@withContext direct
            }
            lastFailure = direct as? Outcome.Failure
                ?: Outcome.Failure(Mode.ADB, "ADB command returned no usable output")
            Log.w(TAG, "Direct run failed: ${lastFailure.message}")
        }

        Log.w(TAG, "All runners failed for: $cmd")
        lastFailure ?: Outcome.Failure(Mode.NONE, "No privileged backend available")
    }

    suspend fun runDirectOnly(cmd: String): String? = withContext(Dispatchers.IO) {
        if (!PrivilegeChecker.hasAdvancedViaAdb(context)) return@withContext null
        (runDirect(cmd) as? Outcome.Success)?.output?.takeIf { it.isNotBlank() && !isErrorOutput(it) }
    }

    private fun runDirect(cmd: String): Outcome {
        var process: Process? = null
        var watchdog: Thread? = null
        val timedOut = AtomicBoolean(false)
        return try {
            // Shell wrapping so pipes and redirects keep working.
            val p = ProcessBuilder("sh", "-c", cmd)
                .redirectErrorStream(true)
                .start()
            process = p
            runCatching { p.outputStream.close() }

            watchdog = Thread {
                try {
                    if (!p.waitFor(CMD_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                        timedOut.set(true)
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
            val error = shellOutputError(out)
            when {
                timedOut.get() -> Outcome.Failure(Mode.ADB, "Command timed out after $CMD_TIMEOUT_SEC seconds")
                exitCode != 0 -> Outcome.Failure(Mode.ADB, error ?: "Command exited with status $exitCode")
                // dumpsys exits 0 even when it refuses, so check the text as well.
                error != null -> Outcome.Failure(Mode.ADB, error)
                else -> Outcome.Success(out, Mode.ADB)
            }
        } catch (e: Exception) {
            Log.e(TAG, "runDirect exception", e)
            Outcome.Failure(Mode.ADB, e.message ?: "Could not execute command")
        } finally {
            watchdog?.interrupt()
            runCatching { process?.destroy() }
        }
    }

    private fun isErrorOutput(out: String): Boolean =
        shellOutputError(out) != null

    /**
     * Detects the best available backend. Cached for [MODE_CACHE_MS] so a burst of commands
     * does not re-probe every backend, and so a momentary Shizuku hiccup does not make the
     * UI claim that no privileged access exists.
     */
    suspend fun detectMode(forceRefresh: Boolean = false): Mode {
        if (!forceRefresh) {
            cachedMode?.let {
                if (SystemClock.elapsedRealtime() - cachedModeAt < MODE_CACHE_MS) return it
            }
        }
        return modeLock.withLock {
            if (!forceRefresh) {
                cachedMode?.let {
                    if (SystemClock.elapsedRealtime() - cachedModeAt < MODE_CACHE_MS) {
                        return@withLock it
                    }
                }
            }
            val mode = probeMode()
            cachedMode = mode
            cachedModeAt = SystemClock.elapsedRealtime()
            mode
        }
    }

    private suspend fun probeMode(): Mode = withContext(Dispatchers.IO) {
        if (RootStatsCollector.isRootAvailable()) return@withContext Mode.ROOT
        if (shizuku.hasPermissionResilient()) return@withContext Mode.SHIZUKU
        if (PrivilegeChecker.hasAdvancedViaAdb(context)) {
            // Verify direct exec actually works (some OEMs block it even with the grant).
            // `dumpsys battery` needs the same DUMP permission but is cheap to run.
            if (runDirectOnly("dumpsys battery") != null) return@withContext Mode.ADB
        }
        Mode.NONE
    }

    /** Drops the cached backend, e.g. after the user grants a permission. */
    fun invalidateMode() {
        cachedMode = null
        cachedModeAt = 0L
        RootStatsCollector.invalidateRootCache()
    }

    suspend fun hasAnyPrivilegedAccess(): Boolean = detectMode() != Mode.NONE
}
