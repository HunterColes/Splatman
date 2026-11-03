package com.huntercoles.splatman.viewer.export

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.huntercoles.splatman.core.preferences.SettingsPreferences
import com.huntercoles.splatman.splat.model.SplatCloud
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.OutputStream
import javax.inject.Inject

sealed interface ExportStatus {
    data object Idle : ExportStatus
    data class Working(val progress: Float) : ExportStatus
    data class Failed(val message: String) : ExportStatus
}

/** Finished share files, waiting for the screen to send them ([ExportIntent.ShareLaunched] clears it). */
data class ShareRequest(val uris: List<String>, val fileNames: List<String>)

/** One scan to export: its [entry] (name and size) and how to load its cloud, called off the main thread. */
class ExportItem(val entry: ExportEntry, val load: suspend () -> SplatCloud)

data class ExportUiState(
    val formats: List<ExportFormat> = ExportCodecs.AVAILABLE.map { it.format },
    val selected: ExportFormat = ExportCodecs.DEFAULT,
    val sheetOpen: Boolean = false,
    val entries: List<ExportEntry> = emptyList(),
    /** Free bytes where the export is written, read when the sheet opens; null when unknown. */
    val freeBytes: Long? = null,
    val status: ExportStatus = ExportStatus.Idle,
    val share: ShareRequest? = null,
    /** File names just saved, for the screen's snackbar ([ExportIntent.SavedShown] clears it). */
    val saved: List<String>? = null,
) {
    /** Several scans (from the Library): the title counts them, Save picks a folder. */
    val multi: Boolean get() = entries.size > 1
    val fileNames: List<String> get() = ExportFileName.unique(entries.map { it.name }, selected)
    val fileName: String get() = fileNames.firstOrNull() ?: ExportFileName.of("", selected)
    val working: Boolean get() = status is ExportStatus.Working

    fun size(format: ExportFormat): Long = ExportSize.of(format, entries)

    val lowStorage: Boolean get() = entries.isNotEmpty() && ExportStorage.isLow(size(selected), freeBytes)
    val canExport: Boolean get() = entries.isNotEmpty() && !working && !lowStorage
}

sealed interface ExportIntent {
    data object Show : ExportIntent

    /** Closes the sheet; during a write (system Back) it cancels the write first. */
    data object Dismiss : ExportIntent
    data class Select(val format: ExportFormat) : ExportIntent

    /** The `CreateDocument` result for one scan; null when the user backed out of the picker. */
    data class SaveTo(val uri: String?) : ExportIntent

    /** The `OpenDocumentTree` result for several scans; null when the user backed out. */
    data class SaveToFolder(val treeUri: String?) : ExportIntent
    data object Share : ExportIntent
    data object ShareLaunched : ExportIntent
    data object SavedShown : ExportIntent
}

/**
 * The export sheet behind the viewer's Share action and the Library's selection: holds the scans ([setScene] for
 * one, [setItems] for several), the chosen format (Settings' default when the sheet opens), and runs one export at
 * a time through [ExportUseCase] (off the main thread) to saved documents or share files.
 */
