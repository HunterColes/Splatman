package com.huntercoles.splatman.splat.sfm.nativebridge

import com.huntercoles.splatman.splat.sfm.SparseModel

/**
 * The native SfM front end (epic #200, ADR 0012): OpenCV feature extraction, matching and geometric verification in
 * `libsplat_sfm.so`. Handle-based: [frontEnd] returns a handle that owns every keypoint and verified match; read it
 * with [matchStats], [reconstruct] and [stageMs], then free it with [release] exactly once. Bad input (a missing file, an image
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
    const val STAGE_INIT = 3
    const val STAGE_REGISTER = 4

    /** Hard caps of [reconstruction]; reaching one is reported in the result, never a crash. */
    const val MAX_POINTS = 50_000
    const val MAX_OBSERVATIONS = 1_000_000

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

    /** [reconstruction] with the hard caps, the model only. */
    fun reconstruct(handle: Long): SparseModel = reconstruction(handle).model

    /**
     * Incremental reconstruction from the handle's verified matches (card #226), OpenCV axes, world-to-camera, the
     * world frame the first init camera's; no bundle adjustment and no axis flips (ADR 0003 conversions are Kotlin's).
     * Tracks are the union of every pair's inliers (a track seen twice in one frame is dropped). Init: among the first
     * 30 frames, the pair with the most inliers whose median parallax (gyro rotation removed when the pair has one) is
     * at least 3 degrees; `recoverPose`, then points kept in front of both cameras within 2 px. Then every frame in
     * video order, and the ones left over again: EPnP RANSAC (2 px) on its triangulated tracks, refined by LM, needs 30
     * inliers or stays unregistered; each new frame triangulates its other tracks with the registered frame of widest
     * ray angle (at least 2 degrees). Observations are every registered track entry within 2 px, in full-size pixels;
     * colours the point's pixel in its first observing frame. Fills [STAGE_INIT] and [STAGE_REGISTER] of [stageMs].
     * No init pair gives a model with nothing registered.
     *
     * @param maxPoints 1..[MAX_POINTS]
     * @param maxObservations 1..[MAX_OBSERVATIONS]
     */
    fun reconstruction(
        handle: Long,
        maxPoints: Int = MAX_POINTS,
        maxObservations: Int = MAX_OBSERVATIONS,
    ): Reconstruction {
        val parts = reconstructNative(handle, maxPoints, maxObservations)
        val caps = parts[8] as IntArray
        val model = SparseModel(
            registered = parts[0] as BooleanArray,
            rotationsCcvW = parts[1] as FloatArray,
            translationsCcvW = parts[2] as FloatArray,
            points = parts[3] as FloatArray,
            colours = parts[4] as ByteArray,
            obsFrame = parts[5] as IntArray,
            obsPoint = parts[6] as IntArray,
            obsUv = parts[7] as FloatArray,
        )
        return Reconstruction(model, pointsCapped = caps[0] != 0, observationsCapped = caps[1] != 0)
    }

    @JvmStatic
    private external fun reconstructNative(handle: Long, maxPoints: Int, maxObservations: Int): Array<Any>

    /**
     * Milliseconds per stage: [STAGE_LOAD], [STAGE_FEATURES], [STAGE_MATCHING], [STAGE_INIT], [STAGE_REGISTER] (the
     * last two 0 until [reconstruct] runs).
     */
    @JvmStatic
    external fun stageMs(handle: Long): LongArray

    /** Frees everything the handle owns; a released or unknown handle throws. */
    @JvmStatic
    external fun release(handle: Long)
}

/** A [SparseModel] and whether a cap ([NativeSfm.MAX_POINTS], [NativeSfm.MAX_OBSERVATIONS] or lower) was reached. */
class Reconstruction(val model: SparseModel, val pointsCapped: Boolean, val observationsCapped: Boolean)

/** A front-end error with its reason (thrown by the native side). */
class NativeSfmException(message: String) : RuntimeException(message)
