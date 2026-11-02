package com.huntercoles.splatman.capture.presentation.capture

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.huntercoles.splatman.capture.preview.PreviewTarget
import com.huntercoles.splatman.capture.recording.CaptureRecorder
import com.huntercoles.splatman.capture.recording.RecorderState
import com.huntercoles.splatman.capture.tracking.PoseSourceStatus
import com.huntercoles.splatman.capture.tracking.TrackingAvailability
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The capture screen: opens the tracker, turns its status and each frame's tracking state into [uiState], hands
 * every frame to the [recorder] (Record, Stop, Cancel), pauses the tracker when the screen leaves the foreground and
 * releases both when the screen is gone. Once a scan is saved, [openProcessing] names it, once, so the screen hands it
 * to Processing.
 */
@HiltViewModel
class CaptureViewModel @Inject constructor(
    private val tracker: CaptureTracker,
    private val recorder: CaptureRecorder,
) : ViewModel() {
    private val permissionDenied = MutableStateFlow(false)
    private val installPending = MutableStateFlow(false)
    private val hint = MutableStateFlow(TrackingHint.LOOKING_FOR_SURFACES)

    /** What the screen shows. */
    val uiState: StateFlow<CaptureUiState> =
        combine(tracker.status, permissionDenied, hint, installPending, recorder.state) {
                status, denied, latest, pending, recording ->
            CaptureUiState(
                phase = capturePhase(status, denied, latest),
                installPending = pending,
                recorder = recording,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, CaptureUiState())

    private val savedScans = Channel<String>(Channel.BUFFERED)

    /** The id of each scan as it is saved: the hand-off to the Processing route. */
    val openProcessing: Flow<String> = savedScans.receiveAsFlow()

    init {
        addCloseable { tracker.release() }
        addCloseable { recorder.release() }
        viewModelScope.launch {
            tracker.frames.collect { frame ->
                hint.value = trackingHint(frame.trackingState, frame.trackingFailure)
                recorder.submit(frame) // it closes the frame, now or once recorded
            }
        }
        viewModelScope.launch {
            recorder.state.collect { state -> if (state is RecorderState.Saved) savedScans.send(state.scanId) }
        }
        start()
    }

    /** Handles one [intent]. */
    fun onIntent(intent: CaptureIntent) {
        when (intent) {
            is CaptureIntent.Foreground -> {
                tracker.setForeground(intent.inForeground)
                // Back from the settings app: the permission may have been granted there.
                if (intent.inForeground && tracker.status.value == PoseSourceStatus.NeedsPermission) start()
            }
            is CaptureIntent.PermissionResult -> {
                permissionDenied.value = !intent.granted
                if (intent.granted) start()
            }
            is CaptureIntent.InstallResult -> {
                val pending = intent.availability == TrackingAvailability.INSTALL_NEEDED
                installPending.value = pending
                if (!pending) start()
            }
            CaptureIntent.Retry -> start()
            CaptureIntent.Record -> recorder.start()
            CaptureIntent.Stop -> recorder.stop()
            CaptureIntent.Cancel -> recorder.cancel()
        }
    }

    /** The surface to draw the camera into, or null before it is destroyed (join the job before returning). */
    fun setPreview(target: PreviewTarget?): Job = tracker.setPreview(target)

    private fun start() {
        viewModelScope.launch { tracker.start() }
    }
}
