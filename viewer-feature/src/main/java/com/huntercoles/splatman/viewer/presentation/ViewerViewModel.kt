package com.huntercoles.splatman.viewer.presentation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.huntercoles.splatman.core.preferences.SettingsPreferences
import com.huntercoles.splatman.splat.io.SplatFormat
import com.huntercoles.splatman.viewer.presentation.load.LoadedScene
import com.huntercoles.splatman.viewer.presentation.load.SplatFileException
import com.huntercoles.splatman.viewer.presentation.load.SplatLoader
import com.huntercoles.splatman.viewer.presentation.load.SplatSamples
import com.huntercoles.splatman.viewer.presentation.load.SplatSource
import com.huntercoles.splatman.viewer.rendering.RoomStart
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.IOException
import javax.inject.Inject

/** What the viewer is showing. */
sealed interface ViewerLoad {
    data object Idle : ViewerLoad
    /** [reload]: a crop changed the source's file; the camera keeps its pose (docs/design/screens/crop.md). */
    data class Loading(val source: SplatSource, val reload: ViewerReload? = null) : ViewerLoad
    class Loaded(val scene: LoadedScene) : ViewerLoad

    /** [outOfMemory]: the file was too big for this phone (`view_error_memory`). */
    data class Failed(val source: SplatSource, val message: String, val outOfMemory: Boolean = false) : ViewerLoad
}

/**
 * Why the Viewer reopens its source: a crop was applied (`crop_done`), an erase was applied (`erase_done`), or the
 * edits were reverted (`crop_reverting`).
 */
enum class ViewerReload { CROPPED, REVERTED, ERASED }

/**
 * The details sheet's text: [frames] null means the splat was imported (a sample, a picked file, or a Library scan
 * with no keyframes). [room]: a scan captured in room mode, which opens from inside (docs/design/screens/viewer.md
 * "Room scans").
 */
data class ViewerDetails(
    val name: String,
    val createdAtMs: Long?,
    val splats: Int,
    val sizeBytes: Long?,
    val frames: Int?,
    val format: SplatFormat,
    val room: Boolean = false,
)

/** The rename dialog: the field's [text], clipped to [RenameRules.MAX_LENGTH]. */
data class RenameDialog(val text: String) {
    val canSave: Boolean get() = RenameRules.canSave(text)
    val showCounter: Boolean get() = RenameRules.showCounter(text)
}

data class ViewerUiState(
    val samples: List<SplatSource.Sample> = SplatSamples.ALL,
    val load: ViewerLoad = ViewerLoad.Idle,
    val showStats: Boolean = false,
    /** The top bar's name: the Library's name for a scan, else the file's. */
    val title: String = "",
    /** A scan made on this phone: it can be renamed and deleted. */
    val canEdit: Boolean = false,
    val details: ViewerDetails? = null,
    /** A room scan's start pose: the camera opens inside it ([ViewerCamera.frame]); null orbits from outside. */
    val room: RoomStart? = null,
    val chromeVisible: Boolean = true,
    val autoRotate: Boolean = false,
    val showHint: Boolean = false,
    val showDetails: Boolean = false,
    val rename: RenameDialog? = null,
    val confirmDelete: Boolean = false,
    /** The scan was deleted: the screen goes back to the Library. */
    val closed: Boolean = false,
    /** What is open (or opening). */
    val source: SplatSource? = null,
    /** A crop reload just reached Ready: keep the camera, show its snackbar, then send [ViewerIntent.ReloadShown]. */
    val reloaded: ViewerReload? = null,
    /** The import flow just opened this scan: show `imp_done` with this name, then send [ViewerIntent.ImportShown]. */
    val importDone: String? = null,
)

sealed interface ViewerIntent {
    data class Open(val source: SplatSource) : ViewerIntent
    data object Retry : ViewerIntent
    data object ToggleStats : ViewerIntent
    data object ToggleChrome : ViewerIntent
    data object ToggleAutoRotate : ViewerIntent
    data object DismissHint : ViewerIntent
    data object ShowDetails : ViewerIntent
    data object HideDetails : ViewerIntent
    data object StartRename : ViewerIntent
    data class RenameChanged(val text: String) : ViewerIntent
    data object SaveRename : ViewerIntent
    data object CancelRename : ViewerIntent
    data object AskDelete : ViewerIntent
    data object CancelDelete : ViewerIntent
    data object ConfirmDelete : ViewerIntent

    /** Crop mode opens: auto-rotate stops (and stays off), the hint goes, the chrome shows. */
    data object EnterCrop : ViewerIntent

