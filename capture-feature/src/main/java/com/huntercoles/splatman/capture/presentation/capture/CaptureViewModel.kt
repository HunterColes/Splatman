package com.huntercoles.splatman.capture.presentation.capture

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.huntercoles.splatman.capture.preview.PreviewTarget
import com.huntercoles.splatman.capture.recording.CaptureRecorder
import com.huntercoles.splatman.capture.tracking.PoseSourceStatus
import com.huntercoles.splatman.capture.tracking.TrackingAvailability
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The capture screen: opens the tracker, turns its status and each frame's tracking state into [uiState], hands
 * every frame to the [recorder] (Record, Stop, Cancel), pauses the tracker when the screen leaves the foreground and
 * releases both when the screen is gone.
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

    init {
        addCloseable { tracker.release() }
        addCloseable { recorder.release() }
        viewModelScope.launch {
            tracker.frames.collect { frame ->
                hint.value = trackingHint(frame.trackingState, frame.trackingFailure)
                recorder.submit(frame) // it closes the frame, now or once recorded
            }
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
