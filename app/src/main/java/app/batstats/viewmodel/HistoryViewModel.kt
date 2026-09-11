package app.batstats.viewmodel

import androidx.lifecycle.ViewModel
import app.batstats.battery.data.BatteryRepository
import kotlinx.coroutines.flow.map

class HistoryViewModel(
    repo: BatteryRepository
) : ViewModel() {
    // Session metadata is small; keep the entire history searchable rather than silently
    // hiding everything older than the first 100 sessions. Samples remain queried separately.
    val sessions = repo.sessionDao.allSessions()
}
