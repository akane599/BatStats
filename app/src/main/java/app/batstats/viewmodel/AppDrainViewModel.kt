package app.batstats.viewmodel

import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.batstats.battery.data.db.AppEnergyDao
import app.batstats.battery.util.AppInfoResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/** How far back the app drain list looks. */
enum class DrainRange(val millis: Long) {
    LAST_24H(24 * 60 * 60 * 1000L),
    LAST_7D(7 * 24 * 60 * 60 * 1000L),
    LAST_30D(30 * 24 * 60 * 60 * 1000L)
}

/**
 * How the figures were arrived at. Worth showing: one is what the system measured, the
 * other is this app's own estimate, and they should not be read the same way.
 */
enum class DrainSource { MEASURED, ESTIMATED }

data class AppDrainRow(
    val packageName: String,
    val label: String,
    val icon: ImageBitmap?,
    val energyMah: Double,
    val shareOfTotal: Float,
    val isSystem: Boolean
)

data class AppDrainUiState(
    val rows: List<AppDrainRow> = emptyList(),
    val totalMah: Double = 0.0,
    val source: DrainSource? = null,
    val loading: Boolean = true
)

class AppDrainViewModel(
    private val dao: AppEnergyDao,
    private val appInfo: AppInfoResolver
) : ViewModel() {

    private val _range = MutableStateFlow(DrainRange.LAST_24H)
    val range: StateFlow<DrainRange> = _range.asStateFlow()

    private val _showSystemApps = MutableStateFlow(false)
    val showSystemApps: StateFlow<Boolean> = _showSystemApps.asStateFlow()

    fun setRange(range: DrainRange) = _range.update { range }

    fun toggleSystemApps() = _showSystemApps.update { !it }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val readings = _range.flatMapLatest { range ->
        flow {
            while (true) {
                emit(System.currentTimeMillis())
                delay(60_000L)
            }
        }.flatMapLatest { to ->
            // Stored energy is bucketed by hour; include the partially overlapping first
            // bucket and explain that precision in the UI.
            val from = ((to - range.millis) / 3_600_000L) * 3_600_000L
            dao.modesInRange(from, to).flatMapLatest { modes ->
                val measured = modes.any { it != HEURISTIC }
                val mode = if (measured) "MEASURED" else HEURISTIC
                dao.drainersInRange(from, to, mode).map { aggregates ->
                    val total = aggregates.sumOf { it.energyMah }
                    AppDrainUiState(
                        rows = aggregates.map { aggregate ->
                            AppDrainRow(
                                packageName = aggregate.packageName,
                                label = appInfo.label(aggregate.packageName),
                                icon = appInfo.icon(aggregate.packageName),
                                energyMah = aggregate.energyMah,
                                shareOfTotal = if (total > 0) (aggregate.energyMah / total).toFloat() else 0f,
                                isSystem = appInfo.isSystem(aggregate.packageName)
                            )
                        },
                        totalMah = total,
                        source = if (measured) DrainSource.MEASURED else DrainSource.ESTIMATED,
                        loading = false
                    )
                }
            }
        }
    }.flowOn(Dispatchers.Default)

    val state: StateFlow<AppDrainUiState> = combine(readings, _showSystemApps) { state, showSystem ->
        state.copy(rows = state.rows.filter { showSystem || !it.isSystem })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppDrainUiState())

    private companion object {
        const val HEURISTIC = "HEURISTIC"
    }
}
