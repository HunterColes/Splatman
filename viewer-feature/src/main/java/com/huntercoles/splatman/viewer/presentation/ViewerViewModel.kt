package com.huntercoles.splatman.viewer.presentation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.huntercoles.splatman.viewer.presentation.load.LoadedScene
import com.huntercoles.splatman.viewer.presentation.load.SplatFileException
import com.huntercoles.splatman.viewer.presentation.load.SplatLoader
import com.huntercoles.splatman.viewer.presentation.load.SplatSamples
import com.huntercoles.splatman.viewer.presentation.load.SplatSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.IOException
import javax.inject.Inject

/** What the viewer is showing. */
sealed interface ViewerLoad {
    data object Idle : ViewerLoad
    data class Loading(val source: SplatSource) : ViewerLoad
    class Loaded(val scene: LoadedScene) : ViewerLoad
    data class Failed(val source: SplatSource, val message: String) : ViewerLoad
}

data class ViewerUiState(
    val samples: List<SplatSource.Sample> = SplatSamples.ALL,
    val load: ViewerLoad = ViewerLoad.Idle,
    val showStats: Boolean = true,
)

sealed interface ViewerIntent {
    data class Open(val source: SplatSource) : ViewerIntent
    data object Retry : ViewerIntent
    data object ToggleStats : ViewerIntent
}

/**
 * Opens a sample or a picked file through [SplatLoader] (off the main thread) and holds the result. The route's
 * `scanId` ([ARG_SCAN_ID]) opens at once when it names a bundled sample (`sample:<file>`); scan ids wait for #18.
 * A new open cancels the one in flight; leaving the screen clears the ViewModel, which cancels any load still running.
 */
@HiltViewModel
class ViewerViewModel @Inject constructor(
    private val loader: SplatLoader,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ViewerUiState())
    val uiState: StateFlow<ViewerUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null
    private var lastSource: SplatSource? = null

    init {
        SplatSamples.fromScanId(savedStateHandle.get<String>(ARG_SCAN_ID))?.let(::open)
    }

    fun onIntent(intent: ViewerIntent) {
        when (intent) {
            is ViewerIntent.Open -> open(intent.source)
            ViewerIntent.Retry -> lastSource?.let(::open)
            ViewerIntent.ToggleStats -> _uiState.update { it.copy(showStats = !it.showStats) }
        }
    }

    private fun open(source: SplatSource) {
        loadJob?.cancel()
        lastSource = source
        _uiState.update { it.copy(load = ViewerLoad.Loading(source)) }
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
                ViewerLoad.Failed(source, "Not enough memory to open this file.")
            }
            _uiState.update { it.copy(load = next) }
        }
    }

    companion object {
        /** The viewer route's argument (core/navigation `NavigationDestination.Viewer.scanId`). */
        const val ARG_SCAN_ID = "scanId"
        private const val GENERIC_ERROR = "The file could not be opened."
    }
}
