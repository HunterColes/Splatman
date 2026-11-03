package com.huntercoles.splatman.capture.presentation.capture

import com.huntercoles.splatman.capture.recording.Coverage
import com.huntercoles.splatman.capture.recording.CoverageBand
import com.huntercoles.splatman.capture.recording.CoverageCells
import com.huntercoles.splatman.capture.recording.RecorderState
import com.huntercoles.splatman.capture.recording.RoomCells
import com.huntercoles.splatman.capture.recording.RoomCoverage
import com.huntercoles.splatman.capture.tracking.PoseSourceStatus
import com.huntercoles.splatman.capture.tracking.TrackingAvailability
import com.huntercoles.splatman.capture.tracking.TrackingCheck
import com.huntercoles.splatman.capture.tracking.TrackingFailure
import com.huntercoles.splatman.capture.tracking.TrackingState
import com.huntercoles.splatman.splat.capture.PoseFreeKeyframePolicy
import com.huntercoles.splatman.splat.capture.ScanMode
import kotlin.math.abs

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

    /**
     * The Object/Room switch: taken only while no recording is under way (Aim), so never once a frame is kept, and
     * remembered as the next scan's mode. Ignored otherwise.
     */
    data class SelectMode(val mode: ScanMode) : CaptureIntent
}

/** Where the live capture is (docs/design/screens/capture.md, States). */
enum class CaptureStage { STARTING, FINDING_SURFACES, AIM, CAPTURING, PAUSED, FINISHING }

/**
 * The status pill at the top. TRACKING, LIMITED and LOST are ARCore's; a pose-free capture (foss) never tracks, so it
 * shows READY (camera open, not recording) and RECORDING instead (docs/design/screens/capture.md, Pose-free mode).
 */
enum class StatusPill { STARTING, TRACKING, LIMITED, LOST, PAUSED, READY, RECORDING }

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
 * [facingSegment] is the coverage segment the phone is in; [ready] unlocks Finish. [poseFree]: the scan records
 * without tracking (foss, PoseFreeRecorder present): no subject tap or reticle, the pose-free aim copy, the READY and
 * RECORDING pills, and Finish usable as soon as recording starts (filled once [ready]).
 *
 * Room mode (pose-free only, ADR 0015 section 5): [mode] is the scan's mode; [modeSwitchVisible] while the switch can
 * change it (no recording under way, so false from the first kept frame until the next scan); [roomCells] the room
 * gauge in Room mode (null in Object mode): on the pose-free path only Seen cells, from the gyro coverage, never
 * Covered, and [roomPercent] is Seen / [Coverage.CELLS]; [noGauge] when the phone has no gyroscope (hide the gauge);
 * [budgetReached] once the keyframe budget is used up ([PoseFreeRoom.BUDGET]), which also unlocks Finish.
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
    val poseFree: Boolean = false,
    val mode: ScanMode = ScanMode.OBJECT,
    val modeSwitchVisible: Boolean = true,
    val roomCells: RoomCells? = null,
    val roomPercent: Int = 0,
    val noGauge: Boolean = false,
    val budgetReached: Boolean = false,
)

/**
 * The pose-free Room Finish rule and guidance (ADR 0015 section 5): without poses only gyro Seen cells exist, so
 * Finish unlocks at [READY_SEEN] Seen cells with [WALLS_SEEN] of them LEVEL and [MIN_KEYFRAMES] kept frames, at
 * [MIN_KEYFRAMES] alone on a phone without a gyroscope, and always at the keyframe [BUDGET].
 */
object PoseFreeRoom {
    /** Seen cells of the 72 that unlock Finish (70 %, as Room's Covered rule asks of 29 Covered plus walls). */
    const val READY_SEEN = 51

    /** Of them, Seen LEVEL (wall) cells. */
    const val WALLS_SEEN = RoomCoverage.WALLS_DONE

    /** Kept frames before Finish unlocks, whatever the gauge says. */
    const val MIN_KEYFRAMES = 120

    /** The pose-free keyframe budget: at it nothing more is kept, the instruction is ROOM_FULL and Finish unlocks. */
    val BUDGET: Int = PoseFreeKeyframePolicy().maxKeyframes

    /** Seen cells per band before the guidance moves on (walls, then ceiling, then floor). */
    const val STEP_BAND = RoomCoverage.STEP_BAND

