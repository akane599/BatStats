package app.batstats.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.batstats.battery.shizuku.ShizukuBridge
import app.batstats.battery.util.BatteryCapacity
import app.batstats.battery.util.DetailedStatsCollector
import app.batstats.battery.util.PrivilegeChecker
import app.batstats.battery.util.RootStatsCollector
import app.batstats.battery.util.ShellRunner
import app.batstats.settings.AppSettings
import io.github.mlmgames.settings.core.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DetailedStatsViewModel(
    private val collector: DetailedStatsCollector,
    private val shizukuBridge: ShizukuBridge,
    private val shellRunner: ShellRunner,
    private val context: Context,
    settingsRepository: SettingsRepository<AppSettings>
) : ViewModel() {

    /** "Compact Stats View": renders the cards densely. */
    val compactView: StateFlow<Boolean> = settingsRepository.flow
        .map { it.compactStatsView }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    // Forward flows from collector
    val snapshot = collector.snapshot
    val deviceIdle = collector.deviceIdle
    val powerManager = collector.powerManager
    val lastRefresh = collector.lastRefresh
    val isRefreshing = collector.isRefreshing
    val error = collector.error

    private val _hasShizuku = MutableStateFlow(false)
    val hasShizuku: StateFlow<Boolean> = _hasShizuku.asStateFlow()

    private val _shizukuRunning = MutableStateFlow(false)
    val shizukuRunning: StateFlow<Boolean> = _shizukuRunning.asStateFlow()

    private val _shizukuDenied = MutableStateFlow(false)
    val shizukuDenied: StateFlow<Boolean> = _shizukuDenied.asStateFlow()

    private val _hasRoot = MutableStateFlow(false)
    val hasRoot: StateFlow<Boolean> = _hasRoot.asStateFlow()

    private val _hasAdb = MutableStateFlow(false)
    val hasAdb: StateFlow<Boolean> = _hasAdb.asStateFlow()

    private val _hasAdvanced = MutableStateFlow(false)
    val hasAdvanced: StateFlow<Boolean> = _hasAdvanced.asStateFlow()

    private val _advMode = MutableStateFlow<ShellRunner.Mode>(ShellRunner.Mode.NONE)
    val advMode: StateFlow<ShellRunner.Mode> = _advMode.asStateFlow()

    private val _kernelBattery = MutableStateFlow<RootStatsCollector.KernelBatteryInfo?>(null)
    val kernelBattery: StateFlow<RootStatsCollector.KernelBatteryInfo?> = _kernelBattery.asStateFlow()

    /** Full battery capacity, or 0 when unknown - percentages are then not shown. */
    private val _capacityMah = MutableStateFlow(0.0)
    val capacityMah: StateFlow<Double> = _capacityMah.asStateFlow()

    private var refreshJob: Job? = null

    init {
        // Seed from the bridge first: these are StateFlows, so their initial emission would
        // otherwise look like a permission that just landed and kick off a second refresh.
        _hasShizuku.value = shizukuBridge.granted.value
        _shizukuRunning.value = shizukuBridge.running.value

        // The bridge owns the Shizuku listeners; react to a grant landing while we are open.
        viewModelScope.launch {
            shizukuBridge.granted.collect { granted ->
                val justGranted = granted && !_hasShizuku.value
                _hasShizuku.value = granted
                if (justGranted) {
                    shellRunner.invalidateMode()
                    refresh(forceRefresh = true)
                }
            }
        }
        viewModelScope.launch {
            shizukuBridge.running.collect { _shizukuRunning.value = it }
        }
        // No initial refresh here on purpose: the screen asks for one when it appears, and
        // starting a second from init only produced a refresh that got thrown away.
    }

    fun recheck() {
        refresh(forceRefresh = true)
    }

    fun requestShizukuPermission() {
        shizukuBridge.requestPermission()
    }

    fun clearError() = collector.clearError()

    /**
     * Refreshes everything on the screen. Overlapping calls collapse into the one already
     * running rather than queueing behind it - opening the screen used to fire two.
     * [forceRefresh] restarts even so, for cases where the underlying state just changed.
     */
    fun refresh(forceRefresh: Boolean = false) {
        if (!forceRefresh && refreshJob?.isActive == true) return
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            if (forceRefresh) shellRunner.invalidateMode()

            // ADB grants and Shizuku can both change while the app is alive.
            _hasAdb.value = PrivilegeChecker.hasAdvancedViaAdb(context)
            _hasShizuku.value = shizukuBridge.hasPermissionResilient()
            _shizukuRunning.value = shizukuBridge.ping()
            _shizukuDenied.value = shizukuBridge.isPermanentlyDenied()
            _hasRoot.value = RootStatsCollector.isRootAvailable()

            val mode = shellRunner.detectMode(forceRefresh)
            _advMode.value = mode
            _hasAdvanced.value = mode != ShellRunner.Mode.NONE ||
                _hasShizuku.value || _hasRoot.value || _hasAdb.value

            if (_hasAdvanced.value) {
                collector.refresh()
            }
            // batterystats reports the capacity Android itself attributes against; measure
            // it only if the dump did not carry one.
            _capacityMah.value = BatteryCapacity.resolveMah(
                context,
                collector.snapshot.value?.estimatedCapacityMah ?: 0
            ) ?: 0.0
            if (_hasRoot.value) {
                _kernelBattery.value = RootStatsCollector.getKernelBatteryInfo()
            }
        }
    }

    fun refreshRootStats() {
        viewModelScope.launch {
            if (_hasRoot.value) {
                _kernelBattery.value = RootStatsCollector.getKernelBatteryInfo()
            }
        }
    }

    suspend fun resetStats(): Boolean {
        return try {
            if (_hasAdvanced.value) collector.resetStats() else false
        } catch (_: Exception) {
            false
        }
    }
}
