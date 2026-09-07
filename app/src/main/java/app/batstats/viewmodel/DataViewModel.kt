package app.batstats.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.batstats.battery.data.ExportImportManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DataViewModel(
    private val exportImportManager: ExportImportManager
) : ViewModel() {

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
    ) = runOperation("JSON Exported Successfully", "Export Failed") {
        exportImportManager.exportJson(uri, from, to, includeSamples, includeSessions)
    }

    fun exportCsv(uri: Uri, from: Long, to: Long) =
        runOperation("CSV Exported Successfully", "Export Failed") {
            exportImportManager.exportCsvToFolder(uri, from, to)
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
        operation: suspend () -> Boolean
    ) {
        // Set the guard before launching so repeated taps cannot overlap two restores.
        if (_isBusy.value) return
        _isBusy.value = true
        _message.value = null
        viewModelScope.launch {
            try {
                _message.value = if (operation()) successMessage else failureMessage
            } finally {
                _isBusy.value = false
            }
        }
    }
}