@HiltViewModel
class ExportViewModel @Inject constructor(
    private val useCase: ExportUseCase,
    private val target: ExportTarget,
    private val settings: SettingsPreferences,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ExportUiState(formats = useCase.formats, selected = settingsFormat()))
    val uiState: StateFlow<ExportUiState> = _uiState.asStateFlow()

    private var items: List<ExportItem> = emptyList()
    private var job: Job? = null

    /** The scene the viewer shows now, or nulls when it shows none. */
    fun setScene(name: String?, cloud: SplatCloud?) {
        setItems(
            if (cloud == null) {
                emptyList()
            } else {
                listOf(ExportItem(ExportEntry(name.orEmpty(), cloud.count, cloud.shDegree)) { cloud })
            },
        )
    }

    /** The scans to export (the Library's selection). */
    fun setItems(items: List<ExportItem>) {
        this.items = items
        _uiState.update { it.copy(entries = items.map(ExportItem::entry)) }
    }

    fun onIntent(intent: ExportIntent) {
        when (intent) {
            ExportIntent.Show -> show()
            ExportIntent.Dismiss -> {
                job?.cancel()
                _uiState.update { it.copy(sheetOpen = false, status = ExportStatus.Idle) }
            }
            is ExportIntent.Select -> _uiState.update {
                if (intent.format in it.formats && !it.working) it.copy(selected = intent.format) else it
            }
            is ExportIntent.SaveTo -> intent.uri?.let(::save)
            is ExportIntent.SaveToFolder -> intent.treeUri?.let(::saveToFolder)
            ExportIntent.Share -> share()
            ExportIntent.ShareLaunched -> _uiState.update { it.copy(share = null, sheetOpen = false) }
            ExportIntent.SavedShown -> _uiState.update { it.copy(saved = null) }
        }
    }

    private fun show() {
        if (_uiState.value.working) {
            _uiState.update { it.copy(sheetOpen = true) }
            return
        }
        val free = target.freeBytes()
        _uiState.update {
            val default = settingsFormat()
            it.copy(
                sheetOpen = true,
                status = ExportStatus.Idle,
                freeBytes = free,
                selected = if (default in it.formats) default else it.selected,
            )
        }
    }

    private fun settingsFormat(): ExportFormat = ExportFormat.of(settings.exportFormat.value)

    private fun save(uri: String) {
        if (_uiState.value.multi) return
        run(
            open = { _ -> target.openDocument(uri) },
            discard = { target.deleteDocument(uri) },
            onDone = ::saved,
        )
    }

    private fun saveToFolder(treeUri: String) {
        val created = mutableListOf<String>()
        run(
            open = { name ->
                val doc = target.createInFolder(treeUri, name)
                created += doc
                target.openDocument(doc)
            },
            discard = { created.forEach(target::deleteDocument) },
            onDone = ::saved,
        )
    }

    private fun saved(names: List<String>) {
        _uiState.update { it.copy(sheetOpen = false, saved = names) }
    }

    private fun share() {
        run(
            before = { target.clearShareFiles() },
            open = { name -> target.openShareFile(name) },
            discard = { target.clearShareFiles() },
        ) { names ->
            try {
                val uris = names.map(target::shareUri)
                _uiState.update { it.copy(share = ShareRequest(uris, names)) }
            } catch (ignored: IllegalArgumentException) {
                _uiState.update { it.copy(status = ExportStatus.Failed(ExportUseCase.WRITE_ERROR)) }
            } catch (ignored: IOException) {
                _uiState.update { it.copy(status = ExportStatus.Failed(ExportUseCase.WRITE_ERROR)) }
            }
        }
    }

    /**
     * Writes every scan in turn ([open] gets each file name), progress spread over all of them. A failure stops the
     * batch with [ExportStatus.Failed]; a failure or a cancel removes what was written ([discard]).
     */
    private fun run(
        before: () -> Unit = {},
        open: (fileName: String) -> OutputStream,
        discard: () -> Unit,
        onDone: (fileNames: List<String>) -> Unit,
    ) {
        val state = _uiState.value
        val batch = items
        if (batch.isEmpty() || !state.canExport) return
        val names = state.fileNames
        val format = state.selected
        _uiState.update { it.copy(status = ExportStatus.Working(0f)) }
        job = viewModelScope.launch {
            try {
                useCase.cleanUp(before)
                for ((i, item) in batch.withIndex()) {
                    val result = useCase.export(item.load, format, { open(names[i]) }) { p ->
                        progress((i + p) / batch.size)
                    }
                    if (result is ExportResult.Failed) {
                        useCase.cleanUp(discard)
                        _uiState.update { it.copy(status = ExportStatus.Failed(result.message)) }
                        return@launch
                    }
                }
                _uiState.update { it.copy(status = ExportStatus.Idle) }
                onDone(names)
            } catch (e: CancellationException) {
                useCase.cleanUp(discard)
                throw e
            }
        }
    }

    private fun progress(p: Float) {
        _uiState.update { s -> if (s.working) s.copy(status = ExportStatus.Working(p)) else s }
    }
}
