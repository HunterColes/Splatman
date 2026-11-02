package com.huntercoles.splatman.viewer.export

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.huntercoles.splatman.splat.model.SplatCloud
import dagger.hilt.android.lifecycle.HiltViewModel
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
    data class Saved(val fileName: String) : ExportStatus
    data class Failed(val message: String) : ExportStatus
}

/** A finished share file, waiting for the screen to send it ([ExportIntent.ShareLaunched] clears it). */
data class ShareRequest(val uri: String, val fileName: String)

data class ExportUiState(
    val formats: List<ExportFormat> = ExportCodecs.AVAILABLE.map { it.format },
    val selected: ExportFormat = ExportCodecs.DEFAULT,
    val sheetOpen: Boolean = false,
    val sceneName: String? = null,
    val status: ExportStatus = ExportStatus.Idle,
    val share: ShareRequest? = null,
) {
    val fileName: String get() = ExportFileName.of(sceneName.orEmpty(), selected)
    val canExport: Boolean get() = sceneName != null && status !is ExportStatus.Working
}

sealed interface ExportIntent {
    data object Show : ExportIntent
    data object Dismiss : ExportIntent
    data class Select(val format: ExportFormat) : ExportIntent

    /** The `CreateDocument` result; null when the user backed out of the picker. */
    data class SaveTo(val uri: String?) : ExportIntent
    data object Share : ExportIntent
    data object ShareLaunched : ExportIntent
}

/**
 * The export sheet behind the viewer's Export action: holds the shown scene ([setScene]), the chosen format, and runs
 * one export at a time through [ExportUseCase] (off the main thread) to a saved document or a share file.
 */
@HiltViewModel
class ExportViewModel @Inject constructor(
    private val useCase: ExportUseCase,
    private val target: ExportTarget,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ExportUiState(formats = useCase.formats))
    val uiState: StateFlow<ExportUiState> = _uiState.asStateFlow()

    private var cloud: SplatCloud? = null
    private var job: Job? = null

    /** The scene the viewer shows now, or nulls when it shows none. */
    fun setScene(name: String?, cloud: SplatCloud?) {
        this.cloud = cloud
        _uiState.update { it.copy(sceneName = if (cloud == null) null else name.orEmpty()) }
    }

    fun onIntent(intent: ExportIntent) {
        when (intent) {
            ExportIntent.Show -> _uiState.update {
                it.copy(sheetOpen = true, status = if (it.status is ExportStatus.Working) it.status else ExportStatus.Idle)
            }
            ExportIntent.Dismiss -> _uiState.update { it.copy(sheetOpen = false) }
            is ExportIntent.Select -> _uiState.update {
                if (intent.format in it.formats && it.status !is ExportStatus.Working) it.copy(selected = intent.format) else it
            }
            is ExportIntent.SaveTo -> intent.uri?.let(::save)
            ExportIntent.Share -> share()
            ExportIntent.ShareLaunched -> _uiState.update { it.copy(share = null) }
        }
    }

    private fun save(uri: String) {
        val fileName = _uiState.value.fileName
        run(open = { target.openDocument(uri) }) { ExportStatus.Saved(fileName) }
    }

    private fun share() {
        val fileName = _uiState.value.fileName
        run(open = { target.openShareFile(fileName) }) {
            try {
                _uiState.update { it.copy(share = ShareRequest(target.shareUri(fileName), fileName)) }
                ExportStatus.Idle
            } catch (e: IllegalArgumentException) {
                ExportStatus.Failed(ExportUseCase.WRITE_ERROR)
            } catch (e: IOException) {
                ExportStatus.Failed(ExportUseCase.WRITE_ERROR)
            }
        }
    }

    private fun run(open: () -> OutputStream, onDone: () -> ExportStatus) {
        val state = _uiState.value
        val scene = cloud
        if (scene == null || !state.canExport) return
        _uiState.update { it.copy(status = ExportStatus.Working(0f)) }
        job = viewModelScope.launch {
            val result = useCase.export(scene, state.selected, open) { p ->
                _uiState.update { s -> if (s.status is ExportStatus.Working) s.copy(status = ExportStatus.Working(p)) else s }
            }
            val status = when (result) {
                ExportResult.Done -> onDone()
                is ExportResult.Failed -> ExportStatus.Failed(result.message)
            }
            _uiState.update { it.copy(status = status) }
        }
    }
}
