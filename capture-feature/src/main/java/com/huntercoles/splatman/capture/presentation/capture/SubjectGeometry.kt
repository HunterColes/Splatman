package com.huntercoles.splatman.capture.presentation.capture

import com.huntercoles.splatman.capture.tracking.FeaturePoints
import com.huntercoles.splatman.splat.math.Vec3
import com.huntercoles.splatman.splat.model.CameraIntrinsics
import com.huntercoles.splatman.splat.model.CameraPose
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max

/** A point on the preview view, pixels from its top-left corner. */
data class ScreenPoint(val x: Float, val y: Float)

/**
 * How the camera image fills the portrait preview view of [viewWidth] x [viewHeight] pixels: the landscape sensor
 * image (sensor orientation 90 degrees, the back camera on nearly every phone) turned clockwise and scaled to cover
 * the view, centre-cropped (what ARCore's `transformCoordinates2d` gives the preview for a portrait-locked screen).
 * Image (0, 0) lands at the view's top right; image x runs down the view, image y right to left.
 */
class DisplayMapping(private val viewWidth: Float, private val viewHeight: Float, private val k: CameraIntrinsics) {
    private val scale = max(viewWidth / k.height, viewHeight / k.width)

    /** The view point showing image pixel ([ix], [iy]). */
    fun imageToView(ix: Float, iy: Float): ScreenPoint = ScreenPoint(
        viewWidth / 2 - scale * (iy - k.height / 2f),
        viewHeight / 2 + scale * (ix - k.width / 2f),
    )

    /** The image pixel shown at view point [p]. */
    fun viewToImage(p: ScreenPoint): Pair<Float, Float> = Pair(
        k.width / 2f + (p.y - viewHeight / 2) / scale,
        k.height / 2f - (p.x - viewWidth / 2) / scale,
    )

    /** The unit ray (world frame) from the camera at [pose] through view point [p]. */
    fun ray(pose: CameraPose, p: ScreenPoint): Vec3 {
        val (ix, iy) = viewToImage(p)
        // Cgl: x right, y up, looking down -Z (ADR 0003); image y grows downwards.
        val camera = Vec3((ix - k.cx) / k.fx, -(iy - k.cy) / k.fy, -1f).normalized()
        return pose.rotation.normalized().rotate(camera)
    }

    /** Where world point [w] shows on the view for a camera at [pose], or null when it is behind the camera. */
    fun project(pose: CameraPose, w: Vec3): ScreenPoint? {
        val c = pose.rotation.normalized().conjugate().rotate(w - pose.translationM)
        if (c.z > -MIN_DEPTH_M) return null
        val depth = -c.z
        return imageToView(k.cx + k.fx * c.x / depth, k.cy - k.fy * c.y / depth)
    }

    /** The view's centre. */
    val centre: ScreenPoint get() = ScreenPoint(viewWidth / 2, viewHeight / 2)

    private companion object {
        const val MIN_DEPTH_M = 0.01f
    }
}

/**
 * The subject a tap means: the feature points within [NARROW_DEG] (else [WIDE_DEG]) of the [ray] from [origin] and in
 * front of it; the point at their median distance along the ray. Without any, [DEFAULT_DISTANCE_M] along the ray.
 */
fun pickSubject(origin: Vec3, ray: Vec3, points: FeaturePoints): Vec3 {
    val dir = ray.normalized()
    for (limitDeg in floatArrayOf(NARROW_DEG, WIDE_DEG)) {
        val minCos = cos(limitDeg * PI / 180).toFloat()
        val depths = ArrayList<Float>()
        for (i in 0 until points.count) {
            val d = Vec3(points.positions[3 * i], points.positions[3 * i + 1], points.positions[3 * i + 2]) - origin
            val along = d dot dir
            val length = d.length()
            if (along <= 0f || length < MIN_SUBJECT_M) continue
            if (along / length >= minCos) depths += along
        }
        if (depths.isNotEmpty()) {
            depths.sort()
            return origin + dir * depths[depths.size / 2]
        }
    }
    return origin + dir * DEFAULT_DISTANCE_M
}

/** Feature points within this angle of the tap ray are the subject, degrees. */
const val NARROW_DEG = 5f

/** Failing those, within this angle, degrees. */
const val WIDE_DEG = 15f

/** With no feature point near the ray, the subject is this far along it, metres. */
const val DEFAULT_DISTANCE_M = 0.6f

private const val MIN_SUBJECT_M = 0.05f
