package app.batstats.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.batstats.battery.data.ExportImportManager
import app.batstats.settings.AppSettings
import io.github.mlmgames.settings.core.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the app has collected so far, shown above the export controls. */
data class DataSummary(
    val collectingSince: Long = 0L,
    val totalSamples: Long = 0L,
    val lastExport: Long = 0L
)

class DataViewModel(
    private val exportImportManager: ExportImportManager,
    private val settingsRepository: SettingsRepository<AppSettings>
) : ViewModel() {

    /**
     * firstLaunchTime, totalSamplesCollected and lastExportTime were all being written and
     * never read. They belong here: you are about to export, so this is what there is.
     */
    val summary: StateFlow<DataSummary> = settingsRepository.flow
        .map { DataSummary(it.firstLaunchTime, it.totalSamplesCollected, it.lastExportTime) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DataSummary())

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun clearMessage() {
        _message.value = null
    }

    fun exportJson(
        uri: Uri,
        from: Long,
        to: Long,
        includeSamples: Boolean,
        includeSessions: Boolean
    ) {
        viewModelScope.launch {
            _isBusy.value = true
            val success = exportImportManager.exportJson(uri, from, to, includeSamples, includeSessions)
            if (success) markExported()
            _message.value = if (success) "JSON Exported Successfully" else "Export Failed"
            _isBusy.value = false
        }
    }

    fun exportCsv(uri: Uri, from: Long, to: Long) {
        viewModelScope.launch {
            _isBusy.value = true
            val success = exportImportManager.exportCsvToFolder(uri, from, to)
            if (success) markExported()
            _message.value = if (success) "CSV Exported Successfully" else "Export Failed"
            _isBusy.value = false
        }
    }

    fun importJson(uri: Uri) {
        viewModelScope.launch {
            _isBusy.value = true
            val success = exportImportManager.importJson(uri)
            _message.value = if (success) "JSON Imported Successfully" else "Import Failed"
            _isBusy.value = false
        }
    }

    fun importCsv(uri: Uri) {
        viewModelScope.launch {
            _isBusy.value = true
            val success = exportImportManager.importCsv(uri)
            _message.value = if (success) "CSV Imported Successfully" else "Import Failed"
            _isBusy.value = false
        }
    }

    private suspend fun markExported() {
        settingsRepository.update { it.copy(lastExportTime = System.currentTimeMillis()) }
    }
}
