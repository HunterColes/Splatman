package com.huntercoles.splatman.capture.recording

import com.huntercoles.splatman.capture.presentation.capture.MotionMeter
import com.huntercoles.splatman.splat.math.Vec3
import com.huntercoles.splatman.splat.model.CameraPose
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2

/**
 * Room mode motion signals (ADR 0013 sections 5 and 6): the walked path, the turn sum, the spinning warning, the
 * once-per-scan walk-on hint and the nearest-surface distance. Fed every tracked frame while recording and not
 * paused. Pure JVM, no Android types. Not thread-safe.
 */
class RoomMotion {
    private val xs = FloatArray(MAX_PATH_POINTS)
    private val zs = FloatArray(MAX_PATH_POINTS)
    private var count = 0
    private var lastPoint: Vec3? = null

    private var lastFrame: Vec3? = null
    private var lastNs = 0L
    private var lastHeadingDeg: Float? = null

    private val sampleTurn = ArrayDeque<Float>()
    private val samplePath = ArrayDeque<Float>()
    private var walkOnShown = false

    /** Walked path length, metres; relocalisation jumps and tracking gaps add nothing. */
    var pathM = 0f
        private set

    /** Sum of the absolute heading changes between frames, degrees. */
    var turnDeg = 0f
        private set

    /** The path points' (x, z) in world metres, oldest first, at most [MAX_PATH_POINTS]. */
    val path: List<Pair<Float, Float>>
        get() = List(count) { xs[it] to zs[it] }

    /** True while the last half turn moved the camera less than [SPIN_PATH_M]: the user spins in place. */
    val spinning: Boolean
        get() = turnedInPlace(HALF_TURN_DEG)

    /** Takes one frame's camera pose; [tracking] is false for a frame ARCore does not track. */
    fun add(pose: CameraPose, timestampNs: Long, tracking: Boolean) {
        if (!tracking) {
            breakContinuity()
            return
        }
        val position = pose.translationM
        val previous = lastFrame
        val dtNs = timestampNs - lastNs
        val continuous = previous != null && dtNs in 1..MotionMeter.MAX_GAP_NS &&
            (position - previous).length() <= MAX_STEP_M
        if (!continuous) lastHeadingDeg = null
        lastFrame = position
        lastNs = timestampNs
        addPath(position, continuous)
        addTurn(pose)
    }

    /**
     * Returns true once per scan, on the first call after a full turn moved the camera less than [SPIN_PATH_M]:
     * time to show the walk-on hint.
     */
    fun walkOnDue(): Boolean {
        if (walkOnShown || !turnedInPlace(FULL_TURN_DEG)) return false
        walkOnShown = true
        return true
    }

    /** Forgets everything, the walk-on hint included (a new scan). */
    fun reset() {
        count = 0
        lastPoint = null
        breakContinuity()
        pathM = 0f
        turnDeg = 0f
        sampleTurn.clear()
        samplePath.clear()
        walkOnShown = false
    }

    private fun breakContinuity() {
        lastFrame = null
        lastHeadingDeg = null
    }

    private fun addPath(position: Vec3, continuous: Boolean) {
        val last = lastPoint
        if (last != null && continuous) {
            val step = (position - last).length()
            if (step < MIN_STEP_M) return
            pathM += step
        }
        appendPoint(position)
    }

    private fun appendPoint(position: Vec3) {
        if (count == MAX_PATH_POINTS) {
            // Keep every second point, the first and the last.
            var n = 0
            for (i in 0 until count - 1 step 2) {
                xs[n] = xs[i]
                zs[n] = zs[i]
                n++
            }
            xs[n] = xs[count - 1]
            zs[n] = zs[count - 1]
            count = n + 1
        }
        xs[count] = position.x
        zs[count] = position.z
        count++
        lastPoint = position
    }

    private fun addTurn(pose: CameraPose) {
        val f = pose.rotation.normalized().rotate(FORWARD)
        val pitchDeg = Math.toDegrees(asin(f.y.coerceIn(-1f, 1f)).toDouble())
        if (abs(pitchDeg) > MAX_PITCH_DEG) return
        val headingDeg = Math.toDegrees(atan2(f.x, f.z).toDouble()).toFloat()
        lastHeadingDeg?.let { turnDeg += abs(shortestDeg(headingDeg - it)) }
        lastHeadingDeg = headingDeg
        addSample()
    }

    private fun addSample() {
        if (sampleTurn.isNotEmpty() && turnDeg - sampleTurn.last() < SAMPLE_STEP_DEG) return
        sampleTurn.addLast(turnDeg)
        samplePath.addLast(pathM)
        // Keep the newest sample at least a full turn back; drop the older ones.
        while (sampleTurn.size > 1 && turnDeg - sampleTurn[1] >= FULL_TURN_DEG) dropOldest()
        while (sampleTurn.size > MAX_SAMPLES) dropOldest()
    }

    private fun dropOldest() {
        sampleTurn.removeFirst()
        samplePath.removeFirst()
    }

    /** The latest sample at least [turnBackDeg] of turn back has less than [SPIN_PATH_M] of path since. */
    private fun turnedInPlace(turnBackDeg: Float): Boolean {
        for (i in sampleTurn.indices.reversed()) {
            if (turnDeg - sampleTurn[i] >= turnBackDeg) return pathM - samplePath[i] < SPIN_PATH_M
        }
        return false
    }

    private fun shortestDeg(d: Float): Float {
        var a = d % 360f
        if (a > 180f) a -= 360f
        if (a < -180f) a += 360f
        return a
    }

    companion object {
        /** A path step needs the camera this far from the last path point, metres. */
        const val MIN_STEP_M = 0.05f

        /** A move longer than this between consecutive frames is a relocalisation jump, metres. */
        const val MAX_STEP_M = 0.5f

        /** Path points kept for the large view; halved when full. */
        const val MAX_PATH_POINTS = 1024

        /** Less path than this over a half turn is spinning in place, metres. */
        const val SPIN_PATH_M = 0.3f

        /** Frames steeper than this up or down have no useful heading, degrees. */
        const val MAX_PITCH_DEG = 80.0

        /** Turn samples kept at most. */
        const val MAX_SAMPLES = 600

        /** A new turn sample every this many degrees of turning. */
        const val SAMPLE_STEP_DEG = 1f

        /** Nearer than this to the nearest surface raises the Close warning, metres. */
        const val ROOM_CLOSE_M = 0.4f

        /** Feature points needed for a point-distance median. */
        const val MIN_POINTS = 20

        private const val HALF_TURN_DEG = 180f
        private const val FULL_TURN_DEG = 360f
        private val FORWARD = Vec3(0f, 0f, -1f)

        /**
         * Distance to the nearest surface, metres: [depthMedianM] when the frame had a depth image, else the median of
         * the first [count] of [pointDistancesM] when there are at least [MIN_POINTS], else null (no warning).
         */
        fun roomNearM(depthMedianM: Float?, pointDistancesM: FloatArray, count: Int): Float? {
            if (depthMedianM != null) return depthMedianM
            val n = count.coerceIn(0, pointDistancesM.size)
            if (n < MIN_POINTS) return null
            val sorted = pointDistancesM.copyOf(n).also { it.sort() }
            return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2f
        }
    }
}
