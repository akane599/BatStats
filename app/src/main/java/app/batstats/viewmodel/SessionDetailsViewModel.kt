package app.batstats.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.batstats.battery.data.BatteryRepository
import app.batstats.battery.data.db.BatteryDatabase
import app.batstats.battery.data.db.BatteryChartPoint
import app.batstats.battery.data.db.SessionType
import app.batstats.settings.AppSettings
import app.batstats.settings.useFahrenheit
import io.github.mlmgames.settings.core.SettingsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*

@OptIn(ExperimentalCoroutinesApi::class)
class SessionDetailsViewModel(
    app: Application,
    private val repo: BatteryRepository,
    private val db: BatteryDatabase,
    private val sessionId: String,
    settings: SettingsRepository<AppSettings>
) : AndroidViewModel(app) {
    data class Ui(
        val loading: Boolean = true,
        val type: SessionType? = null,
        val start: Long = 0L,
        val end: Long? = null,
        val levelRange: String = "",
        val capacityMah: Int? = null,
        val avgCurrent: Long? = null,
        val points: List<BatteryChartPoint> = emptyList()
    )
    val fahrenheit = settings.flow.map { it.useFahrenheit }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val ui: StateFlow<Ui> = db.sessionDao().session(sessionId).flatMapLatest { session ->
        if (session == null) return@flatMapLatest flowOf(Ui(loading = false))
        // Closed sessions do not re-query on every live current reading. Aggregate in SQL
        // before loading history, bounding even a months-long session to about 300 points.
        val bounds = if (session.endTime != null) flowOf(session.endTime)
            else repo.realtimeFlow.map { System.currentTimeMillis() }
        bounds.flatMapLatest { end ->
            db.batteryDao().sessionChart(session.startTime, end, ((end - session.startTime) / 300).coerceAtLeast(1000))
                .map { points ->
                    val endLevel = session.endLevel ?: repo.realtimeFlow.value.sample?.levelPercent ?: session.startLevel
                    Ui(false, session.type, session.startTime, session.endTime,
                        "${session.startLevel}% → $endLevel%", session.estCapacityMah, session.avgCurrentUa, points)
                }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Ui())
}
