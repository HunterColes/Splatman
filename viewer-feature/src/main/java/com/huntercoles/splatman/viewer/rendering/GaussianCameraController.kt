package com.huntercoles.splatman.viewer.rendering

import com.huntercoles.splatman.splat.math.Mat4
import com.huntercoles.splatman.splat.math.Quat
import com.huntercoles.splatman.splat.math.Vec3
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

/**
 * The viewer's orbit camera: it orbits a [target] at a [distance], pans the target in camera space and zooms by
 * scaling the distance. All state is plain maths, testable without OpenGL; callers synchronise on the instance.
 *
 * [setInitialView] frames a scene: the front three-quarter view (yaw [INITIAL_YAW_DEG], looking down
 * [INITIAL_PITCH_DEG]) with the scene's sphere filling [FILL] of the short edge, and zoom clamped to a range around
 * it so the scan can neither vanish nor be passed through. [advance] runs per frame: fling with friction,
 * auto-rotate and the animated reset to the initial view.
 *
 * Maths from splat-core (ADR 0006): Hamilton wxyz quaternions, column-major [Mat4]. The eye sits at
 * `target + rotation(+Z) * distance`.
 */
class GaussianCameraController {

    private var distance: Float = ViewerConstants.DEFAULT_DISTANCE
    private var rotation: Quat = Quat.IDENTITY
    private var target: Vec3 = Vec3.ZERO
    private var aspectRatio: Float = 16f / 9f

    private val fov: Float = ViewerConstants.FOV_DEGREES
    private val nearPlane: Float = ViewerConstants.NEAR_PLANE
    private var farPlane: Float = ViewerConstants.FAR_PLANE

    private var minDistance: Float = ViewerConstants.MIN_DISTANCE
    private var maxDistance: Float = ViewerConstants.MAX_DISTANCE

    private var initialRotation: Quat = Quat.IDENTITY
    private var initialDistance: Float = ViewerConstants.DEFAULT_DISTANCE
    private var initialTarget: Vec3 = Vec3.ZERO

    private var flingX = 0f
    private var flingY = 0f
    private var resetAnim: ResetAnimation? = null

    /** Spins the view about the world up axis in [advance] while true. */
    var autoRotate: Boolean = false

    /**
     * Orbit by a drag: [deltaX] yaws about world up, [deltaY] pitches about the camera's right axis, both in
     * controller units ([ViewerConstants.ROTATION_SENSITIVITY] radians each).
     */
    fun onRotate(deltaX: Float, deltaY: Float) {
        val sensitivity = ViewerConstants.ROTATION_SENSITIVITY
        val yaw = Quat.fromAxisAngle(Vec3.UNIT_Y, -deltaX * sensitivity)
        rotation = yaw * rotation
        val right = rotation.rotate(Vec3.UNIT_X)
        val pitch = Quat.fromAxisAngle(right, -deltaY * sensitivity)
        rotation = (pitch * rotation).normalized()
    }

    /** Zoom by a pinch: [scaleFactor] < 1 moves in, > 1 out; the distance stays within the clamps. */
    fun onZoom(scaleFactor: Float) {
        if (!scaleFactor.isFinite() || scaleFactor <= 0f) return
        distance = (distance * scaleFactor).coerceIn(minDistance, maxDistance)
    }

    /** Pan by a two-finger drag: moves the target along the camera's right and up, scaled by the distance. */
    fun onPan(deltaX: Float, deltaY: Float) {
        val sensitivity = ViewerConstants.PAN_SENSITIVITY * distance
        val right = rotation.rotate(Vec3.UNIT_X)
        val up = rotation.rotate(Vec3.UNIT_Y)
        target = target + (right * deltaX + up * deltaY) * sensitivity
    }

