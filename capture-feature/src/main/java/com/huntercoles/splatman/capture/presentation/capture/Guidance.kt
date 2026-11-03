package com.huntercoles.splatman.capture.presentation.capture

import com.huntercoles.splatman.capture.recording.Coverage
import com.huntercoles.splatman.capture.recording.CoverageBand
import com.huntercoles.splatman.capture.recording.CoverageCells
import com.huntercoles.splatman.capture.tracking.TrackingFailure
import com.huntercoles.splatman.capture.tracking.TrackingState
import com.huntercoles.splatman.core.design.SplatMotion
import com.huntercoles.splatman.splat.model.CameraPose
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos

/** A live capture warning, highest priority first (docs/design/screens/capture.md, Warning priority). */
enum class GuidanceWarning { LOST, DARK, FAST, CLOSE, FAR }

/**
 * Picks the one warning to show from the ones live each frame: the highest-priority live one, except that a shown
 * warning stays at least [minShowMs] (only a higher one replaces it sooner), and a warning that was hidden waits
 * [cooldownMs] before it can show again. Times are milliseconds on any one monotonic clock. Not thread-safe.
 */
class WarningArbiter(
    private val minShowMs: Long = SplatMotion.GuidanceMinShow,
    private val cooldownMs: Long = SplatMotion.GuidanceCooldown,
) {
    private var current: GuidanceWarning? = null
    private var shownAtMs = 0L
    private val hiddenAtMs = LongArray(GuidanceWarning.entries.size) { NEVER }

    /** The warning shown now, or null. */
    val shown: GuidanceWarning? get() = current

    /** Takes the warnings [live] at [nowMs] and returns the one to show, or null. */
    fun update(live: Set<GuidanceWarning>, nowMs: Long): GuidanceWarning? {
        val showing = current
        if (showing != null) {
            val higher = live.filter { it < showing && canShow(it, nowMs) }.minOrNull()
            if (higher != null) {
                hide(showing, nowMs)
                show(higher, nowMs)
                return higher
            }
            if (showing in live || nowMs - shownAtMs < minShowMs) return showing
            hide(showing, nowMs)
        }
        live.filter { canShow(it, nowMs) }.minOrNull()?.let { show(it, nowMs) }
        return current
    }

    /** Hides any warning and forgets every cooldown. */
    fun reset() {
        current = null
        hiddenAtMs.fill(NEVER)
    }

    private fun canShow(w: GuidanceWarning, nowMs: Long): Boolean {
        val hidden = hiddenAtMs[w.ordinal]
        return hidden == NEVER || nowMs - hidden >= cooldownMs
    }

    private fun show(w: GuidanceWarning, nowMs: Long) {
        current = w
        shownAtMs = nowMs
    }

    private fun hide(w: GuidanceWarning, nowMs: Long) {
        hiddenAtMs[w.ordinal] = nowMs
        current = null
    }

    private companion object {
        const val NEVER = Long.MIN_VALUE
    }
}

/** What one frame says about how the capture goes, the inputs of [liveWarnings]. */
data class FrameSignals(
    val trackingState: TrackingState,
    val failure: TrackingFailure,
    /** Smoothed camera rotation speed, degrees per second. */
    val rotationDegPerS: Float = 0f,
    /** Smoothed camera speed, metres per second. */
    val speedMPerS: Float = 0f,
    /** Mean luma 0..255 of a recent frame, or null when none was measured. */
    val meanLuma: Float? = null,
    /** Camera to subject, metres, or null without a subject. */
    val subjectDistanceM: Float? = null,
)

/** Thresholds of [liveWarnings]. */
object GuidanceLimits {
    /** Turning faster than this blurs frames, degrees per second. */
    const val MAX_ROTATION_DEG_PER_S = 75f

    /** Moving faster than this blurs frames, metres per second. */
    const val MAX_SPEED_M_PER_S = 1f

    /** A mean luma below this is too dark. */
    const val MIN_MEAN_LUMA = 40f

    /** Nearer than this to the subject is too close, metres. */
    const val MIN_DISTANCE_M = 0.2f

    /** Farther than this from the subject is too far, metres. */
    const val MAX_DISTANCE_M = 2.5f
}

