package com.huntercoles.splatman.capture.presentation.processing

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.huntercoles.splatman.capture.processing.ProcessingRepository
import com.huntercoles.splatman.capture.processing.ProcessingStarter
import com.huntercoles.splatman.capture.processing.ProcessingState
import com.huntercoles.splatman.capture.processing.ScanFolders
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The Processing route for one scan (`processing/{scanId}`): shows the runner's state, starts the run when the scan
 * has not been processed yet, and once the splat is written names the scan on [openViewer], once. A run the app was
 * killed in reads as failed (ProcessingRepository's marker) and waits for Retry; it never restarts by itself.
 */
@HiltViewModel
class ProcessingViewModel @Inject constructor(
    private val repository: ProcessingRepository,
    private val starter: ProcessingStarter,
    folders: ScanFolders,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val scanId: String = savedStateHandle.get<String>(ARG_SCAN_ID).orEmpty()
    private val folder = folders.folder(scanId)?.takeIf { it.isDirectory }

    /** What the screen shows. */
    val uiState: StateFlow<ProcessingUiState> = if (folder == null) {
        MutableStateFlow(ProcessingUiState(scanId, missing = true))
    } else {
        val states = repository.state(folder)
        states.map { ProcessingUiState(scanId, it) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, ProcessingUiState(scanId, states.value))
    }

    private val done = Channel<String>(Channel.BUFFERED)

    /** The scan id once its splat is ready: the hand-off to the Viewer route. */
    val openViewer: Flow<String> = done.receiveAsFlow()

    init {
        if (folder != null) {
            if (repository.state(folder).value == ProcessingState.Idle && !repository.isRunning(folder)) {
                starter.start(folder)
            }
            viewModelScope.launch {
                repository.state(folder).first { it is ProcessingState.Done }
                done.send(scanId)
            }
        }
    }

    /** Handles one [intent]. */
    fun onIntent(intent: ProcessingIntent) {
        val scan = folder ?: return
        when (intent) {
            ProcessingIntent.Retry -> if (!repository.isRunning(scan)) starter.start(scan)
            ProcessingIntent.Cancel -> repository.cancel(scan)
        }
    }

    companion object {
        /** The route's argument (core/navigation `NavigationDestination.Processing.scanId`). */
        const val ARG_SCAN_ID = "scanId"
    }
}
