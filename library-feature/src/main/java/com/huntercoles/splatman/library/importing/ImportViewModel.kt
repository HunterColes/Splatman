package com.huntercoles.splatman.library.importing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.huntercoles.splatman.core.coroutines.IoDispatcher
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.IOException
import javax.inject.Inject

/** Validation shorter than this never shows its dialog (docs/design/screens/import.md, Flow 2). */
const val VALIDATING_DIALOG_DELAY_MS = 300L

/** The import flow's dialog over the Library: none, "Reading <name>…", or the error with its OK button. */
sealed interface ImportDialog {
    data class Validating(val fileName: String) : ImportDialog

    data class Error(val message: ImportMessage) : ImportDialog
}

/** [opened] is the imported scan to open in the Viewer, once; the screen consumes it with [ImportIntent.Opened]. */
data class ImportUiState(
    val dialog: ImportDialog? = null,
    val opened: OpenedImport? = null,
) {
    val busy: Boolean get() = dialog is ImportDialog.Validating
}

data class OpenedImport(val scanId: String, val name: String)

sealed interface ImportIntent {
    /** A document the system picker returned, or a file another app handed over. */
    data class Pick(val uri: String) : ImportIntent

    /** Back on the validating dialog. */
    data object Cancel : ImportIntent

    /** OK on the error dialog. */
    data object Dismiss : ImportIntent

    data object Opened : ImportIntent
}

/**
 * Runs one import at a time: the validating dialog appears only after [VALIDATING_DIALOG_DELAY_MS], an invalid file
 * turns it into the error dialog, a valid one becomes a Library entry that [ImportUiState.opened] hands to the Viewer.
 * Files from "Open with" ([ImportRequests]) start the same flow.
 */
@HiltViewModel
class ImportViewModel @Inject constructor(
    private val importer: Importer,
    private val sources: ImportSourceFactory,
    private val requests: ImportRequests,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ImportUiState())
    val uiState: StateFlow<ImportUiState> = _uiState.asStateFlow()

    private var job: Job? = null

    init {
        viewModelScope.launch {
            requests.requests.filterNotNull().collect { uri ->
                if (requests.take(uri)) onIntent(ImportIntent.Pick(uri))
            }
        }
    }

    fun onIntent(intent: ImportIntent) {
        when (intent) {
            is ImportIntent.Pick -> start(intent.uri)
            ImportIntent.Cancel -> {
                job?.cancel()
                job = null
                _uiState.update { it.copy(dialog = null) }
            }
            ImportIntent.Dismiss -> _uiState.update { it.copy(dialog = null) }
            ImportIntent.Opened -> _uiState.update { it.copy(opened = null) }
        }
    }

    private fun start(uri: String) {
        if (job?.isActive == true) return
        _uiState.update { it.copy(dialog = null) }
        job = viewModelScope.launch {
            val source = sources.create(uri)
            val name = withContext(io) { source.fileName }
            val dialog = launch {
                delay(VALIDATING_DIALOG_DELAY_MS)
                _uiState.update { it.copy(dialog = ImportDialog.Validating(name)) }
            }
            val outcome = try {
                importer.import(source)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                Timber.w(e, "Import of %s failed", name)
                ImportOutcome.Refused(ImportValidation.Corrupt)
            } catch (e: SecurityException) {
                Timber.w(e, "No read access to %s", name)
                ImportOutcome.Refused(ImportValidation.Corrupt)
            }
            dialog.cancel()
            _uiState.update {
                when (outcome) {
                    is ImportOutcome.Imported ->
                        it.copy(dialog = null, opened = OpenedImport(outcome.scan.id, outcome.scan.name))
                    is ImportOutcome.Refused ->
                        it.copy(dialog = outcome.validation.message()?.let(ImportDialog::Error))
                }
            }
        }
    }
}
