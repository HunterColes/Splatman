package com.huntercoles.splatman.viewer.export.video

import com.huntercoles.splatman.splat.math.Mat4
import com.huntercoles.splatman.splat.math.Quat
import com.huntercoles.splatman.splat.math.Vec3
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2

/**
 * The turntable video's camera (#262): one full turn about world up around [target] at [distance], the eye
 * [elevationRad] above the horizon, starting at [startYawRad]. Frame `k` of [frames] is yawed by `2 pi k / frames`,
 * so frame [frames] is frame 0 again: the encoder draws `0 until frames` and the clip loops without a repeated or
 * missing step. Pure maths, the conventions of the Viewer's controller (the eye sits at
 * `target + rotation(+Z) * distance`).
 */
class OrbitPath(
    val target: Vec3,
    val distance: Float,
    val elevationRad: Float,
    val startYawRad: Float,
    val frames: Int = FRAMES,
) {
    init {
        require(frames > 0) { "frames must be positive: $frames" }
        require(distance.isFinite() && distance > 0f) { "distance must be positive: $distance" }
    }

    /** The yaw step between two frames, radians. */
    val stepRad: Float get() = (2.0 * PI / frames).toFloat()

    fun yaw(frame: Int): Float = (startYawRad + 2.0 * PI * frame / frames).toFloat()

    /** The camera's orientation at [frame]: yaw about world up after tilting the eye [elevationRad] up. */
    fun rotation(frame: Int): Quat {
        val yaw = Quat.fromAxisAngle(Vec3.UNIT_Y, yaw(frame))
        val pitch = Quat.fromAxisAngle(Vec3.UNIT_X, -elevationRad)
        return (yaw * pitch).normalized()
    }

    fun eye(frame: Int): Vec3 = target + rotation(frame).rotate(Vec3.UNIT_Z) * distance

    /** World-to-camera at [frame], built as the Viewer's controller builds it. */
    fun view(frame: Int): Mat4 {
        val r = rotation(frame)
        return Mat4.lookAt(target + r.rotate(Vec3.UNIT_Z) * distance, target, r.rotate(Vec3.UNIT_Y))
    }

    companion object {
        const val FPS = 30
        const val SECONDS = 6
        const val FRAMES = FPS * SECONDS

        /** Kept off the poles so the orbit's up stays defined. */
        val MAX_ELEVATION_RAD = Math.toRadians(85.0).toFloat()

        /**
         * The orbit at the Viewer's elevation and distance: [rotation] and [distance] are the Viewer camera's,
         * [target] the scan's centre (the framing target, not a panned one). The start yaw is the Viewer's, so frame
         * 0 is what the Viewer shows when its target is the centre.
         */
        fun of(rotation: Quat, distance: Float, target: Vec3, frames: Int = FRAMES): OrbitPath {
            val f = rotation.normalized().rotate(Vec3.UNIT_Z)
            val elevation = asin(f.y.coerceIn(-1f, 1f)).coerceIn(-MAX_ELEVATION_RAD, MAX_ELEVATION_RAD)
            val horizontal = f.x * f.x + f.z * f.z
            val yaw = if (horizontal > HORIZONTAL_EPS) atan2(f.x, f.z) else 0f
            return OrbitPath(target, distance, elevation, yaw, frames)
        }

        private const val HORIZONTAL_EPS = 1e-12f
    }
}
