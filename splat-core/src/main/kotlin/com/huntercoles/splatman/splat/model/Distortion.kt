package com.huntercoles.splatman.splat.model

/**
 * OpenCV lens distortion of one image (Brown-Conrady: radial k1, k2, k3 and tangential p1, p2), unitless and
 * defined on normalised image coordinates of the image the owning [CameraIntrinsics] names (ADR 0002).
 * ARCore CPU images are undistorted, so v1 always passes [NONE]; the calibration research (#3) fills it later.
 */
data class Distortion(
    /** Radial coefficient of r^2, unitless. */
    val k1: Float,
    /** Radial coefficient of r^4, unitless. */
    val k2: Float,
    /** First tangential coefficient, unitless. */
    val p1: Float,
    /** Second tangential coefficient, unitless. */
    val p2: Float,
    /** Radial coefficient of r^6, unitless. */
    val k3: Float,
) {
    companion object {
        /** No distortion: an ideal pinhole. */
        val NONE = Distortion(0f, 0f, 0f, 0f, 0f)
    }
}
