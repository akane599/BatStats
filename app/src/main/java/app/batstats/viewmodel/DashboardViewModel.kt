package app.batstats.viewmodel

import android.app.Application
import android.content.Intent
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.batstats.battery.data.BatteryRepository
import app.batstats.battery.data.db.BatteryCurrentPoint
import app.batstats.battery.data.db.ChargeSession
import app.batstats.battery.data.db.SessionType
import app.batstats.battery.service.BatteryMonitorService
import app.batstats.battery.util.Notifier
import app.batstats.settings.AppSettings
import app.batstats.settings.AppSettingsSchema
import app.batstats.R
import app.batstats.settings.useFahrenheit
import app.batstats.settings.chartTimeRangeMs
import io.github.mlmgames.settings.core.SettingsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged

class DashboardViewModel(
    private val app: Application,
    private val repo: BatteryRepository,
    settingsRepository: SettingsRepository<AppSettings>
) : AndroidViewModel(app) {

    val settings: StateFlow<AppSettings> = settingsRepository.flow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = AppSettingsSchema.default
        )

    val realtime: StateFlow<BatteryRepository.Realtime> = repo.realtimeFlow

    /** "Show Current in mA": off swaps the hero reading for a share of the battery per hour. */
    val showCurrentInMa: StateFlow<Boolean> = settings
        .map { it.showCurrentInMa }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val isMonitoring: StateFlow<Boolean> = repo.isMonitoringFlow

    val activeSession: StateFlow<ChargeSession?> = repo.activeSessionFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    @OptIn(ExperimentalCoroutinesApi::class)
    val recentSamples: Flow<List<BatteryCurrentPoint>> = settings
        .map { it.chartTimeRangeMs }
        .distinctUntilChanged()
        .flatMapLatest { duration -> repo.currentChartFlow(duration) }

    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _error = MutableStateFlow<Int?>(null)
    val error = _error.asStateFlow()
    fun clearError() { _error.value = null }
    fun refreshReading() = repo.refreshBatteryReading()

    fun toggleMonitoring() {
        if (_busy.value) return
        _busy.value = true
        val target = !isMonitoring.value
        viewModelScope.launch {
            try {
                val intent = Intent(app, BatteryMonitorService::class.java)
                if (target) {
                    Notifier.ensureChannel(app)
                    app.startForegroundService(intent)
                } else app.stopService(intent)
                if (withTimeoutOrNull(5_000L) { isMonitoring.first { it == target } } == null) {
                    _error.value = R.string.monitoring_action_failed
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                _error.value = R.string.monitoring_action_failed
            } finally {
                _busy.value = false
            }
        }
    }

    fun startManualSession(type: SessionType) {
        viewModelScope.launch {
            try { repo.startSession(type) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { _error.value = R.string.action_failed }
        }
    }

    fun endSession() {
        viewModelScope.launch {
            try { repo.endCurrentSession() }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { _error.value = R.string.action_failed }
        }
    }
}
