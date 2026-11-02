package com.huntercoles.splatman.splat.model

/**
 * Pinhole intrinsics of one image (ADR 0002), OpenCV pixel convention: the centre of pixel (0, 0) is at (0, 0),
 * +u right, +v down, in pixels of that image. Immutable.
 */
data class CameraIntrinsics(
    /** Focal length along u, pixels of this image. */
    val fx: Float,
    /** Focal length along v, pixels of this image. */
    val fy: Float,
    /** Principal point u, pixels of this image (pixel-centre convention above). */
    val cx: Float,
    /** Principal point v, pixels of this image (pixel-centre convention above). */
    val cy: Float,
    /** Image width, pixels. */
    val width: Int,
    /** Image height, pixels. */
    val height: Int,
    /** OpenCV k1, k2, p1, p2, k3 of this image, unitless; ARCore images are undistorted, so [Distortion.NONE]. */
    val distortion: Distortion = Distortion.NONE,
) {
    init {
        require(width > 0 && height > 0) { "image size must be positive, got ${width}x$height" }
        require(fx > 0f && fy > 0f && fx.isFinite() && fy.isFinite()) {
            "focal lengths must be positive and finite, got fx=$fx fy=$fy"
        }
    }

    /**
     * The same camera for the image resized to `newWidth x newHeight`, keeping the pixel-centre convention:
     * with `s = newWidth / width`, `fx' = fx * s` and `cx' = (cx + 0.5) * s - 0.5` (likewise for y with
     * `newHeight / height`). Distortion is unitless on normalised coordinates, so it is kept.
     */
    fun scaled(newWidth: Int, newHeight: Int): CameraIntrinsics {
        require(newWidth > 0 && newHeight > 0) { "image size must be positive, got ${newWidth}x$newHeight" }
        val sx = newWidth.toFloat() / width
        val sy = newHeight.toFloat() / height
        return CameraIntrinsics(
            fx = fx * sx,
            fy = fy * sy,
            cx = (cx + 0.5f) * sx - 0.5f,
            cy = (cy + 0.5f) * sy - 0.5f,
            width = newWidth,
            height = newHeight,
            distortion = distortion,
        )
    }
}
