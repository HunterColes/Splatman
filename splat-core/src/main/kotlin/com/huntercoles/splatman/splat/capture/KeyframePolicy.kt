package com.huntercoles.splatman.splat.capture

/**
 * The keyframe thresholds of ADR 0004, in one place. A frame becomes a keyframe when tracking is OK, it moved at
 * least [minTranslationM] or turned at least [minRotationDeg] from the last keyframe, its sharpness is not in the
 * bottom [sharpnessQuantile] of the last [sharpnessWindow] frames, and fewer than [maxKeyframes] were taken.
 */
data class KeyframePolicy(
    /** Minimum camera-centre travel since the last keyframe, metres. */
    val minTranslationM: Float = 0.05f,
    /** Minimum rotation since the last keyframe, degrees. */
    val minRotationDeg: Float = 10f,
    /** Number of recent tracked frames whose sharpness forms the reference distribution. */
    val sharpnessWindow: Int = 30,
    /** Fraction of the window that counts as too blurry: 0.25 rejects the bottom quartile. */
    val sharpnessQuantile: Float = 0.25f,
    /** Most keyframes one capture takes. */
    val maxKeyframes: Int = 120,
) {
    init {
        require(minTranslationM >= 0f && minTranslationM.isFinite()) { "minTranslationM must be >= 0, got $minTranslationM" }
        require(minRotationDeg >= 0f && minRotationDeg.isFinite()) { "minRotationDeg must be >= 0, got $minRotationDeg" }
        require(sharpnessWindow >= 1) { "sharpnessWindow must be >= 1, got $sharpnessWindow" }
        require(sharpnessQuantile in 0f..1f) { "sharpnessQuantile must be in 0..1, got $sharpnessQuantile" }
        require(maxKeyframes >= 1) { "maxKeyframes must be >= 1, got $maxKeyframes" }
    }

    companion object {
        /** A room scan (ADR 0013 section 8): sparser keyframes along the walk, more of them in total. */
        val ROOM: KeyframePolicy = KeyframePolicy(minTranslationM = 0.10f, minRotationDeg = 10f, maxKeyframes = 200)
    }
}
