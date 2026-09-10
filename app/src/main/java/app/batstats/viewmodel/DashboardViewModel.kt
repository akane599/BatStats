package app.batstats.viewmodel

import android.app.Application
import android.content.Intent
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.batstats.battery.data.BatteryRepository
import app.batstats.battery.data.db.BatterySample
import app.batstats.battery.data.db.ChargeSession
import app.batstats.battery.data.db.SessionType
import app.batstats.battery.service.BatteryMonitorService
import app.batstats.battery.util.Notifier
import app.batstats.settings.AppSettings
import app.batstats.settings.AppSettingsSchema
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

class DashboardViewModel(
    private val app: Application,
    private val repo: BatteryRepository,
    settingsRepository: SettingsRepository<AppSettings>
) : AndroidViewModel(app) {

    private val settings: StateFlow<AppSettings> = settingsRepository.flow
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
    val recentSamples: Flow<List<BatterySample>> = settings
        .flatMapLatest { s ->
            repo.recentSamplesFlow(s.chartTimeRangeMs)
        }

    private val _liveCurrent = MutableStateFlow<List<Float>>(emptyList())
    val liveCurrent: StateFlow<List<Float>> = _liveCurrent.asStateFlow()

    init {
        viewModelScope.launch {
            realtime.collectLatest { rt ->
                val current = rt.currentMa.toFloat()
                _liveCurrent.update { history ->
                    (history + current).takeLast(100)
                }
            }
        }
    }

    fun toggleMonitoring() {
        if (isMonitoring.value) {
            val intent = Intent(app, BatteryMonitorService::class.java)
            app.stopService(intent)
            // Repo update happens in Service.onDestroy
        } else {
            Notifier.ensureChannel(app)
            // minSdk is 26, so the pre-O startService branch was unreachable.
            app.startForegroundService(Intent(app, BatteryMonitorService::class.java))
            // Repo update happens in Service.onStartCommand
        }
    }

    fun startManualSession(type: SessionType) {
        viewModelScope.launch {
            repo.startSession(type)
        }
    }

    fun endSession() {
        viewModelScope.launch {
            repo.endCurrentSession()
        }
    }
}