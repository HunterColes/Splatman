package com.huntercoles.splatman.splat.capture

import kotlin.math.min

/**
 * Decides, frame by frame, which frames become keyframes when there are no poses (#213), under [policy]. Not
 * thread-safe: one capture feeds it from one thread. Allocates nothing per frame; [reset] starts a new capture.
 * After a true the caller calls [GyroIntegrator.mark] and keeps the frame's thumbnail for the next [ImageChange].
 */
class PoseFreeKeyframeSelector(val policy: PoseFreeKeyframePolicy = PoseFreeKeyframePolicy()) {

    private val minRotationRad = Math.toRadians(policy.minRotationDeg.toDouble()).toFloat()
    private val minIntervalNs = policy.minIntervalMs * 1_000_000L
    private val window = FloatArray(policy.sharpnessWindow)
    private val scratch = FloatArray(policy.sharpnessWindow)
    private var windowSize = 0
    private var windowNext = 0
    private var hasLast = false
    private var lastNs = 0L

    /** Keyframes accepted since construction or the last [reset]. */
    var keyframeCount: Int = 0
        private set

    /**
     * Offers one frame at [timestampNs]: [rotationSinceLastRad] is [GyroIntegrator.rotationSinceMarkRad],
     * [imageChange] the [ImageChange.meanAbsDiff] against the last keyframe's thumbnail, [rateRadS]
     * [GyroIntegrator.rateRadS] and [sharpness] its [Sharpness.laplacianVariance]. Every offered frame's sharpness
     * enters the window, accepted or not. Returns true when the frame is a keyframe.
     */
    fun offer(timestampNs: Long, rotationSinceLastRad: Float, imageChange: Float, rateRadS: Float, sharpness: Float): Boolean {
        val sharpEnough = sharpness >= sharpnessThreshold()
        push(sharpness)
        if (keyframeCount >= policy.maxKeyframes) return false
        if (!sharpEnough || !(rateRadS < policy.maxRateRadS)) return false
        if (hasLast) {
            if (timestampNs - lastNs < minIntervalNs) return false
            val turned = rotationSinceLastRad >= minRotationRad
            val changed = imageChange >= policy.minImageChange
            if (!turned && !changed) return false
        }
        hasLast = true
        lastNs = timestampNs
        keyframeCount++
        return true
    }

    /** Forgets every keyframe and the sharpness history. */
    fun reset() {
        windowSize = 0
        windowNext = 0
        hasLast = false
        lastNs = 0L
        keyframeCount = 0
    }

    /** The sharpness at sorted index `floor(n * q)` of the window; with an empty window anything passes. */
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
}
