package com.huntercoles.splatman.capture.presentation.capture

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.huntercoles.splatman.capture.preview.PreviewTarget
import com.huntercoles.splatman.capture.recording.CaptureRecorder
import com.huntercoles.splatman.capture.recording.Coverage
import com.huntercoles.splatman.capture.recording.CoverageCells
import com.huntercoles.splatman.capture.recording.PoseFreeRecorder
import com.huntercoles.splatman.capture.recording.PoseFreeState
import com.huntercoles.splatman.capture.recording.RecorderState
import com.huntercoles.splatman.capture.tracking.CameraPermission
import com.huntercoles.splatman.capture.tracking.FeaturePoints
import com.huntercoles.splatman.capture.tracking.FlavourTracking
import com.huntercoles.splatman.capture.tracking.PoseSourceStatus
import com.huntercoles.splatman.capture.tracking.TrackedFrame
import com.huntercoles.splatman.capture.tracking.TrackingAvailability
import com.huntercoles.splatman.capture.tracking.TrackingCheck
import com.huntercoles.splatman.capture.tracking.TrackingState
import com.huntercoles.splatman.core.preferences.SettingsPreferences
import com.huntercoles.splatman.splat.math.Vec3
import com.huntercoles.splatman.splat.model.CameraIntrinsics
import com.huntercoles.splatman.splat.model.CameraPose
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
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
import java.util.Optional
import javax.inject.Inject

/** What a pose-free capture asks of the phone before it opens the camera, and its clock. */
interface PoseFreeAccess {
    /** True when CAMERA is granted. */
    fun cameraGranted(): Boolean

    /** Whether this phone has a back camera to scan with (UNSUPPORTED: none). */
    suspend fun check(): TrackingCheck

    /** A monotonic clock for the capture time, in nanoseconds. */
    fun nowNs(): Long
}

/** [PoseFreeAccess] on the app's context: the CAMERA grant, the flavour's camera check, the elapsed-realtime clock. */
@Module
@InstallIn(ViewModelComponent::class)
object PoseFreeAccessModule {
    @Provides
    fun poseFreeAccess(@ApplicationContext context: Context): PoseFreeAccess = object : PoseFreeAccess {
        private val support = FlavourTracking.support(context)
        override fun cameraGranted(): Boolean = CameraPermission.isGranted(context)
        override suspend fun check(): TrackingCheck = support.check()
        override fun nowNs(): Long = SystemClock.elapsedRealtimeNanos()
    }
}

/** No pose-free capture: the ARCore path never asks it. */
private object NoPoseFreeAccess : PoseFreeAccess {
    override fun cameraGranted(): Boolean = false
    override suspend fun check(): TrackingCheck =
        TrackingCheck(TrackingAvailability.UNSUPPORTED, "no pose-free recorder")
    override fun nowNs(): Long = System.nanoTime()
}

/**
 * The capture screen: opens the tracker, turns its status and each frame's tracking state into [uiState], and runs
 * the live guidance (docs/design/screens/capture.md): the subject tap, the coverage ring, one warning at a time
 * through a [WarningArbiter], pause, the gated Finish and the discard confirm. Every frame goes to the [recorder],
 * which keeps it only while recording, not paused and with no warning live. Pauses the tracker when the screen leaves
 * the foreground and releases both when the screen is gone. Once a scan is saved, [openProcessing] names it, once;
 * [leave] asks the screen to go back; [haptics] plays only with Settings > Haptics on.
 *
 * With a [PoseFreeRecorder] (the foss build, #219) the tracker and [recorder] are never started: the pose-free
 * recorder opens the camera once [access] allows it, and its state drives the screen (keyframes, gyro coverage, the
 * DARK and FAST warnings). No subject tap and no reticle; a Stop with no keyframe says so; a saved scan goes to
 * Processing as above.
 */