    /** A crop changed the source's file: open it again through Loading, keeping the camera. */
    data class Reload(val reason: ViewerReload) : ViewerIntent

    /** The screen showed [ViewerUiState.reloaded]. */
    data object ReloadShown : ViewerIntent

    /** The screen showed [ViewerUiState.importDone]. */
    data object ImportShown : ViewerIntent
}

/**
 * Opens a sample or a picked file through [SplatLoader] (off the main thread) and holds the result. The route's
 * `scanId` ([ARG_SCAN_ID]) opens at once: a bundled sample (`sample:<file>`) or a scan's `splat.ply`. When
 * [ARG_IMPORTED] is set (the import flow opened it), the first load that reaches Ready carries `imp_done` once.
 * A new open cancels the one in flight; leaving the screen clears the ViewModel, which cancels any load still running.
 * It also holds the chrome (bars shown, auto-rotate, the one-time hint) and the scan's details, rename and delete.
 */
@HiltViewModel
class ViewerViewModel @Inject constructor(
    private val loader: SplatLoader,
    private val scans: ViewerScanStore,
    private val hints: ViewerHintStore,
    private val settings: SettingsPreferences,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ViewerUiState())
    val uiState: StateFlow<ViewerUiState> = _uiState.asStateFlow()

    private val _ready = Channel<SplatSource>(Channel.CONFLATED)

    /** Each source that reached Ready, once (crop mode reads `canRevert` from it; a rotation does not repeat it). */
    val ready: Flow<SplatSource> = _ready.receiveAsFlow()

    /** Settings > Haptics. */
    val haptics: Boolean get() = settings.haptics.value

    /** The camera, kept here so a rotation keeps its pose. */
    val camera = ViewerCamera()

    private var loadJob: Job? = null
    private var lastSource: SplatSource? = null

    init {
        val scanId = savedStateHandle.get<String>(ARG_SCAN_ID)
        (SplatSamples.fromScanId(scanId) ?: SplatSource.Scan.fromScanId(scanId))?.let(::open)
    }

    fun onIntent(intent: ViewerIntent) {
        when (intent) {
            is ViewerIntent.Open -> open(intent.source)
            ViewerIntent.Retry -> lastSource?.let(::open)
            ViewerIntent.ToggleStats -> _uiState.update { it.copy(showStats = !it.showStats) }
            ViewerIntent.ToggleChrome -> _uiState.update { it.copy(chromeVisible = !it.chromeVisible) }
            ViewerIntent.ToggleAutoRotate -> _uiState.update { it.copy(autoRotate = !it.autoRotate) }
            ViewerIntent.DismissHint -> dismissHint()
            ViewerIntent.ShowDetails -> _uiState.update { it.copy(showDetails = it.details != null) }
            ViewerIntent.HideDetails -> _uiState.update { it.copy(showDetails = false) }
            ViewerIntent.StartRename -> _uiState.update {
                if (it.canEdit) it.copy(rename = RenameDialog(RenameRules.clip(it.title))) else it
            }
            is ViewerIntent.RenameChanged -> _uiState.update {
                if (it.rename == null) it else it.copy(rename = RenameDialog(RenameRules.clip(intent.text)))
            }
            ViewerIntent.SaveRename -> saveRename()
            ViewerIntent.CancelRename -> _uiState.update { it.copy(rename = null) }
            ViewerIntent.AskDelete -> _uiState.update { it.copy(confirmDelete = it.canEdit) }
            ViewerIntent.CancelDelete -> _uiState.update { it.copy(confirmDelete = false) }
            ViewerIntent.ConfirmDelete -> confirmDelete()
            ViewerIntent.EnterCrop -> enterCrop()
            is ViewerIntent.Reload -> lastSource?.let { open(it, intent.reason) }
            ViewerIntent.ReloadShown -> _uiState.update { it.copy(reloaded = null) }
            ViewerIntent.ImportShown -> _uiState.update { it.copy(importDone = null) }
        }
    }

    private fun dismissHint() {
        if (!_uiState.value.showHint) return
        if (_uiState.value.room != null) hints.markRoomHintSeen() else hints.markHintSeen()
        _uiState.update { it.copy(showHint = false) }
    }

    private fun enterCrop() {
        dismissHint()
        _uiState.update { it.copy(autoRotate = false, chromeVisible = true, showDetails = false) }
    }

    private fun saveRename() {
        val state = _uiState.value
        val dialog = state.rename ?: return
        val scanId = (lastSource as? SplatSource.Scan)?.scanId ?: return
        if (!dialog.canSave) return
        val name = dialog.text.trim()
        _uiState.update { it.copy(rename = null, title = name, details = it.details?.copy(name = name)) }
        viewModelScope.launch {
            try {
                scans.rename(scanId, name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                Timber.w(e, "Could not rename %s", scanId)
            }
        }
    }

    private fun confirmDelete() {
        val scanId = (lastSource as? SplatSource.Scan)?.scanId
        if (scanId == null || !_uiState.value.confirmDelete) return
        _uiState.update { it.copy(confirmDelete = false) }
        viewModelScope.launch {
            try {
                scans.delete(scanId)
                _uiState.update { it.copy(closed = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                Timber.w(e, "Could not delete %s", scanId)
            }
        }
    }

    private fun open(source: SplatSource, reload: ViewerReload? = null) {
        loadJob?.cancel()
        lastSource = source
        _uiState.update {
            it.copy(
                load = ViewerLoad.Loading(source, reload),
                title = if (reload != null) it.title else source.name,
                source = source,
                reloaded = null,
                canEdit = false,
                details = null,
                room = if (reload != null) it.room else null,
                showDetails = false,
                rename = null,
                confirmDelete = false,
            )
        }
        loadJob = viewModelScope.launch {
            val next = try {
                ViewerLoad.Loaded(loader.load(source))
            } catch (e: CancellationException) {
                throw e
            } catch (e: SplatFileException) {
                ViewerLoad.Failed(source, e.message ?: GENERIC_ERROR)
            } catch (e: IOException) {
                Timber.w(e, "Could not read %s", source)
                ViewerLoad.Failed(source, "The file could not be read.")
            } catch (e: IllegalArgumentException) {
                Timber.w(e, "Invalid splat file %s", source)
                ViewerLoad.Failed(source, "Not a valid splat file.")
            } catch (e: SecurityException) {
                Timber.w(e, "No access to %s", source)
                ViewerLoad.Failed(source, "Splatman may not read this file.")
            } catch (e: OutOfMemoryError) {
                Timber.w(e, "Out of memory loading %s", source)
                ViewerLoad.Failed(source, "Not enough memory to open this file.", outOfMemory = true)
            }
            val record = if (next is ViewerLoad.Loaded && source is SplatSource.Scan) record(source.scanId) else null
            _uiState.update { it.mapLoad(source, next, record, reload) }
            if (next is ViewerLoad.Loaded) markImportDone()
            if (next is ViewerLoad.Loaded) _ready.send(source)
        }
    }

    /** Once per import: a retry, a crop reload or a recreated ViewModel does not show it again. */
    private fun markImportDone() {
        if (savedStateHandle.get<Boolean>(ARG_IMPORTED) != true) return
        savedStateHandle[ARG_IMPORTED] = false
        _uiState.update { it.copy(importDone = it.title) }
    }

    private suspend fun record(scanId: String): ScanRecord? = try {
        scans.get(scanId)
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        Timber.w(e, "No library row for %s", scanId)
        null
    }

    private fun ViewerUiState.mapLoad(
        source: SplatSource,
        next: ViewerLoad,
        record: ScanRecord?,
        reload: ViewerReload?,
    ): ViewerUiState {
        if (next !is ViewerLoad.Loaded) return copy(load = next, title = source.name, room = null)
        val scene = next.scene
        // Only a scan made on this phone has a bundle; samples and picked files have no mode and orbit as before.
        val room = record?.room?.takeIf { source is SplatSource.Scan }
        val name = record?.name ?: scene.name
        return copy(
            load = next,
            title = name,
            canEdit = source is SplatSource.Scan && record != null,
            details = ViewerDetails(
                name = name,
                createdAtMs = record?.createdAtMs,
                splats = scene.count,
                sizeBytes = record?.sizeBytes ?: scene.sizeBytes,
                // An imported Library file has a row with no keyframes (ScanRecord has no imported flag): it shows
                // "Imported from <format>", like a sample, not "Built from 0 frames" (#402).
                frames = record?.keyframeCount?.takeIf { source is SplatSource.Scan && it > 0 },
                format = scene.format,
                room = room != null,
            ),
            room = room,
            // A room scan has its own hint, shown once apart from the object hint (viewer.md "Room scans").
            showHint = if (room != null) !hints.roomHintSeen else !hints.hintSeen,
            reloaded = reload,
        )
    }

    companion object {
        /** The viewer route's argument (core/navigation `NavigationDestination.Viewer.scanId`). */
        const val ARG_SCAN_ID = "scanId"

        /** The viewer route's flag (`NavigationDestination.Viewer.imported`): the import flow opened this scan. */
        const val ARG_IMPORTED = "imported"
        private const val GENERIC_ERROR = "The file could not be opened."
    }
}
