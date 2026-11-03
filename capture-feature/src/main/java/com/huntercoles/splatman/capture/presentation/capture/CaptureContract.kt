package com.huntercoles.splatman.capture.presentation.capture

import com.huntercoles.splatman.capture.recording.CoverageCells
import com.huntercoles.splatman.capture.recording.RecorderState
import com.huntercoles.splatman.capture.tracking.PoseSourceStatus
import com.huntercoles.splatman.capture.tracking.TrackingAvailability
import com.huntercoles.splatman.capture.tracking.TrackingCheck
import com.huntercoles.splatman.capture.tracking.TrackingFailure
import com.huntercoles.splatman.capture.tracking.TrackingState

/** What the user or the screen's lifecycle did. */
sealed interface CaptureIntent {
    /** ON_RESUME (true) or ON_PAUSE and leaving the screen (false). */
    data class Foreground(val inForeground: Boolean) : CaptureIntent

    /** The answer to the CAMERA permission request. */
    data class PermissionResult(val granted: Boolean) : CaptureIntent

    /** The answer of an ARCore install or update request. */
    data class InstallResult(val check: TrackingCheck) : CaptureIntent

    /** Try to open the tracker again (after a failure, a refused permission or a check that could not answer). */
    data object Retry : CaptureIntent

    /** The preview view is [width] x [height] pixels (for the tap ray and the reticle). */
    data class ViewSize(val width: Int, val height: Int) : CaptureIntent

    /** A tap on the preview at ([x], [y]) view pixels: sets the subject before recording. */
    data class Tap(val x: Float, val y: Float) : CaptureIntent

    /** Start recording a scan (round the tapped subject, else what is in the middle). */
    data object Record : CaptureIntent

    /** Pause recording: frames are no longer kept. */
    data object Pause : CaptureIntent

    /** Resume a paused recording. */
    data object Resume : CaptureIntent

    /** Finish: stop recording and save the scan. */
    data object Stop : CaptureIntent

    /** Stop recording (or saving) and keep nothing. */
    data object Cancel : CaptureIntent

    /** Close or system Back: asks first when frames were captured, else leaves. */
    data object Close : CaptureIntent

    /** The discard dialog's Keep scanning. */
    data object KeepScanning : CaptureIntent

    /** The discard dialog's Discard: keeps nothing and leaves. */
    data object Discard : CaptureIntent
}

/** Where the live capture is (docs/design/screens/capture.md, States). */
enum class CaptureStage { STARTING, FINDING_SURFACES, AIM, CAPTURING, PAUSED, FINISHING }

/** The status pill at the top. */
enum class StatusPill { STARTING, TRACKING, LIMITED, LOST, PAUSED }

/** A haptic the screen plays (Settings > Haptics on). */
enum class CaptureHaptic { CONFIRM, REJECT }

/** The one line telling the user how tracking goes. */
enum class TrackingHint {
    /** Tracking well. */
    TRACKING,

    /** Starting up: "looking for surfaces". */
    LOOKING_FOR_SURFACES,

    /** Moving too fast: "move slower". */
    MOVE_SLOWER,

    /** Too dark: "more light". */
    MORE_LIGHT,

    /** Too little texture: aim at something with more detail. */
    MORE_DETAIL,

    /** Another app holds the camera. */
    CAMERA_IN_USE,

    /** Tracking stopped or broke. */
    TRACKING_LOST,
}

/** Which screen the capture route shows. */
sealed interface CapturePhase {
    /** Checking ARCore and the permission. */
    data object Checking : CapturePhase

    /** This phone cannot run ARCore; [reason] is the raw answer (the Details line). */
    data class Unsupported(val reason: String) : CapturePhase

    /** ARCore is supported but missing or too old: offer the install; [reason] is the raw answer. */
    data class InstallNeeded(val reason: String) : CapturePhase

    /** The ARCore check failed or timed out: offer Try again; [reason] is the raw answer or the exception. */
    data class CouldntCheck(val reason: String) : CapturePhase

    /** CAMERA not granted yet: the screen asks. */
    data object PermissionNeeded : CapturePhase

    /** The user refused CAMERA. */
    data object PermissionDenied : CapturePhase

    /** The live camera with the tracking [hint]; [paused] while in the background. */
    data class Preview(val hint: TrackingHint, val paused: Boolean) : CapturePhase

    /** The tracker failed to open or broke; [reason] is the error text. */
    data class Failed(val reason: String) : CapturePhase
}

/**
 * The capture screen's state. [installPending]: an ARCore install was started; check again on resume. [recorder]:
 * the recording, with its live keyframe count and coverage. The rest is the live guidance overlay: [instruction] is
 * null while a [warning] replaces it; [reticle] is the subject's view position (null: the centre at half alpha);
 * [facingSegment] is the coverage segment the phone is in; [ready] unlocks Finish.
 */
