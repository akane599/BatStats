package app.batstats.battery.util

import android.content.Context
import android.util.Log
import app.batstats.battery.data.db.BatteryDatabase
import app.batstats.battery.shizuku.ShizukuBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Collects comprehensive battery stats using Root/Shizuku/ADB-granted DUMP.
 * Provides parsed data for the detailed stats screen.
 */
class DetailedStatsCollector(
    private val shellRunner: ShellRunner,
    private val db: BatteryDatabase,
    private val context: Context,
    private val checkinSource: CheckinSource,
    // NOTE: only for direct checks (if later used)
    private val shizuku: ShizukuBridge? = null
) {
    companion object {
        private const val TAG = "DetailedStatsCollector"

        const val NO_ACCESS_MESSAGE =
            "Need Shizuku, root, or ADB-granted DUMP/BATTERY_STATS. See Settings > Advanced Stats."
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val refreshing = AtomicBoolean(false)
    private val packageNames = PackageNameResolver(context)

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

    private val _mode = MutableStateFlow(ShellRunner.Mode.NONE)
    val mode: StateFlow<ShellRunner.Mode> = _mode.asStateFlow()

    fun clearError() {
        _error.value = null
    }

    suspend fun refresh(): Boolean = withContext(Dispatchers.Default) {
        if (!refreshing.compareAndSet(false, true)) {
            Log.d(TAG, "Refresh already in progress")
            return@withContext false
        }

        _isRefreshing.value = true
        Log.d(TAG, "Starting refresh...")

        try {
            var hasData = false
            var firstFailure: String? = null

            // Battery stats - the one that actually matters. Shared with the background
            // per-app poller so the dump is produced once, not once per collector.
            Log.d(TAG, "Fetching batterystats...")
            when (val stats = checkinSource.get()) {
                is ShellRunner.Outcome.Success -> {
                    _mode.value = stats.mode
                    Log.d(TAG, "Parsing batterystats (${stats.output.length} chars, via ${stats.mode})...")
                    val parsed = BatteryStatsParser.parseCheckin(stats.output)
                    // The dump's own uid -> package map is incomplete whenever the caller
                    // could not see other packages; fill the gaps locally.
                    val named = BatteryStatsParser.applyPackageNames(parsed, packageNames::nameFor)
                    _snapshot.value = named
                    hasData = true
                    Log.d(
                        TAG,
                        "Parsed ${named.apps.size} apps, ${named.wakelocks.size} wakelocks, " +
                            "${parsed.mappedPackages} names from the dump, " +
                            "${named.apps.count { it.packageName != "uid:${it.uid}" }} resolved"
                    )
                }

                is ShellRunner.Outcome.Failure -> {
                    _mode.value = stats.mode
                    firstFailure = describe(stats)
                    Log.e(TAG, "batterystats failed: ${stats.mode} / ${stats.message}")
                }
            }

            // Per-app power split by process state. Current Android reports this only in
            // the human-readable dump - the checkin format carries a per-app total and no
            // breakdown at all - so it takes a second, filtered pass.
            if (hasData) {
                when (val power = shellRunner.exec(BatteryStatsParser.POWER_USE_COMMAND)) {
                    is ShellRunner.Outcome.Success -> {
                        val byUid = BatteryStatsParser.parseEstimatedPowerUse(power.output)
                        Log.d(TAG, "Power-use breakdown for ${byUid.size} uids")
                        _snapshot.value = _snapshot.value?.let {
                            BatteryStatsParser.applyPowerStates(it, byUid)
                        }
                    }

                    is ShellRunner.Outcome.Failure ->
                        Log.w(TAG, "power-use dump failed: ${power.message}")
                }
            }

            // Device idle info.
            when (val idle = shellRunner.exec("dumpsys deviceidle")) {
                is ShellRunner.Outcome.Success -> {
                    _deviceIdle.value = BatteryStatsParser.parseDeviceIdle(idle.output)
                }

                is ShellRunner.Outcome.Failure -> {
                    _deviceIdle.value = null
                    Log.w(TAG, "deviceidle failed: ${idle.message}")
                }
            }

            // Power manager info.
            when (val power = shellRunner.exec("dumpsys power")) {
                is ShellRunner.Outcome.Success -> {
                    _powerManager.value = BatteryStatsParser.parsePowerManager(power.output)
                }

                is ShellRunner.Outcome.Failure -> {
                    _powerManager.value = null
                    Log.w(TAG, "power failed: ${power.message}")
                }
            }

            if (hasData) {
                _lastRefresh.value = System.currentTimeMillis()
                _error.value = null
                Log.d(TAG, "Refresh completed successfully")
            } else {
                _error.value = firstFailure ?: NO_ACCESS_MESSAGE
            }

            hasData
        } catch (ce: CancellationException) {
            // Navigating away cancels the scope; that is not something to show the user.
            throw ce
        } catch (e: Exception) {
            Log.e(TAG, "Refresh failed with exception", e)
            _error.value = "Error: ${e.message}"
            false
        } finally {
            _isRefreshing.value = false
            refreshing.set(false)
        }
    }

    /** Turns a backend failure into something a user can act on. */
    private fun describe(failure: ShellRunner.Outcome.Failure): String = when (failure.mode) {
        ShellRunner.Mode.NONE -> NO_ACCESS_MESSAGE
        ShellRunner.Mode.SHIZUKU ->
            "Shizuku is connected but the dump failed: ${failure.message}. Try again, or restart Shizuku."
        ShellRunner.Mode.ROOT ->
            "Root is available but the dump failed: ${failure.message}."
        ShellRunner.Mode.ADB ->
            "DUMP/BATTERY_STATS is granted but the dump failed: ${failure.message}."
    }

    suspend fun resetStats(): Boolean {
        // A successful reset prints nothing at all, so an empty result is a success here.
        val outcome = shellRunner.exec("dumpsys batterystats --reset", allowEmpty = true)
        // Every cached counter just went to zero; serving the pre-reset dump would make the
        // reset look as though it had not worked.
        checkinSource.invalidate()
        return outcome is ShellRunner.Outcome.Success
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
