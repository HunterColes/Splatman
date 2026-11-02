package com.huntercoles.splatman.splat.model

/**
 * A depth map with its own intrinsics (ADR 0002; depth is lower resolution than the colour image), row-major from
 * the top-left pixel, size `intrinsics.width x intrinsics.height`. Arrays are not copied: immutable by contract.
 */
class DepthImage(
    /** Intrinsics of this depth image, pixels of the depth image. */
    val intrinsics: CameraIntrinsics,
    /** Depth per pixel along the camera's viewing axis, metres, Float32; 0 where unknown. */
    val depthM: FloatArray,
    /** Confidence per pixel, 0 (none) to 255 (full) read as unsigned, or null when the source gives none. */
    val confidence: ByteArray?,
) {
    init {
        val n = intrinsics.width * intrinsics.height
        require(depthM.size == n) { "depthM must hold $n values, got ${depthM.size}" }
        require(confidence == null || confidence.size == n) {
            "confidence must hold $n values, got ${confidence?.size}"
        }
    }
}
