package com.huntercoles.splatman.splat.train.raster

/**
 * A learned correction of every keyframe's pinhole (ticket #10, docs/research/camera-calibration.md): one shared
 * scale on fx and fy, and one radial term k1 on normalised image coordinates. With `x = X / Z`, `y = Y / Z` (Ccv) and
 * `d = 1 + k1 (x^2 + y^2)`, a splat's centre projects to `u = s fx x d + cx`, `v = s fy y d + cy`, and its footprint
 * uses the pinhole Jacobian with focal lengths `s fx d`, `s fy d` (the local isotropic scale of the distortion; the
 * anisotropic `2 k1 x x^T` term is left out of the footprint, the centre is exact). `Lens(1f, 0f)` is the pinhole.
 */
data class Lens(
    /** Factor on fx and fy, unitless. */
    val focalScale: Float = 1f,
    /** Radial coefficient of r^2 on normalised coordinates, unitless. */
    val k1: Float = 0f,
) {
    init {
        require(focalScale > 0f && focalScale.isFinite()) { "focalScale must be positive and finite, got $focalScale" }
        require(k1.isFinite()) { "k1 must be finite, got $k1" }
    }

    companion object {
        /** The plain pinhole. */
        val PINHOLE = Lens()
    }
}
