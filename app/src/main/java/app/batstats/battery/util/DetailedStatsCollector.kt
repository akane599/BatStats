package app.batstats.battery.util

import android.content.Context
import android.util.Log
import app.batstats.battery.data.db.BatteryDatabase
import app.batstats.battery.shizuku.ShizukuBridge
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Collects comprehensive battery stats using Root/Shizuku/ADB-granted DUMP.
 * Provides parsed data for the detailed stats screen.
 */
class DetailedStatsCollector(
    private val shellRunner: ShellRunner,
    private val db: BatteryDatabase,
    private val context: Context,
    // NOTE: only for direct checks (if later used)
    private val shizuku: ShizukuBridge? = null
) {
    companion object {
        private const val TAG = "DetailedStatsCollector"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val refreshing = AtomicBoolean(false)

    private val _snapshot = MutableStateFlow<BatteryStatsParser.FullSnapshot?>(null)
    val snapshot: StateFlow<BatteryStatsParser.FullSnapshot?> = _snapshot.asStateFlow()

    private val _deviceIdle = MutableStateFlow<BatteryStatsParser.DeviceIdleInfo?>(null)
    val deviceIdle: StateFlow<BatteryStatsParser.DeviceIdleInfo?> = _deviceIdle.asStateFlow()

    private val _powerManager = MutableStateFlow<BatteryStatsParser.PowerManagerInfo?>(null)
    val powerManager: StateFlow<BatteryStatsParser.PowerManagerInfo?> = _powerManager.asStateFlow()

    private val _lastRefresh = MutableStateFlow(0L)
    val lastRefresh: StateFlow<Long> = _lastRefresh.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    suspend fun refresh(): Boolean {
        if (!refreshing.compareAndSet(false, true)) {
            Log.d(TAG, "Refresh already in progress")
            return false
        }

        _isRefreshing.value = true
        _error.value = null
        Log.d(TAG, "Starting refresh...")

        return try {
            val mode = shellRunner.detectMode()
            if (mode == ShellRunner.Mode.NONE) {
                _error.value = "Need Shizuku, root, or ADB-granted DUMP/BATTERY_STATS. See Settings > Advanced Stats."
                Log.e(TAG, "No privileged access available")
                return false
            }

            var hasData = false

            // Fetch battery stats
            Log.d(TAG, "Fetching batterystats via $mode...")
            val statsResult = shellRunner.run("dumpsys batterystats --checkin")
            if (statsResult != null) {
                val statsRaw = statsResult.output
                if (statsRaw.isNotBlank() && !statsRaw.startsWith("ERROR") && !statsRaw.contains("Permission Denial")) {
                    Log.d(TAG, "Parsing batterystats (${statsRaw.length} chars, via ${statsResult.mode})...")
                    val parsed = BatteryStatsParser.parseCheckin(statsRaw)
                    _snapshot.value = parsed
                    hasData = true
                    Log.d(TAG, "Parsed ${parsed.apps.size} apps, ${parsed.wakelocks.size} wakelocks")
                } else {
                    Log.w(TAG, "Empty or error batterystats output: ${statsRaw.take(200)}")
                    _error.value = "Failed to get battery stats: empty/error output"
                }
            } else {
                Log.e(TAG, "batterystats command failed via all runners")
                _error.value = "Failed to get battery stats. Grant DUMP via ADB or start Shizuku."
            }

            // Device idle info
            Log.d(TAG, "Fetching deviceidle...")
            val idleResult = shellRunner.run("dumpsys deviceidle")
            if (idleResult != null && idleResult.output.isNotBlank()) {
                _deviceIdle.value = BatteryStatsParser.parseDeviceIdle(idleResult.output)
                hasData = true
            } else {
                Log.w(TAG, "deviceidle command failed or empty")
            }

            // Power manager info
            Log.d(TAG, "Fetching power manager...")
            val powerResult = shellRunner.run("dumpsys power")
            if (powerResult != null && powerResult.output.isNotBlank()) {
                _powerManager.value = BatteryStatsParser.parsePowerManager(powerResult.output)
                hasData = true
            } else {
                Log.w(TAG, "power command failed or empty")
            }

            if (hasData) {
                _lastRefresh.value = System.currentTimeMillis()
                Log.d(TAG, "Refresh completed successfully")
            } else if (_error.value == null) {
                _error.value = "No data received from any command"
            }

            hasData
        } catch (e: Exception) {
            Log.e(TAG, "Refresh failed with exception", e)
            _error.value = "Error: ${e.message}"
            false
        } finally {
            _isRefreshing.value = false
            refreshing.set(false)
        }
    }

    suspend fun resetStats(): Boolean {
        val result = shellRunner.run("dumpsys batterystats --reset") ?: return false
        return result.output.contains("Battery stats reset") || result.output.isBlank()
    }

    fun startAutoRefresh(intervalMs: Long = 60_000L): Job {
        return scope.launch {
            while (isActive) {
                refresh()
                delay(intervalMs)
            }
        }
    }
}