/** The warnings live for [s]: ARCore's own failure reasons first, then motion, light and distance. */
fun liveWarnings(s: FrameSignals): Set<GuidanceWarning> {
    val out = mutableSetOf<GuidanceWarning>()
    if (s.trackingState != TrackingState.TRACKING) {
        when (s.failure) {
            TrackingFailure.INSUFFICIENT_LIGHT -> out += GuidanceWarning.DARK
            TrackingFailure.EXCESSIVE_MOTION -> out += GuidanceWarning.FAST
            else -> out += GuidanceWarning.LOST
        }
    }
    if (s.rotationDegPerS > GuidanceLimits.MAX_ROTATION_DEG_PER_S || s.speedMPerS > GuidanceLimits.MAX_SPEED_M_PER_S) {
        out += GuidanceWarning.FAST
    }
    s.meanLuma?.let { if (it < GuidanceLimits.MIN_MEAN_LUMA) out += GuidanceWarning.DARK }
    s.subjectDistanceM?.let {
        if (it < GuidanceLimits.MIN_DISTANCE_M) out += GuidanceWarning.CLOSE
        if (it > GuidanceLimits.MAX_DISTANCE_M) out += GuidanceWarning.FAR
    }
    return out
}

/**
 * Camera rotation and translation speed from consecutive poses, smoothed so one jittery frame does not raise a
 * warning. Gaps longer than [MAX_GAP_NS] restart it. Not thread-safe.
 */
class MotionMeter {
    private var lastPose: CameraPose? = null
    private var lastNs = 0L

    /** Smoothed rotation speed, degrees per second. */
    var rotationDegPerS = 0f
        private set

    /** Smoothed speed, metres per second. */
    var speedMPerS = 0f
        private set

    /** Takes the pose of a tracked frame at [timestampNs]. */
    fun add(pose: CameraPose, timestampNs: Long) {
        val last = lastPose
        val dtNs = timestampNs - lastNs
        lastPose = pose
        lastNs = timestampNs
        if (last == null || dtNs <= 0 || dtNs > MAX_GAP_NS) {
            if (last != null) reset(pose, timestampNs)
            return
        }
        val dt = dtNs / 1e9f
        val a = last.rotation.normalized()
        val b = pose.rotation.normalized()
        val dot = abs(a.w * b.w + a.x * b.x + a.y * b.y + a.z * b.z).coerceAtMost(1f)
        val angleDeg = (2 * acos(dot) * 180 / PI).toFloat()
        val distance = (pose.translationM - last.translationM).length()
        rotationDegPerS += SMOOTHING * (angleDeg / dt - rotationDegPerS)
        speedMPerS += SMOOTHING * (distance / dt - speedMPerS)
    }

    /** Forgets everything. */
    fun reset() {
        lastPose = null
        rotationDegPerS = 0f
        speedMPerS = 0f
    }

    private fun reset(pose: CameraPose, timestampNs: Long) {
        reset()
        lastPose = pose
        lastNs = timestampNs
    }

    companion object {
        /** Weight of the newest frame. */
        const val SMOOTHING = 0.3f

        /** A gap this long (0.5 s) restarts the meter. */
        const val MAX_GAP_NS = 500_000_000L
    }
}

/** The one instruction line under the reticle. */
enum class Instruction {
    STARTING, FIND_SURFACE, AIM, WALK, GO_HIGHER, GO_LOWER, PAUSED, READY,

    /** Back to aiming after a Finish that kept no frame. */
    NOTHING_RECORDED,

    /** Back to aiming after a save that failed. */
    SAVE_FAILED,

    /** Room mode, before recording: stand in the room and press record. */
    ROOM_AIM,

    /** Room mode: walk round the room facing the walls. */
    ROOM_WALK,

    /** Room mode: the walls are seen, now the ceiling. */
    ROOM_LOOK_UP,

    /** Room mode: the walls and ceiling are seen, now the floor. */
    ROOM_LOOK_DOWN,

    /** Room mode: a full turn on the spot; walk on to the next part of the room. */
    ROOM_WALK_ON,

    /** Room mode: the keyframe budget is used up; Finish. */
    ROOM_FULL,
}

/**
 * The capturing instruction for [cells]: walk until one band is done ([Coverage.BAND_DONE]), then go higher while a
 * band above the highest done one is missing, then lower while one below is, else keep walking; [Instruction.READY]
 * once the threshold is met.
 */
fun capturingInstruction(cells: CoverageCells): Instruction {
    if (cells.ready) return Instruction.READY
    val done = CoverageBand.entries.filter { cells.count(it) >= Coverage.BAND_DONE }
    val top = done.maxOrNull() ?: return Instruction.WALK
    return when {
        CoverageBand.entries.any { it > top && it !in done } -> Instruction.GO_HIGHER
        CoverageBand.entries.any { it !in done } -> Instruction.GO_LOWER
        else -> Instruction.WALK
    }
}
