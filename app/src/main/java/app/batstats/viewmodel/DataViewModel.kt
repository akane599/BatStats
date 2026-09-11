package app.batstats.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.batstats.battery.data.ExportImportManager
import app.batstats.settings.AppSettings
import io.github.mlmgames.settings.core.SettingsRepository
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.CancellationException
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
    val summary: StateFlow<DataSummary> = combine(settingsRepository.flow, exportImportManager.sampleCount) { settings, count ->
        DataSummary(settings.firstLaunchTime, count, settings.lastExportTime)
    }
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
    ) = runOperation("JSON Exported Successfully", "Export Failed", export = true) {
        exportImportManager.exportJson(uri, from, to, includeSamples, includeSessions)
    }

    fun exportCsv(uri: Uri, from: Long, to: Long, includeSamples: Boolean = true, includeSessions: Boolean = true) =
        runOperation("CSV Exported Successfully", "Export Failed", export = true) {
            exportImportManager.exportCsvToFolder(uri, from, to, includeSamples, includeSessions)
        }

    fun importJson(uri: Uri) = runOperation("JSON Imported Successfully", "Import Failed") {
        exportImportManager.importJson(uri)
    }

    fun importCsv(uri: Uri) = runOperation("CSV Imported Successfully", "Import Failed") {
        exportImportManager.importCsv(uri)
    }

    private fun runOperation(
        successMessage: String,
        failureMessage: String,
        export: Boolean = false,
        operation: suspend () -> Boolean
    ) {
        // Set the guard before launching so repeated taps cannot overlap two restores.
        if (_isBusy.value) return
        _isBusy.value = true
        _message.value = null
        viewModelScope.launch {
            try {
                val success = operation()
                if (success && export) {
                    settingsRepository.update { it.copy(lastExportTime = System.currentTimeMillis()) }
                }
                _message.value = if (success) successMessage else failureMessage
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _message.value = failureMessage
            } finally {
                _isBusy.value = false
            }
        }
    }
}
