package app.batstats.viewmodel

import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.batstats.battery.data.db.AppEnergyDao
import app.batstats.battery.util.AppInfoResolver
import kotlinx.coroutines.Dispatchers
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
    val state: StateFlow<AppDrainUiState> = combine(_range, _showSystemApps, ::Pair)
        .flatMapLatest { (range, showSystem) ->
            val to = System.currentTimeMillis()
            val from = to - range.millis

            dao.modesInRange(from, to).flatMapLatest { modes ->
                // A privileged dump is a real measurement, so it wins outright over the
                // heuristic estimate. Adding the two together would be adding a measurement
                // to a guess about the same hours.
                val mode = modes.firstOrNull { it != HEURISTIC } ?: modes.firstOrNull()
                if (mode == null) {
                    kotlinx.coroutines.flow.flowOf(AppDrainUiState(loading = false))
                } else {
                    dao.drainersInRange(from, to, mode).map { aggregates ->
                        // The share is of everything recorded, including whatever is being
                        // filtered out of the list - hiding system apps should not inflate
                        // the percentages of the ones left.
                        val total = aggregates.sumOf { it.energyMah }
                        val rows = aggregates
                            .map { it to appInfo.isSystem(it.packageName) }
                            .filter { (_, isSystem) -> showSystem || !isSystem }
                            .map { (aggregate, isSystem) ->
                                AppDrainRow(
                                    packageName = aggregate.packageName,
                                    label = appInfo.label(aggregate.packageName),
                                    icon = appInfo.icon(aggregate.packageName),
                                    energyMah = aggregate.energyMah,
                                    shareOfTotal =
                                        if (total > 0) (aggregate.energyMah / total).toFloat() else 0f,
                                    isSystem = isSystem
                                )
                            }
                        AppDrainUiState(
                            rows = rows,
                            totalMah = total,
                            source = if (mode == HEURISTIC) DrainSource.ESTIMATED
                            else DrainSource.MEASURED,
                            loading = false
                        )
                    }
                }
            }
        }
        // Label and icon lookups hit PackageManager, which is too slow for the main thread.
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = AppDrainUiState()
        )

    private companion object {
        const val HEURISTIC = "HEURISTIC"
    }
}
