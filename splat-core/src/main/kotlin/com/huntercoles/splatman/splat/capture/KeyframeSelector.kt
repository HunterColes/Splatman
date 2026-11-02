package com.huntercoles.splatman.splat.capture

import com.huntercoles.splatman.splat.model.CameraPose
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.min

/**
 * Decides, frame by frame, which tracked frames become keyframes (ADR 0004) under [policy]. Not thread-safe: one
 * capture feeds it from one thread. Allocates nothing per frame; [reset] starts a new capture.
 */
class KeyframeSelector(val policy: KeyframePolicy = KeyframePolicy()) {

    private val minRotationRad = Math.toRadians(policy.minRotationDeg.toDouble())
    private val window = FloatArray(policy.sharpnessWindow)
    private val scratch = FloatArray(policy.sharpnessWindow)
    private var windowSize = 0
    private var windowNext = 0
    private var last: CameraPose? = null

    /** Keyframes accepted since construction or the last [reset]. */
    var keyframeCount: Int = 0
        private set

    /**
     * Offers one frame: [pose] is its `T_W_Cgl`, [trackingOk] whether ARCore reports TRACKING, [sharpness] its
     * [Sharpness.laplacianVariance]. Returns true when the frame is a keyframe; the caller then stores it.
     * Every tracked frame's sharpness enters the window, accepted or not; untracked frames are ignored.
     */
    fun offer(pose: CameraPose, trackingOk: Boolean, sharpness: Float): Boolean {
        if (!trackingOk) return false
        val sharpEnough = sharpness >= sharpnessThreshold()
        push(sharpness)
        if (keyframeCount >= policy.maxKeyframes) return false
        if (!sharpEnough || !movedEnough(pose)) return false
        last = pose
        keyframeCount++
        return true
    }

    /** Forgets every keyframe and the sharpness history. */
    fun reset() {
        windowSize = 0
        windowNext = 0
        last = null
        keyframeCount = 0
    }

    /**
     * The sharpness at the [KeyframePolicy.sharpnessQuantile] of the window (the value at sorted index
     * `floor(n * q)`); frames below it are in the bottom quantile. With an empty window anything passes.
     */
    private fun sharpnessThreshold(): Float {
        val n = windowSize
        if (n == 0) return Float.NEGATIVE_INFINITY
        System.arraycopy(window, 0, scratch, 0, n)
        scratch.sort(0, n)
        return scratch[min((n * policy.sharpnessQuantile).toInt(), n - 1)]
    }

    private fun push(sharpness: Float) {
        window[windowNext] = sharpness
        windowNext = (windowNext + 1) % window.size
        if (windowSize < window.size) windowSize++
    }

    private fun movedEnough(pose: CameraPose): Boolean {
        val prev = last ?: return true
        val dx = pose.translationM.x - prev.translationM.x
        val dy = pose.translationM.y - prev.translationM.y
        val dz = pose.translationM.z - prev.translationM.z
        val minT = policy.minTranslationM
        if (dx * dx + dy * dy + dz * dz >= minT * minT) return true
        return relativeAngleRad(prev, pose) >= minRotationRad
    }

    private fun relativeHalfCos(a: CameraPose, b: CameraPose): Double {
        val qa = a.rotation
        val qb = b.rotation
        val na = qa.norm().toDouble()
        val nb = qb.norm().toDouble()
        val dot = (qa.w.toDouble() * qb.w + qa.x.toDouble() * qb.x + qa.y.toDouble() * qb.y + qa.z.toDouble() * qb.z) /
            (na * nb)
        return min(1.0, abs(dot))
    }

    /** Angle of the rotation taking [a] to [b], radians in 0..pi. */
    private fun relativeAngleRad(a: CameraPose, b: CameraPose): Double = 2.0 * acos(relativeHalfCos(a, b))
}