    /** One accessibility or keyboard orbit step: [yawSteps] and [pitchSteps] of [ViewerConstants.ORBIT_STEP_DEG]. */
    fun orbitStep(yawSteps: Int, pitchSteps: Int) {
        val units = Math.toRadians(ViewerConstants.ORBIT_STEP_DEG.toDouble()).toFloat() /
            ViewerConstants.ROTATION_SENSITIVITY
        onRotate(yawSteps * units, pitchSteps * units)
    }

    /** One accessibility or keyboard zoom step: positive moves in by [ViewerConstants.ZOOM_STEP], negative out. */
    fun zoomStep(steps: Int) {
        val factor = ViewerConstants.ZOOM_STEP
        repeat(abs(steps)) { onZoom(if (steps > 0) 1f / factor else factor) }
    }

    /** A finger lifted while moving: the orbit continues at [velocityX], [velocityY] (controller units per second). */
    fun fling(velocityX: Float, velocityY: Float) {
        if (!velocityX.isFinite() || !velocityY.isFinite()) return
        flingX = velocityX
        flingY = velocityY
    }

    /** A finger went down: any fling or reset animation stops where it is. */
    fun stopMotion() {
        flingX = 0f
        flingY = 0f
        resetAnim = null
    }

    /** True while a fling or a reset animation is running. */
    val isMoving: Boolean get() = flingX != 0f || flingY != 0f || resetAnim != null

    /**
     * Frames a scene centred on [sceneTarget] with radius [radius] in the front three-quarter view and makes it the
     * view [resetToInitial] returns to. The zoom clamps become [MIN_FACTOR]..[MAX_FACTOR] of the framed distance.
     */
    fun setInitialView(sceneTarget: Vec3, radius: Float) {
        val r = if (radius.isFinite() && radius > 0f) radius else 1f
        val halfV = Math.toRadians(fov / 2.0)
        val halfShort = if (aspectRatio < 1f) kotlin.math.atan(tan(halfV) * aspectRatio) else halfV
        val framed = (r / (FILL * tan(halfShort))).toFloat()
        minDistance = max(nearPlane * 2f, framed * MIN_FACTOR)
        maxDistance = max(minDistance, framed * MAX_FACTOR)
        initialTarget = sceneTarget
        initialDistance = framed.coerceIn(minDistance, maxDistance)
        initialRotation = initialOrientation()
        stopMotion()
        snapToInitial()
    }

    /** Back to the initial view: at once, or eased over [ViewerConstants.RESET_SECONDS] through [advance]. */
    fun resetToInitial(animated: Boolean) {
        flingX = 0f
        flingY = 0f
        if (!animated) {
            resetAnim = null
            snapToInitial()
        } else {
            resetAnim = ResetAnimation(rotation, distance, target, 0f)
        }
    }

    /** Moves the camera on by [seconds]: the reset animation, else the fling (with friction), and auto-rotate. */
    fun advance(seconds: Float) {
        if (!seconds.isFinite() || seconds <= 0f) return
        val anim = resetAnim
        if (anim != null) {
            val t = min(1f, anim.elapsed + seconds / ViewerConstants.RESET_SECONDS)
            val e = easeOut(t)
            rotation = nlerp(anim.fromRotation, initialRotation, e)
            distance = anim.fromDistance + (initialDistance - anim.fromDistance) * e
            target = anim.fromTarget + (initialTarget - anim.fromTarget) * e
            resetAnim = if (t >= 1f) null else anim.copy(elapsed = t)
            return
        }
        if (flingX != 0f || flingY != 0f) {
            onRotate(flingX * seconds, flingY * seconds)
            val decay = exp(-ViewerConstants.FLING_FRICTION * seconds)
            flingX *= decay
            flingY *= decay
            if (abs(flingX) < ViewerConstants.FLING_STOP && abs(flingY) < ViewerConstants.FLING_STOP) {
                flingX = 0f
                flingY = 0f
            }
        }
        if (autoRotate) {
            val yaw = Quat.fromAxisAngle(Vec3.UNIT_Y, ViewerConstants.AUTO_ROTATE_RAD_PER_S * seconds)
            rotation = (yaw * rotation).normalized()
        }
    }

