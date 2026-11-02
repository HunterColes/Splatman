package com.huntercoles.splatman.splat.packet

/**
 * The lens the trainer learned (ticket #10): every keyframe's fx and fy were multiplied by [focalScale] and a radial
 * [k1] (OpenCV's, on normalised coordinates) was applied on top of its pinhole. Export writes it into the scan's
 * metadata so a later run, or a viewer matching the photos, can use the corrected camera. Immutable.
 */
data class CameraRefinement(
    /** Factor on every keyframe's fx and fy, unitless; 1 = the capture's intrinsics were right. */
    val focalScale: Float,
    /** Radial coefficient of r^2, unitless; 0 = no radial distortion. */
    val k1: Float,
)
