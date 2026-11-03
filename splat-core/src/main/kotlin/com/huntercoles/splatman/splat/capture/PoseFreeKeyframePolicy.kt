package com.huntercoles.splatman.splat.capture

/**
 * Thresholds for picking keyframes without poses (#213). A frame becomes a keyframe when it is at least
 * [minIntervalMs] after the last keyframe, the gyro rate is below [maxRateRadS], its sharpness is not in the bottom
 * [sharpnessQuantile] of the last [sharpnessWindow] frames, fewer than [maxKeyframes] were taken, and it turned at
 * least [minRotationDeg] or its image change is at least [minImageChange]. The first acceptable frame is always taken.
 */
data class PoseFreeKeyframePolicy(
    /** Minimum gyro rotation since the last keyframe, degrees. */
    val minRotationDeg: Float = 3f,
    /** Minimum [ImageChange.meanAbsDiff] against the last keyframe's thumbnail, luma levels 0..255. */
    val minImageChange: Float = 12f,
    /** Minimum time since the last keyframe, milliseconds. */
    val minIntervalMs: Int = 100,
    /** Frames taken while the device turns this fast or faster (rad/s) are rejected as likely smeared. */
    val maxRateRadS: Float = 1.5f,
    /** Number of recent frames whose sharpness forms the reference distribution. */
    val sharpnessWindow: Int = 30,
    /** Fraction of the window that counts as too blurry: 0.25 rejects the bottom quartile. */
    val sharpnessQuantile: Float = 0.25f,
    /** Most keyframes one capture takes. */
    val maxKeyframes: Int = 300,
) {
    init {
        require(minRotationDeg >= 0f && minRotationDeg.isFinite()) { "minRotationDeg must be >= 0, got $minRotationDeg" }
        require(minImageChange >= 0f && minImageChange.isFinite()) { "minImageChange must be >= 0, got $minImageChange" }
        require(minIntervalMs >= 0) { "minIntervalMs must be >= 0, got $minIntervalMs" }
        require(maxRateRadS > 0f && !maxRateRadS.isNaN()) { "maxRateRadS must be > 0, got $maxRateRadS" }
        require(sharpnessWindow >= 1) { "sharpnessWindow must be >= 1, got $sharpnessWindow" }
        require(sharpnessQuantile in 0f..1f) { "sharpnessQuantile must be in 0..1, got $sharpnessQuantile" }
        require(maxKeyframes >= 1) { "maxKeyframes must be >= 1, got $maxKeyframes" }
    }
}