data class CaptureUiState(
    val phase: CapturePhase = CapturePhase.Checking,
    val installPending: Boolean = false,
    val recorder: RecorderState = RecorderState.Idle,
    val stage: CaptureStage = CaptureStage.STARTING,
    val status: StatusPill = StatusPill.STARTING,
    val instruction: Instruction? = Instruction.STARTING,
    val warning: GuidanceWarning? = null,
    val subjectSet: Boolean = false,
    val reticle: ScreenPoint? = null,
    val cells: CoverageCells = CoverageCells(),
    val facingSegment: Int = 0,
    val ready: Boolean = false,
    val elapsedMs: Long = 0,
    val framesKept: Int = 0,
    val confirmDiscard: Boolean = false,
)

/** The stage for the [recorder], whether the user [paused], whether any frame came yet and the latest [hint]. */
fun captureStage(recorder: RecorderState, paused: Boolean, hasFrame: Boolean, hint: TrackingHint): CaptureStage =
    when {
        recorder is RecorderState.Saving || recorder is RecorderState.Saved -> CaptureStage.FINISHING
        recorder is RecorderState.Recording -> if (paused) CaptureStage.PAUSED else CaptureStage.CAPTURING
        !hasFrame -> CaptureStage.STARTING
        hint == TrackingHint.TRACKING -> CaptureStage.AIM
        else -> CaptureStage.FINDING_SURFACES
    }

/** The status pill for [stage] and the latest tracking [hint]. */
fun statusPill(stage: CaptureStage, hint: TrackingHint): StatusPill = when {
    stage == CaptureStage.STARTING -> StatusPill.STARTING
    stage == CaptureStage.PAUSED -> StatusPill.PAUSED
    hint == TrackingHint.TRACKING -> StatusPill.TRACKING
    hint == TrackingHint.TRACKING_LOST || hint == TrackingHint.CAMERA_IN_USE -> StatusPill.LOST
    else -> StatusPill.LIMITED
}

/** The instruction line for [stage]; null while a [warning] replaces it or while finishing. */
fun instructionFor(
    stage: CaptureStage,
    cells: CoverageCells,
    warning: GuidanceWarning?,
    recorder: RecorderState,
): Instruction? = when (stage) {
    CaptureStage.STARTING -> Instruction.STARTING
    CaptureStage.FINDING_SURFACES -> Instruction.FIND_SURFACE
    CaptureStage.AIM -> when (recorder) {
        RecorderState.NothingRecorded -> Instruction.NOTHING_RECORDED
        is RecorderState.Failed -> Instruction.SAVE_FAILED
        else -> Instruction.AIM
    }
    CaptureStage.CAPTURING -> if (warning != null) null else capturingInstruction(cells)
    CaptureStage.PAUSED -> if (cells.ready) Instruction.READY else Instruction.PAUSED
    CaptureStage.FINISHING -> null
}

/** The hint for one frame's tracking [state] and [failure]. */
fun trackingHint(state: TrackingState, failure: TrackingFailure): TrackingHint = when (failure) {
    TrackingFailure.NONE -> when (state) {
        TrackingState.TRACKING -> TrackingHint.TRACKING
        TrackingState.PAUSED -> TrackingHint.LOOKING_FOR_SURFACES
        TrackingState.STOPPED -> TrackingHint.TRACKING_LOST
    }
    TrackingFailure.BAD_STATE -> TrackingHint.TRACKING_LOST
    TrackingFailure.INSUFFICIENT_LIGHT -> TrackingHint.MORE_LIGHT
    TrackingFailure.EXCESSIVE_MOTION -> TrackingHint.MOVE_SLOWER
    TrackingFailure.INSUFFICIENT_FEATURES -> TrackingHint.MORE_DETAIL
    TrackingFailure.CAMERA_UNAVAILABLE -> TrackingHint.CAMERA_IN_USE
}

/** The phase for the tracker's [status], whether the user refused CAMERA, and the latest frame's [hint]. */
fun capturePhase(status: PoseSourceStatus, permissionDenied: Boolean, hint: TrackingHint): CapturePhase =
    when (status) {
        PoseSourceStatus.Idle -> CapturePhase.Checking
        is PoseSourceStatus.Unavailable -> when (status.availability) {
            TrackingAvailability.UNSUPPORTED -> CapturePhase.Unsupported(status.reason)
            TrackingAvailability.INSTALL_NEEDED -> CapturePhase.InstallNeeded(status.reason)
            TrackingAvailability.COULDNT_CHECK -> CapturePhase.CouldntCheck(status.reason)
            TrackingAvailability.SUPPORTED -> CapturePhase.Checking
        }
        PoseSourceStatus.NeedsPermission ->
            if (permissionDenied) CapturePhase.PermissionDenied else CapturePhase.PermissionNeeded
        PoseSourceStatus.Running -> CapturePhase.Preview(hint, paused = false)
        PoseSourceStatus.Paused -> CapturePhase.Preview(hint, paused = true)
        is PoseSourceStatus.Failed -> CapturePhase.Failed(status.reason)
    }
