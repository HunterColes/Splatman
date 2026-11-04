package com.huntercoles.splatman.splat.sfm.nativebridge

/**
 * The native SfM front end (epic #200, ADR 0012): OpenCV feature extraction, matching and geometric verification in
 * `libsplat_sfm.so`. Handle-based: [frontEnd] returns a handle that owns every keypoint and verified match; read it
 * with [matchStats] and [stageMs], then free it with [release] exactly once. Bad input (a missing file, an image
 * that does not match its intrinsics, an array of the wrong length) throws [NativeSfmException] with the reason.
 */
object NativeSfm {
    /** `kind` values, the ordinals of splat-core's `FeatureKind`. */
    const val KIND_ORB = 0
    const val KIND_AKAZE = 1

    /** Indices into [stageMs]. */
    const val STAGE_LOAD = 0
    const val STAGE_FEATURES = 1
    const val STAGE_MATCHING = 2

    /** Floats per frame in `intrinsics`: fx fy cx cy w h (full-size pixels; w and h whole numbers). */
    const val INTRINSICS_PER_FRAME = 6

    /** Floats per pair in `pairRotations`: w x y z of `R_Cj_Ci` (OpenCV camera frames), or four NaN for none. */
    const val ROTATION_PER_PAIR = 4

    init {
        System.loadLibrary("splat_sfm")
    }

    /**
     * Loads every frame (`cv::imread`, grayscale, downscaled so the long side is at most [longSide]), extracts at most
     * [maxFeatures] ORB or AKAZE features per frame, and for each pair (i, j) in [pairs] matches i to j (Hamming,
     * Lowe's ratio 0.8, mutual check), drops matches over 5 % of the long side away from where the gyro rotation
     * `K_j R K_i^-1` sends them (when the pair has one), and keeps the essential-matrix RANSAC inliers (1 px at the
     * working size). Keypoints are kept in full-size pixels. Each image must be exactly w x h of its intrinsics.
     *
     * @param paths image files, one per frame
     * @param intrinsics [INTRINSICS_PER_FRAME] floats per frame
     * @param pairs i, j per pair (frame indices, i != j)
     * @param pairRotations [ROTATION_PER_PAIR] floats per pair
     * @param kind [KIND_ORB] or [KIND_AKAZE]
     * @return a handle for [matchStats], [stageMs] and [release]
     */
    @JvmStatic
    external fun frontEnd(
        paths: Array<String>,
        intrinsics: FloatArray,
        pairs: IntArray,
        pairRotations: FloatArray,
        kind: Int,
        maxFeatures: Int,
        longSide: Int,
    ): Long

    /** Verified inliers per pair, in the order of `pairs`. */
    @JvmStatic
    external fun matchStats(handle: Long): IntArray

    /** Milliseconds per stage: [STAGE_LOAD], [STAGE_FEATURES], [STAGE_MATCHING]. */
    @JvmStatic
    external fun stageMs(handle: Long): LongArray

    /** Frees everything the handle owns; a released or unknown handle throws. */
    @JvmStatic
    external fun release(handle: Long)
}

/** A front-end error with its reason (thrown by the native side). */
class NativeSfmException(message: String) : RuntimeException(message)
