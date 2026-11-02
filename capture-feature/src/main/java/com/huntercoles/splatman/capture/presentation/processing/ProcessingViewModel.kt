package com.huntercoles.splatman.capture.presentation.processing

import android.content.Intent
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.huntercoles.splatman.capture.processing.NotificationAsk
import com.huntercoles.splatman.capture.processing.ProcessingRepository
import com.huntercoles.splatman.capture.processing.ProcessingStarter
import com.huntercoles.splatman.capture.processing.ProcessingState
import com.huntercoles.splatman.capture.processing.ScanDeleter
import com.huntercoles.splatman.capture.processing.ScanFolders
import com.huntercoles.splatman.core.preferences.SettingsPreferences
import com.huntercoles.splatman.viewer.export.AndroidExportTarget
import com.huntercoles.splatman.viewer.export.ExportTarget
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.IOException
import javax.inject.Inject

/**
 * The Processing route for one scan (`processing/{scanId}`): a window onto the runner. It starts the run when the
 * scan has not been processed yet; a run the app was killed in reads as failed and waits for Try again. Done shows
 * View it and Share; Back leaves the run going and, the first time, asks for notifications.
 */
@HiltViewModel
class ProcessingViewModel @Inject constructor(
    private val repository: ProcessingRepository,
    private val starter: ProcessingStarter,
    private val deleter: ScanDeleter,
    private val notificationAsk: NotificationAsk,
    private val exportTarget: ExportTarget,
    private val settings: SettingsPreferences,
    folders: ScanFolders,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val scanId: String = savedStateHandle.get<String>(ARG_SCAN_ID).orEmpty()
    private val folder = folders.folder(scanId)?.takeIf { it.isDirectory }
    private var eta = EtaEstimator()
    private var cachedFrames: List<File>? = null

    /** What the screen shows. */
    val uiState: StateFlow<ProcessingUiState> = if (folder == null) {
        MutableStateFlow(ProcessingUiState(scanId, missing = true))
    } else {
        val states = repository.state(folder)
        states.map { ui(folder, it) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, ui(folder, states.value))
    }

    private val events = Channel<ProcessingEvent>(Channel.BUFFERED)

    /** One-off actions for the screen. */
    val effects: Flow<ProcessingEvent> = events.receiveAsFlow()

    init {
        if (folder != null) {
            if (repository.state(folder).value == ProcessingState.Idle && !repository.isRunning(folder)) {
                starter.start(folder)
            }
            viewModelScope.launch {
                repository.state(folder).drop(1).first { it is ProcessingState.Done }
                if (settings.haptics.value) events.send(ProcessingEvent.Haptic)
            }
        }
    }

    /** Handles one [intent]. */
    fun onIntent(intent: ProcessingIntent) {
        when (intent) {
            ProcessingIntent.Leave -> leave()
            ProcessingIntent.NotificationAsked -> {
                notificationAsk.markAsked()
                send(ProcessingEvent.Leave)
            }
            ProcessingIntent.Open -> if (folder != null) send(ProcessingEvent.OpenViewer(scanId))
            ProcessingIntent.Retry -> folder?.let(::retry)
            ProcessingIntent.Cancel -> folder?.let {
                repository.cancel(it)
                send(ProcessingEvent.Leave)
            }
            ProcessingIntent.Share -> folder?.let(::share)
            ProcessingIntent.Delete -> if (folder != null) {
                viewModelScope.launch {
                    deleter.delete(scanId)
                    events.send(ProcessingEvent.Leave)
                }
            }
        }
    }

    private fun retry(scan: File) {
        if (repository.isRunning(scan)) return
        eta = EtaEstimator()
        starter.start(scan)
    }

    private fun leave() {
        val running = uiState.value.canCancel
        send(if (running && !notificationAsk.asked) ProcessingEvent.AskNotifications else ProcessingEvent.Leave)
    }

    private fun share(scan: File) {
        val splat = File(scan, ProcessingRepository.SPLAT_FILE)
        val name = "$scanId.ply"
        viewModelScope.launch {
            val uri = withContext(Dispatchers.IO) {
                try {
                    exportTarget.openShareFile(name).use { out -> splat.inputStream().use { it.copyTo(out) } }
                    exportTarget.shareUri(name)
                } catch (e: IOException) {
                    Timber.w(e, "share failed")
                    null
                }
            }
            if (uri != null) events.send(ProcessingEvent.Share(uri, name))
        }
    }

    private fun send(event: ProcessingEvent) {
        viewModelScope.launch { events.send(event) }
    }

    private fun ui(scan: File, state: ProcessingState): ProcessingUiState {
        val progress = state.stageProgress()
        val time = if (progress == null) {
            Eta.Unknown
        } else {
            eta.update(progress.overall, System.nanoTime() / NANOS_PER_MS, hot = state is ProcessingState.Paused)
        }
        // The frames do not change while a run goes; list them only when it is not running (a tick each iteration).
        val frames = if (progress != null && cachedFrames != null) cachedFrames else listFrames(scan)
        cachedFrames = frames
        return ProcessingUiState(
            scanId = scanId,
            state = state,
            eta = time,
            framesKept = !frames.isNullOrEmpty(),
            lastFrame = frames?.lastOrNull(),
        )
    }

    private fun listFrames(scan: File): List<File>? =
        File(scan, IMAGES_DIR).listFiles { f -> f.isFile && f.name.endsWith(".jpg") }?.sortedBy { it.name }

    companion object {
        /** The route's argument (core/navigation `NavigationDestination.Processing.scanId`). */
        const val ARG_SCAN_ID = "scanId"
        private const val IMAGES_DIR = "images"
        private const val NANOS_PER_MS = 1_000_000L

        /** The chooser for a [ProcessingEvent.Share]. */
        fun shareIntent(event: ProcessingEvent.Share): Intent = AndroidExportTarget.shareIntent(event.uri, event.fileName)
    }
}
