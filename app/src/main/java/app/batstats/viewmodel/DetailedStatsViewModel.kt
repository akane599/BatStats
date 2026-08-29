package app.batstats.viewmodel

import android.content.Context
import android.content.pm.PackageManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.batstats.battery.shizuku.ShizukuBridge
import app.batstats.battery.util.DetailedStatsCollector
import app.batstats.battery.util.PrivilegeChecker
import app.batstats.battery.util.RootStatsCollector
import app.batstats.battery.util.ShellRunner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

class DetailedStatsViewModel(
    private val collector: DetailedStatsCollector,
    private val shizukuBridge: ShizukuBridge,
    private val shellRunner: ShellRunner,
    private val context: Context
) : ViewModel() {

    // Forward flows from collector
    val snapshot = collector.snapshot
    val deviceIdle = collector.deviceIdle
    val powerManager = collector.powerManager
    val lastRefresh = collector.lastRefresh
    val isRefreshing = collector.isRefreshing
    val error = collector.error

    private val _hasShizuku = MutableStateFlow(false)
    val hasShizuku: StateFlow<Boolean> = _hasShizuku.asStateFlow()

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

    // Listener for Shizuku permission results
    private val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == 1001) {
            val granted = grantResult == PackageManager.PERMISSION_GRANTED
            _hasShizuku.value = granted
            if (granted) {
                refresh()
            }
        }
    }

    init {
        // Register listener for permission results
        try {
            Shizuku.addRequestPermissionResultListener(permissionListener)
        } catch (_: Throwable) {
            // Shizuku might not be available
        }
        checkPermissions()
    }

    override fun onCleared() {
        super.onCleared()
        try {
            Shizuku.removeRequestPermissionResultListener(permissionListener)
        } catch (_: Throwable) {}
    }

    private fun checkPermissions() {
        viewModelScope.launch {
            _hasShizuku.value = try { shizukuBridge.hasPermission() } catch (_: Exception) { false }
            _hasRoot.value = RootStatsCollector.isRootAvailable()
            _hasAdb.value = PrivilegeChecker.hasAdvancedViaAdb(context)
            _hasAdvanced.value = _hasShizuku.value || _hasRoot.value || _hasAdb.value
            _advMode.value = shellRunner.detectMode()

            if (_hasAdvanced.value) refresh()
            if (_hasRoot.value) refreshRootStats()
        }
    }

    fun recheck() {
        checkPermissions()
    }

    fun requestShizukuPermission() {
        shizukuBridge.requestPermission(1001)
    }

    fun refresh() {
        viewModelScope.launch {
            // Recheck ADB grants (they can be granted while app is alive)
            _hasAdb.value = PrivilegeChecker.hasAdvancedViaAdb(context)
            _hasShizuku.value = try { shizukuBridge.hasPermission() } catch (_: Exception) { false }
            _hasRoot.value = RootStatsCollector.isRootAvailable()
            _hasAdvanced.value = _hasShizuku.value || _hasRoot.value || _hasAdb.value
            _advMode.value = shellRunner.detectMode()

            if (_hasAdvanced.value) {
                collector.refresh()
            }
            if (_hasRoot.value) {
                refreshRootStats()
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
            when {
                _hasAdvanced.value -> collector.resetStats()
                _hasRoot.value -> RootStatsCollector.resetBatteryStats()
                else -> false
            }
        } catch (e: Exception) {
            false
        }
    }
}