@HiltViewModel
class CaptureViewModel @Inject constructor(
    private val tracker: CaptureTracker,
    private val recorder: CaptureRecorder,
    private val settings: SettingsPreferences,
    poseFree: Optional<PoseFreeRecorder> = Optional.empty(),
    private val access: PoseFreeAccess = NoPoseFreeAccess,
) : ViewModel() {
    /** The pose-free recorder (foss), or null on the ARCore path. */
    private val poseFreeRecorder: PoseFreeRecorder? = poseFree.orElse(null)

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

    // Pose-free only: the camera's status (as the tracker's would be) and how the last recording ended.
    private val poseFreeStatus = MutableStateFlow<PoseSourceStatus>(PoseSourceStatus.Idle)
    private val poseFreeOutcome = MutableStateFlow<RecorderState?>(null)

    /** What the screen shows. */
    val uiState: StateFlow<CaptureUiState> = (poseFreeRecorder?.let(::poseFreeUiState) ?: trackedUiState()).stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        CaptureUiState(poseFree = poseFreeRecorder != null),
    )

    private fun trackedUiState(): Flow<CaptureUiState> = combine(base, hint, live) { b, h, l ->
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
    }

    private fun poseFreeUiState(pf: PoseFreeRecorder): Flow<CaptureUiState> =
        combine(poseFreeStatus, permissionDenied, pf.state, poseFreeOutcome, live) { status, denied, s, outcome, l ->
            val shown = poseFreeRecorderState(s, outcome)
            val stage = poseFreeStage(s, l.paused)
            val cells = (s as? PoseFreeState.Recording)?.cells ?: l.cells
            CaptureUiState(
                phase = capturePhase(status, denied, TrackingHint.TRACKING),
                recorder = shown,
                stage = stage,
                status = poseFreePill(stage),
                instruction = instructionFor(stage, cells, l.warning, shown),
                warning = if (stage == CaptureStage.CAPTURING) l.warning else null,
                cells = cells,
                ready = cells.ready && (stage == CaptureStage.CAPTURING || stage == CaptureStage.PAUSED),
                elapsedMs = l.elapsedMs,
                framesKept = when (shown) {
                    is RecorderState.Recording -> shown.keyframes
                    is RecorderState.Saving -> shown.keyframes
                    is RecorderState.Saved -> shown.keyframes
                    else -> 0
                },
                confirmDiscard = l.confirmDiscard,
                poseFree = true,
            )
        }

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

    // Pose-free, main thread only.
    private var opening: Job? = null
    private var inTake = false
    private var stopping = false
    private var lastKeyframes = 0
    private var foreground = true
    private var lastTickNs = 0L

    /** Recording now, paused or not: the pose-free take, or the ARCore recorder's. */
    private val isRecording: Boolean get() = if (poseFreeRecorder != null) inTake else recorder.isRecording

    /** What the tap and the reticle need of the latest frame (its image is closed by then). */
    private class Latest(val pose: CameraPose, val k: CameraIntrinsics, val points: FeaturePoints, val tracking: Boolean)

    init {
        addCloseable { tracker.release() }
        addCloseable { recorder.release() }
        val pf = poseFreeRecorder
        if (pf != null) {
            addCloseable { pf.release() }
            viewModelScope.launch { pf.state.collect(::onPoseFreeState) }
        } else {
            initTracked()
        }
        start()
    }

    private fun initTracked() {
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
    }

    /** Handles one [intent]. */
    fun onIntent(intent: CaptureIntent) {
        when (intent) {
            is CaptureIntent.Foreground -> {
                setForeground(intent.inForeground)
                // Back from the settings app or the store: the permission or ARCore may be there now.
                if (intent.inForeground && needsRecheck(status())) start()
            }
            is CaptureIntent.PermissionResult -> {
                permissionDenied.value = !intent.granted
                if (intent.granted) start()
            }
            is CaptureIntent.InstallResult -> {
                val pending = intent.check.availability == TrackingAvailability.INSTALL_NEEDED
                installPending.value = pending
                if (!pending) start()
            }
            CaptureIntent.Retry -> start()
            is CaptureIntent.ViewSize -> {
                viewWidth = intent.width
                viewHeight = intent.height
            }
            // Pose-free: no subject to pick, the whole frame is the scan.
            is CaptureIntent.Tap -> if (poseFreeRecorder == null && !recorder.isRecording) {
                setSubject(ScreenPoint(intent.x, intent.y))
            }
            CaptureIntent.Record -> poseFreeRecorder?.let(::recordPoseFree) ?: record()
            CaptureIntent.Pause -> if (isRecording) {
                poseFreeRecorder?.pause(true)
                arbiter.reset()
                live.update { it.copy(paused = true, warning = null) }
            }
            CaptureIntent.Resume -> if (isRecording) {
                poseFreeRecorder?.pause(false)
                motion.reset()
                live.update { it.copy(paused = false) }
            }
            CaptureIntent.Stop -> if (isRecording) {
                stop()
                haptic(CaptureHaptic.CONFIRM)
                live.update { it.copy(paused = false, warning = null) }
            }
            CaptureIntent.Cancel -> cancel()
            CaptureIntent.Close -> {
                val captured = uiState.value.framesKept > 0 && isRecording
                if (captured) {
                    live.update { it.copy(confirmDiscard = true) }
                } else {
                    if (isRecording) cancel()
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
    fun setPreview(target: PreviewTarget?): Job = poseFreeRecorder?.setPreview(target) ?: tracker.setPreview(target)

    private fun start() {
        val pf = poseFreeRecorder
        if (pf == null) {
            viewModelScope.launch { tracker.start() }
            return
        }
        if (opening?.isActive == true) return
        opening = viewModelScope.launch { openPoseFree(pf) }
    }

    private fun setForeground(inForeground: Boolean) {
        val pf = poseFreeRecorder
        if (pf == null) {
            tracker.setForeground(inForeground)
            return
        }
        foreground = inForeground
        lastTickNs = 0L // time in the background is not capture time
        pf.setForeground(inForeground)
        poseFreeStatus.update { s ->
            when {
                s == PoseSourceStatus.Running && !inForeground -> PoseSourceStatus.Paused
                s == PoseSourceStatus.Paused && inForeground -> PoseSourceStatus.Running
                else -> s
            }
        }
    }

    private fun status(): PoseSourceStatus =
        if (poseFreeRecorder != null) poseFreeStatus.value else tracker.status.value

    /** A status that a resume should check again: no permission yet, or no usable ARCore yet. */
    private fun needsRecheck(status: PoseSourceStatus): Boolean =
        status == PoseSourceStatus.NeedsPermission || status is PoseSourceStatus.Unavailable

    /** Checks the permission and the camera, then opens the pose-free recorder (again after a refusal or failure). */
    private suspend fun openPoseFree(pf: PoseFreeRecorder) {
        if (!access.cameraGranted()) {
            poseFreeStatus.value = PoseSourceStatus.NeedsPermission
            return
        }
        val check = access.check()
        if (check.availability == TrackingAvailability.UNSUPPORTED) {
            poseFreeStatus.value = PoseSourceStatus.Unavailable(TrackingAvailability.UNSUPPORTED, check.reason)
            return
        }
        poseFreeStatus.value = PoseSourceStatus.Idle
        poseFreeStatus.value = if (pf.open()) {
            if (foreground) PoseSourceStatus.Running else PoseSourceStatus.Paused
        } else {
            PoseSourceStatus.Failed((pf.state.value as? PoseFreeState.Failed)?.reason ?: CAMERA_DID_NOT_OPEN)
        }
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

    private fun recordPoseFree(pf: PoseFreeRecorder) {
        if (!pf.start()) {
            // The camera went (lost mid-recording): show the failure, whose Try again opens it again.
            (pf.state.value as? PoseFreeState.Failed)?.let { poseFreeStatus.value = PoseSourceStatus.Failed(it.reason) }
            return
        }
        inTake = true
        stopping = false
        lastKeyframes = 0
        lastTickNs = 0
        poseFreeOutcome.value = null
        arbiter.reset()
        elapsedNs = 0
        wasReady = false
        live.update { it.copy(paused = false, warning = null, cells = CoverageCells(), elapsedMs = 0) }
        haptic(CaptureHaptic.CONFIRM)
    }

    private fun stop() {
        val pf = poseFreeRecorder
        if (pf == null) {
            recorder.stop()
            return
        }
        inTake = false
        stopping = true
        pf.stop()
    }

    private fun cancel() {
        val pf = poseFreeRecorder
        if (pf != null) {
            inTake = false
            stopping = false
            poseFreeOutcome.value = null
            pf.cancel()
        } else {
            recorder.cancel()
        }
        arbiter.reset()
        live.update { it.copy(paused = false, warning = null, confirmDiscard = false, cells = CoverageCells()) }
    }

    private suspend fun onPoseFreeState(state: PoseFreeState) {
        when (state) {
            is PoseFreeState.Recording -> if (inTake) onPoseFreeFrame(state)
            is PoseFreeState.Saved -> {
                stopping = false
                savedScans.send(state.scanId)
            }
            is PoseFreeState.Failed -> onPoseFreeFailed(state.reason)
            else -> Unit
        }
    }

    private fun onPoseFreeFailed(reason: String) {
        when {
            stopping -> {
                stopping = false
                poseFreeOutcome.value =
                    if (lastKeyframes == 0) RecorderState.NothingRecorded else RecorderState.Failed(reason)
            }
            inTake -> {
                // The camera was lost or a write failed while recording; a Record then shows which.
                inTake = false
                arbiter.reset()
                live.update { it.copy(paused = false, warning = null, confirmDiscard = false) }
                poseFreeOutcome.value = RecorderState.Failed(reason)
            }
            poseFreeStatus.value == PoseSourceStatus.Running || poseFreeStatus.value == PoseSourceStatus.Paused ->
                poseFreeStatus.value = PoseSourceStatus.Failed(reason)
            else -> Unit
        }
    }

    private fun onPoseFreeFrame(state: PoseFreeState.Recording) {
        val now = access.nowNs()
        val paused = live.value.paused
        // Updates come once per processed frame, which can be far apart on a slow phone: count every gap since the
        // last one; pausing and the background restart the count (lastTickNs = 0), so their time is never added.
        val dt = now - lastTickNs
        if (!paused && lastTickNs != 0L && dt > 0) elapsedNs += dt
        lastTickNs = if (paused) 0L else now
        lastKeyframes = state.keyframes
        var warning: GuidanceWarning? = null
        if (!paused) {
            val before = arbiter.shown
            warning = arbiter.update(state.warnings, now / NS_PER_MS)
            if (warning != null && warning != before) haptic(CaptureHaptic.REJECT)
        }
        if (state.cells.ready && !wasReady) {
            wasReady = true
            haptic(CaptureHaptic.CONFIRM)
        }
        live.update {
            it.copy(hasFrame = true, warning = warning, cells = state.cells, elapsedMs = elapsedNs / NS_PER_MS)
        }
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

        /** The Failed reason when the pose-free recorder gave none. */
        const val CAMERA_DID_NOT_OPEN = "the camera did not open"
    }
}

/** What the screen shows of the pose-free recorder's [state], or how the last recording ended ([outcome]). */
internal fun poseFreeRecorderState(state: PoseFreeState, outcome: RecorderState?): RecorderState = when (state) {
    is PoseFreeState.Recording ->
        RecorderState.Recording(state.keyframes, state.coverage, 0, state.droppedFrames, state.cells)
    is PoseFreeState.Saving -> RecorderState.Saving(state.keyframes)
    is PoseFreeState.Saved -> RecorderState.Saved(state.scanId, state.keyframes)
    else -> outcome ?: RecorderState.Idle
}

/** The stage of a pose-free capture: no Finding surfaces, the camera is ready to aim once it is open. */
internal fun poseFreeStage(state: PoseFreeState, paused: Boolean): CaptureStage = when (state) {
    PoseFreeState.Idle, PoseFreeState.Opening -> CaptureStage.STARTING
    is PoseFreeState.Previewing, is PoseFreeState.Failed -> CaptureStage.AIM
    is PoseFreeState.Recording -> if (paused) CaptureStage.PAUSED else CaptureStage.CAPTURING
    is PoseFreeState.Saving, is PoseFreeState.Saved -> CaptureStage.FINISHING
}

/** The pose-free status pill: Starting, Ready, Recording or Paused, never a tracking one. */
internal fun poseFreePill(stage: CaptureStage): StatusPill = when (stage) {
    CaptureStage.STARTING -> StatusPill.STARTING
    CaptureStage.PAUSED -> StatusPill.PAUSED
    CaptureStage.CAPTURING, CaptureStage.FINISHING -> StatusPill.RECORDING
    CaptureStage.AIM, CaptureStage.FINDING_SURFACES -> StatusPill.READY
}