    /** Back to the defaults before any scene: identity rotation, [ViewerConstants.DEFAULT_DISTANCE], origin. */
    fun reset() {
        distance = ViewerConstants.DEFAULT_DISTANCE
        rotation = Quat.IDENTITY
        target = Vec3.ZERO
        minDistance = ViewerConstants.MIN_DISTANCE
        maxDistance = ViewerConstants.MAX_DISTANCE
        initialRotation = Quat.IDENTITY
        initialDistance = ViewerConstants.DEFAULT_DISTANCE
        initialTarget = Vec3.ZERO
        stopMotion()
    }

    fun setTarget(newTarget: Vec3) {
        target = newTarget
    }

    fun setAspectRatio(aspectRatio: Float) {
        this.aspectRatio = aspectRatio
    }

    fun setFarPlane(farPlane: Float) {
        this.farPlane = farPlane
    }

    /** Sets the distance directly, within the current clamps. */
    fun setDistance(distance: Float) {
        this.distance = distance.coerceIn(minDistance, maxDistance)
    }

    fun getViewMatrix(): Mat4 {
        val forward = rotation.rotate(Vec3.UNIT_Z)
        val eye = target + forward * distance
        val up = rotation.rotate(Vec3.UNIT_Y)
        return Mat4.lookAt(eye, target, up)
    }

    fun getProjectionMatrix(): Mat4 =
        Mat4.perspective(Math.toRadians(fov.toDouble()).toFloat(), aspectRatio, nearPlane, farPlane)

    fun getDistance(): Float = distance
    fun getRotation(): Quat = rotation
    fun getTarget(): Vec3 = target
    fun getNearPlane(): Float = nearPlane
    fun getFarPlane(): Float = farPlane
    fun getMinDistance(): Float = minDistance
    fun getMaxDistance(): Float = maxDistance

    private fun snapToInitial() {
        rotation = initialRotation
        distance = initialDistance
        target = initialTarget
    }

    private data class ResetAnimation(
        val fromRotation: Quat,
        val fromDistance: Float,
        val fromTarget: Vec3,
        val elapsed: Float,
    )

    companion object {
        /** The initial view's yaw about world up and its downward tilt (degrees). */
        const val INITIAL_YAW_DEG = 35f
        const val INITIAL_PITCH_DEG = 20f

        /** The initial view fills about this fraction of the short edge with the scene's sphere. */
        const val FILL = 0.7f

        /** Zoom clamps as fractions of the framed distance: in to a quarter, out to ten times. */
        const val MIN_FACTOR = 0.25f
        const val MAX_FACTOR = 10f

        /** Yaw [INITIAL_YAW_DEG] about world up after tilting the eye [INITIAL_PITCH_DEG] above the horizon. */
        fun initialOrientation(): Quat {
            val yaw = Quat.fromAxisAngle(Vec3.UNIT_Y, Math.toRadians(INITIAL_YAW_DEG.toDouble()).toFloat())
            val pitch = Quat.fromAxisAngle(Vec3.UNIT_X, -Math.toRadians(INITIAL_PITCH_DEG.toDouble()).toFloat())
            return (yaw * pitch).normalized()
        }

        /** Emphasized-ish deceleration: cubic ease-out. */
        internal fun easeOut(t: Float): Float {
            val u = 1f - t
            return 1f - u * u * u
        }

        /** Normalised lerp along the shorter arc. */
        internal fun nlerp(a: Quat, b: Quat, t: Float): Quat {
            val dot = a.w * b.w + a.x * b.x + a.y * b.y + a.z * b.z
            val s = if (dot < 0f) -1f else 1f
            return Quat(
                a.w + (s * b.w - a.w) * t,
                a.x + (s * b.x - a.x) * t,
                a.y + (s * b.y - a.y) * t,
                a.z + (s * b.z - a.z) * t,
            ).normalized()
        }
    }
}