    /** Gyro yaw, one way, after which ROOM_WALK_ON shows again. */
    const val TURN_DEG = 360f

    /** How long ROOM_WALK_ON stays up, milliseconds. */
    const val WALK_ON_SHOW_MS = 3_000L

    /** True once [keyframes] reached the budget. */
    fun budgetReached(keyframes: Int): Boolean = keyframes >= BUDGET

    /** True once Finish unlocks for [seen] cells and [keyframes] kept frames, with or without a gyroscope. */
    fun ready(seen: CoverageCells, keyframes: Int, hasGyroscope: Boolean): Boolean = budgetReached(keyframes) ||
        keyframes >= MIN_KEYFRAMES &&
        (!hasGyroscope || seen.total >= READY_SEEN && seen.count(CoverageBand.LEVEL) >= WALLS_SEEN)

    /** The next step while not ready: walls until [STEP_BAND] are Seen, then the ceiling, then the floor. */
    fun step(seen: CoverageCells, hasGyroscope: Boolean): Instruction = when {
        !hasGyroscope -> Instruction.ROOM_WALK
        seen.count(CoverageBand.LEVEL) < STEP_BAND -> Instruction.ROOM_WALK
        seen.count(CoverageBand.HIGH) < STEP_BAND -> Instruction.ROOM_LOOK_UP
        seen.count(CoverageBand.LOW) < STEP_BAND -> Instruction.ROOM_LOOK_DOWN
        else -> Instruction.ROOM_WALK
    }
}

/** What the Room instruction line needs of the pose-free recording beside the stage. */
data class RoomProgress(
    val seen: CoverageCells,
    val keyframes: Int,
    val hasGyroscope: Boolean,
    val walkOn: Boolean,
) {
    val budgetReached: Boolean get() = PoseFreeRoom.budgetReached(keyframes)
    val ready: Boolean get() = PoseFreeRoom.ready(seen, keyframes, hasGyroscope)
}

/** The Room instruction line for [stage]; null while a [warning] replaces it or while finishing. */
fun roomInstructionFor(
    stage: CaptureStage,
    progress: RoomProgress,
    warning: GuidanceWarning?,
    recorder: RecorderState,
): Instruction? = when (stage) {
    CaptureStage.STARTING -> Instruction.STARTING
    CaptureStage.FINDING_SURFACES -> Instruction.FIND_SURFACE
    CaptureStage.AIM -> when (recorder) {
        RecorderState.NothingRecorded -> Instruction.NOTHING_RECORDED
        is RecorderState.Failed -> Instruction.SAVE_FAILED
        else -> Instruction.ROOM_AIM
    }
    CaptureStage.CAPTURING -> when {
        warning != null -> null
        progress.budgetReached -> Instruction.ROOM_FULL
        progress.ready -> Instruction.READY
        progress.walkOn -> Instruction.ROOM_WALK_ON
        else -> PoseFreeRoom.step(progress.seen, progress.hasGyroscope)
    }
    CaptureStage.PAUSED -> when {
        progress.budgetReached -> Instruction.ROOM_FULL
        progress.ready -> Instruction.READY
        else -> Instruction.PAUSED
    }
    CaptureStage.FINISHING -> null
}

/**
 * Counts full turns of gyro yaw from the coverage ring's facing segment, frame by frame: each step to a neighbouring
 * segment adds or takes off 360 / [Coverage.SEGMENTS] degrees (the shorter way round), so looking left and right
 * cancels out. A turn completes once the net yaw is a further [PoseFreeRoom.TURN_DEG], either way, from where the last
 * one completed (or the start). Not thread-safe.
 */
class YawTurns {
    private var last: Int? = null
    private var steps = 0
    private var anchor = 0

    /** Whole turns so far. */
    var turns: Int = 0
        private set

    /** Takes the facing [segment] of the latest frame; true when it completes a further turn. */
    fun add(segment: Int): Boolean {
        val n = Coverage.SEGMENTS
        val prev = last
        last = segment
        if (prev == null) return false
        steps += (segment - prev + n + n / 2).mod(n) - n / 2
        if (abs(steps - anchor) < n) return false
        anchor = steps
        turns++
        return true
    }

    /** Forgets the yaw. */
    fun reset() {
        last = null
        steps = 0
        anchor = 0
        turns = 0
    }
}

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
