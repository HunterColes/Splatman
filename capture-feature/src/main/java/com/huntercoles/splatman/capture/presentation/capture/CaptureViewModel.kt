package com.huntercoles.splatman.capture.presentation.capture

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.huntercoles.splatman.capture.preview.PreviewTarget
import com.huntercoles.splatman.capture.recording.CaptureRecorder
import com.huntercoles.splatman.capture.recording.Coverage
import com.huntercoles.splatman.capture.recording.CoverageCells
import com.huntercoles.splatman.capture.recording.RecorderState
import com.huntercoles.splatman.capture.tracking.FeaturePoints
import com.huntercoles.splatman.capture.tracking.PoseSourceStatus
import com.huntercoles.splatman.capture.tracking.TrackedFrame
import com.huntercoles.splatman.capture.tracking.TrackingAvailability
import com.huntercoles.splatman.capture.tracking.TrackingState
import com.huntercoles.splatman.core.preferences.SettingsPreferences
import com.huntercoles.splatman.splat.math.Vec3
import com.huntercoles.splatman.splat.model.CameraIntrinsics
import com.huntercoles.splatman.splat.model.CameraPose
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The capture screen: opens the tracker, turns its status and each frame's tracking state into [uiState], and runs
 * the live guidance (docs/design/screens/capture.md): the subject tap, the coverage ring, one warning at a time
 * through a [WarningArbiter], pause, the gated Finish and the discard confirm. Every frame goes to the [recorder],
 * which keeps it only while recording, not paused and with no warning live. Pauses the tracker when the screen leaves
 * the foreground and releases both when the screen is gone. Once a scan is saved, [openProcessing] names it, once;
 * [leave] asks the screen to go back; [haptics] plays only with Settings > Haptics on.
 */
