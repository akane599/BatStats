package app.batstats.battery.util

import android.content.Context
import android.util.Log
import app.batstats.battery.shizuku.ShizukuBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

class ShellRunner(
    private val context: Context,
    private val shizuku: ShizukuBridge
) {
    companion object {
        private const val TAG = "ShellRunner"
        private const val CMD_TIMEOUT_SEC = 15L
    }

    enum class Mode { ROOT, SHIZUKU, ADB, NONE }

    data class ShellResult(
        val output: String,
        val mode: Mode
    )

    suspend fun run(cmd: String): ShellResult? = withContext(Dispatchers.IO) {
        try {
            if (RootStatsCollector.isRootAvailable()) {
                val out = withTimeoutOrNull(CMD_TIMEOUT_SEC * 1000) {
                    RootStatsCollector.runAsRoot(cmd)
                }
                if (!out.isNullOrBlank() && !isErrorOutput(out)) {
                    Log.d(TAG, "run via ROOT: $cmd (${out.length} chars)")
                    return@withContext ShellResult(out, Mode.ROOT)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Root run failed", e)
        }

        try {
            if (shizuku.ping() && shizuku.hasPermission()) {
                when (val r = shizuku.run(cmd)) {
                    is ShizukuBridge.RunResult.Success -> {
                        val out = r.output
                        if (out.isNotBlank() && !isErrorOutput(out)) {
                            Log.d(TAG, "run via SHIZUKU: $cmd (${out.length} chars)")
                            return@withContext ShellResult(out, Mode.SHIZUKU)
                        }
                    }
                    is ShizukuBridge.RunResult.Error -> {
                        Log.w(TAG, "Shizuku run error: ${r.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Shizuku run failed", e)
        }

        // direct ADB-granted (DUMP / BATTERY_STATS)
        if (PrivilegeChecker.hasDump(context) || PrivilegeChecker.hasBatteryStats(context)) {
            try {
                val out = withTimeoutOrNull(CMD_TIMEOUT_SEC * 1000) {
                    runDirect(cmd)
                }
                if (!out.isNullOrBlank() && !isErrorOutput(out)) {
                    Log.d(TAG, "run via ADB: $cmd (${out.length} chars)")
                    return@withContext ShellResult(out, Mode.ADB)
                } else {
                    Log.w(TAG, "Direct run empty or error: ${out?.take(200)}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Direct run failed", e)
            }
        }

        Log.w(TAG, "All runners failed for: $cmd")
        null
    }

    suspend fun runDirectOnly(cmd: String): String? = withContext(Dispatchers.IO) {
        if (!PrivilegeChecker.hasDump(context) && !PrivilegeChecker.hasBatteryStats(context)) return@withContext null
        try {
            val out = withTimeoutOrNull(CMD_TIMEOUT_SEC * 1000) { runDirect(cmd) }
            if (!out.isNullOrBlank() && !isErrorOutput(out)) out else null
        } catch (_: Exception) { null }
    }

    private fun runDirect(cmd: String): String? {
        return try {
            // Use ProcessBuilder with shell wrapping to support pipes etc.
            val process = ProcessBuilder("sh", "-c", cmd)
                .redirectErrorStream(true)
                .start()
            val finished = process.waitFor(CMD_TIMEOUT_SEC, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return null
            }
            val out = process.inputStream.bufferedReader().use { it.readText() }
            // Check exit code optionally; dumpsys returns 0 even on permission denial but prints "Permission Denial"
            if (out.contains("Permission Denial") || out.contains("Permission denial")) return null
            out
        } catch (e: Exception) {
            Log.e(TAG, "runDirect exception", e)
            null
        }
    }

    private fun isErrorOutput(out: String): Boolean {
        return out.startsWith("ERROR") || out.startsWith("Permission Denial") || out.startsWith("Permission denial")
    }

    suspend fun detectMode(): Mode = withContext(Dispatchers.IO) {
        if (RootStatsCollector.isRootAvailable()) return@withContext Mode.ROOT
        if (shizuku.ping() && shizuku.hasPermission()) return@withContext Mode.SHIZUKU
        if (PrivilegeChecker.hasDump(context) || PrivilegeChecker.hasBatteryStats(context)) {
            // Verify direct exec actually works (some OEMs block even with grant)
            val probe = runDirectOnly("dumpsys batterystats --checkin")
            if (probe != null) return@withContext Mode.ADB
        }
        Mode.NONE
    }

    suspend fun hasAnyPrivilegedAccess(): Boolean = detectMode() != Mode.NONE
}