@HiltViewModel
class CaptureViewModel @Inject constructor(
    private val tracker: CaptureTracker,
    private val recorder: CaptureRecorder,
    private val settings: SettingsPreferences,
) : ViewModel() {
    private val permissionDenied = MutableStateFlow(false)
    private val installPending = MutableStateFlow(false)
    private val hint = MutableStateFlow(TrackingHint.LOOKING_FOR_SURFACES)
    private val live = MutableStateFlow(Live())

    /** The guidance's own state, beside the tracker's and the recorder's. */
    private data class Live(
        val hasFrame: Boolean = false,
        val paused: Boolean = false,
        val subjectSet: Boolean = false,
        val reticle: ScreenPoint? = null,
        val warning: GuidanceWarning? = null,
        val cells: CoverageCells = CoverageCells(),
        val facingSegment: Int = 0,
        val elapsedMs: Long = 0,
        val confirmDiscard: Boolean = false,
    )

    private val base = combine(tracker.status, permissionDenied, hint, installPending, recorder.state) {
            status, denied, latest, pending, recording ->
        CaptureUiState(phase = capturePhase(status, denied, latest), installPending = pending, recorder = recording)
    }

    /** What the screen shows. */
    val uiState: StateFlow<CaptureUiState> = combine(base, hint, live) { b, h, l ->
        val stage = captureStage(b.recorder, l.paused, l.hasFrame, h)
        val cells = (b.recorder as? RecorderState.Recording)?.cells ?: l.cells
        b.copy(
            stage = stage,
            status = statusPill(stage, h),
            instruction = instructionFor(stage, cells, l.warning, b.recorder),
            warning = if (stage == CaptureStage.CAPTURING) l.warning else null,
            subjectSet = l.subjectSet,
            reticle = l.reticle,
            cells = cells,
            facingSegment = l.facingSegment,
            ready = cells.ready && (stage == CaptureStage.CAPTURING || stage == CaptureStage.PAUSED),
            elapsedMs = l.elapsedMs,
            framesKept = when (val r = b.recorder) {
                is RecorderState.Recording -> r.keyframes
                is RecorderState.Saving -> r.keyframes
                is RecorderState.Saved -> r.keyframes
                else -> 0
            },
            confirmDiscard = l.confirmDiscard,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, CaptureUiState())

    private val savedScans = Channel<String>(Channel.BUFFERED)
    private val leaveRequests = Channel<Unit>(Channel.CONFLATED)
    private val hapticEvents = Channel<CaptureHaptic>(Channel.BUFFERED)

    /** The id of each scan as it is saved: the hand-off to the Processing route. */
    val openProcessing: Flow<String> = savedScans.receiveAsFlow()

    /** The screen should go back (Close with nothing captured, or Discard). */
    val leave: Flow<Unit> = leaveRequests.receiveAsFlow()

    /** Haptics to play now. */
    val haptics: Flow<CaptureHaptic> = hapticEvents.receiveAsFlow()

    // Main thread only, like every intent and frame.
    private val arbiter = WarningArbiter()
    private val motion = MotionMeter()
    private var subject: Vec3? = null
    private var latest: Latest? = null
    private var viewWidth = 0
    private var viewHeight = 0
    private var lastFrameNs = 0L
    private var elapsedNs = 0L
    private var wasReady = false

    /** What the tap and the reticle need of the latest frame (its image is closed by then). */
    private class Latest(val pose: CameraPose, val k: CameraIntrinsics, val points: FeaturePoints, val tracking: Boolean)

    init {
        addCloseable { tracker.release() }
        addCloseable { recorder.release() }
        viewModelScope.launch { tracker.frames.collect(::onFrame) }
        viewModelScope.launch {
            recorder.state.collect { state ->
                when (state) {
                    is RecorderState.Saved -> savedScans.send(state.scanId)
                    is RecorderState.Recording -> {
                        live.update { it.copy(cells = state.cells) }
                        if (state.cells.ready && !wasReady) {
                            wasReady = true
                            haptic(CaptureHaptic.CONFIRM)
                        }
                    }
                    else -> Unit
                }
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
            is CaptureIntent.ViewSize -> {
                viewWidth = intent.width
                viewHeight = intent.height
            }
            is CaptureIntent.Tap -> if (!recorder.isRecording) setSubject(ScreenPoint(intent.x, intent.y))
            CaptureIntent.Record -> record()
            CaptureIntent.Pause -> if (recorder.isRecording) {
                arbiter.reset()
                live.update { it.copy(paused = true, warning = null) }
            }
            CaptureIntent.Resume -> if (recorder.isRecording) {
                motion.reset()
                live.update { it.copy(paused = false) }
            }
            CaptureIntent.Stop -> if (recorder.isRecording) {
                recorder.stop()
                haptic(CaptureHaptic.CONFIRM)
                live.update { it.copy(paused = false, warning = null) }
            }
            CaptureIntent.Cancel -> cancel()
            CaptureIntent.Close -> {
                val captured = uiState.value.framesKept > 0 && recorder.isRecording
                if (captured) {
                    live.update { it.copy(confirmDiscard = true) }
                } else {
                    if (recorder.isRecording) cancel()
                    leaveRequests.trySend(Unit)
                }
            }
            CaptureIntent.KeepScanning -> live.update { it.copy(confirmDiscard = false) }
            CaptureIntent.Discard -> {
                cancel()
                leaveRequests.trySend(Unit)
            }
        }
    }

    /** The surface to draw the camera into, or null before it is destroyed (join the job before returning). */
    fun setPreview(target: PreviewTarget?): Job = tracker.setPreview(target)

    private fun start() {
        viewModelScope.launch { tracker.start() }
    }

    private fun record() {
        if (subject == null) mapping()?.let { setSubject(it.centre) }
        if (!recorder.start(subject)) return
        arbiter.reset()
        motion.reset()
        elapsedNs = 0
        wasReady = false
        live.update { it.copy(paused = false, warning = null, cells = CoverageCells(), elapsedMs = 0) }
        haptic(CaptureHaptic.CONFIRM)
    }

    private fun cancel() {
        recorder.cancel()
        arbiter.reset()
        live.update { it.copy(paused = false, warning = null, confirmDiscard = false, cells = CoverageCells()) }
    }

    /** The subject under view point [p] of the latest tracked frame. */
    private fun setSubject(p: ScreenPoint) {
        val frame = latest?.takeIf { it.tracking } ?: return
        val mapping = mapping() ?: return
        val picked = pickSubject(frame.pose.translationM, mapping.ray(frame.pose, p), frame.points)
        subject = picked
        live.update {
            it.copy(
                subjectSet = true,
                reticle = mapping.project(frame.pose, picked),
                facingSegment = Coverage.segmentOf(frame.pose.translationM, picked),
            )
        }
    }

    private fun mapping(): DisplayMapping? {
        val frame = latest ?: return null
        if (viewWidth <= 0 || viewHeight <= 0) return null
        return DisplayMapping(viewWidth.toFloat(), viewHeight.toFloat(), frame.k)
    }

    private fun onFrame(frame: TrackedFrame) {
        hint.value = trackingHint(frame.trackingState, frame.trackingFailure)
        val tracking = frame.trackingState == TrackingState.TRACKING
        latest = Latest(frame.pose, frame.intrinsics, frame.points, tracking)
        val now = frame.timestampNs
        val paused = live.value.paused
        var warning: GuidanceWarning? = null
        val recording = recorder.isRecording
        if (recording) {
            val dt = now - lastFrameNs
            if (!paused && lastFrameNs != 0L && dt in 1..MAX_TICK_NS) elapsedNs += dt
            if (tracking) motion.add(frame.pose, now) else motion.reset()
            val signals = FrameSignals(
                trackingState = frame.trackingState,
                failure = frame.trackingFailure,
                rotationDegPerS = motion.rotationDegPerS,
                speedMPerS = motion.speedMPerS,
                meanLuma = recorder.meanLuma.value,
                subjectDistanceM = subject?.takeIf { tracking }?.let { (frame.pose.translationM - it).length() },
            )
            if (!paused) {
                val before = arbiter.shown
                warning = arbiter.update(liveWarnings(signals), now / NS_PER_MS)
                if (warning != null && warning != before) haptic(CaptureHaptic.REJECT)
            }
        }
        lastFrameNs = now
        val s = subject
        val mapping = mapping()
        live.update {
            it.copy(
                hasFrame = true,
                warning = warning,
                reticle = if (s != null && tracking && mapping != null) mapping.project(frame.pose, s) else it.reticle,
                facingSegment = if (s != null && tracking) {
                    Coverage.segmentOf(frame.pose.translationM, s)
                } else {
                    it.facingSegment
                },
                elapsedMs = elapsedNs / NS_PER_MS,
            )
        }
        // It closes the frame, now or once recorded; it keeps none while paused or warned.
        recorder.submit(frame, keep = !paused && warning == null)
    }

    private fun haptic(h: CaptureHaptic) {
        if (settings.haptics.value) hapticEvents.trySend(h)
    }

    private companion object {
        const val NS_PER_MS = 1_000_000L

        /** A longer gap between frames (the app was in the background) does not count as capture time. */
        const val MAX_TICK_NS = 200_000_000L
    }
}
