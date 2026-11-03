package com.huntercoles.splatman.viewer.rendering

import com.huntercoles.splatman.splat.math.Mat4
import com.huntercoles.splatman.splat.math.Quat
import com.huntercoles.splatman.splat.math.Vec3
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
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
 * [setRoomView] opens a room scan from inside instead (docs/design/screens/viewer.md "Room scans"): drag turns the
 * view about the eye, pan slides the eye, pinch steps it along the view, all kept within the room's sphere; fling,
 * auto-rotate and reset work the same about the eye. [setInitialView] or [reset] go back to orbiting.
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

    private var room: RoomBounds? = null

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
        if (room != null) return turnInPlace(deltaX, deltaY)
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
        room?.let { r ->
            val eye = eye()
            moveEyeTo(eye + forward() * (-kotlin.math.ln(scaleFactor) * r.step), r)
            return
        }
        distance = (distance * scaleFactor).coerceIn(minDistance, maxDistance)
    }

    /** Pan by a two-finger drag: moves the target along the camera's right and up, scaled by the distance. */
    fun onPan(deltaX: Float, deltaY: Float) {
        room?.let { r ->
            val slide = ViewerConstants.PAN_SENSITIVITY * r.radius
            val move = (rotation.rotate(Vec3.UNIT_X) * deltaX + rotation.rotate(Vec3.UNIT_Y) * deltaY) * slide
            moveEyeTo(eye() + move, r)
            return
        }
        val sensitivity = ViewerConstants.PAN_SENSITIVITY * distance
        val right = rotation.rotate(Vec3.UNIT_X)
        val up = rotation.rotate(Vec3.UNIT_Y)
        target = target + (right * deltaX + up * deltaY) * sensitivity
    }

    /** One accessibility or keyboard orbit step: [yawSteps] and [pitchSteps] of [ViewerConstants.ORBIT_STEP_DEG]. */
    fun orbitStep(yawSteps: Int, pitchSteps: Int) {
        val units = Math.toRadians(ViewerConstants.ORBIT_STEP_DEG.toDouble()).toFloat() /
            ViewerConstants.ROTATION_SENSITIVITY
        // Inside a room a drag turns the other way (the room follows the finger); a step still names its direction.
        val yaw = if (room != null) -yawSteps else yawSteps
        onRotate(yaw * units, pitchSteps * units)
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
        room = null
        minDistance = max(nearPlane * 2f, framed * MIN_FACTOR)
        maxDistance = max(minDistance, framed * MAX_FACTOR)
        initialTarget = sceneTarget
        initialDistance = framed.coerceIn(minDistance, maxDistance)
        initialRotation = initialOrientation()
        stopMotion()
        snapToInitial()
    }

    /**
     * Opens a room scan from inside: the eye at [start]'s eye, looking along its forward levelled (no roll, the
     * pitch kept up to [ROOM_MAX_PITCH_DEG]), and makes it the view [resetToInitial] returns to. The eye stays within
     * [radius] of [centre] (the scene's framing sphere), widened to take in the start eye.
     */
    fun setRoomView(start: RoomStart, centre: Vec3, radius: Float) {
        val r = if (radius.isFinite() && radius > 0f) radius else 1f
        val reach = max(r, (start.eye - centre).length())
        room = RoomBounds(centre, reach, r, r * ROOM_STEP_FACTOR)
        minDistance = ROOM_LOOK_DISTANCE
        maxDistance = ROOM_LOOK_DISTANCE
        initialDistance = ROOM_LOOK_DISTANCE
        initialRotation = levelled(start.forward)
        initialTarget = start.eye - initialRotation.rotate(Vec3.UNIT_Z) * ROOM_LOOK_DISTANCE
        stopMotion()
        snapToInitial()
    }

    /** True while the camera looks around a room from inside ([setRoomView]). */
    val isRoom: Boolean get() = room != null

    /** Where the camera is: `target + rotation(+Z) * distance`. */
    fun eye(): Vec3 = target + rotation.rotate(Vec3.UNIT_Z) * distance

    /** The view direction (camera -Z). */
    fun forward(): Vec3 = -rotation.rotate(Vec3.UNIT_Z)

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
        if (autoRotate && room != null) {
            val eye = eye()
            val yaw = Quat.fromAxisAngle(Vec3.UNIT_Y, ViewerConstants.AUTO_ROTATE_RAD_PER_S * seconds)
            rotation = (yaw * rotation).normalized()
            target = eye - rotation.rotate(Vec3.UNIT_Z) * distance
        } else if (autoRotate) {
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
        room = null
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

    /**
     * Puts the camera exactly at [rotation], [target], [distance] (no clamp, no animation): an offscreen renderer
     * driven frame by frame, such as the turntable video, sets each frame's pose with it.
     */
    fun setPose(rotation: Quat, target: Vec3, distance: Float) {
        stopMotion()
        this.rotation = rotation.normalized()
        this.target = target
        this.distance = distance
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

    /** Room drag: yaw about world up and pitch about the camera's right, both about the eye, pitch clamped. */
    private fun turnInPlace(deltaX: Float, deltaY: Float) {
        val sensitivity = ViewerConstants.ROTATION_SENSITIVITY
        val eye = eye()
        val yawed = (Quat.fromAxisAngle(Vec3.UNIT_Y, deltaX * sensitivity) * rotation).normalized()
        val limit = Math.toRadians(ROOM_MAX_PITCH_DEG.toDouble()).toFloat()
        val pitch = pitchOf(yawed)
        val delta = (pitch + deltaY * sensitivity).coerceIn(-limit, limit) - pitch
        val right = yawed.rotate(Vec3.UNIT_X)
        rotation = (Quat.fromAxisAngle(right, delta) * yawed).normalized()
        target = eye - rotation.rotate(Vec3.UNIT_Z) * distance
    }

    private fun moveEyeTo(eye: Vec3, bounds: RoomBounds) {
        val offset = eye - bounds.centre
        val length = offset.length()
        val kept = if (length > bounds.reach) bounds.centre + offset * (bounds.reach / length) else eye
        target = kept - rotation.rotate(Vec3.UNIT_Z) * distance
    }

    /** [centre], [reach]: the sphere the eye stays in; [radius] the scene's; [step] metres per e-fold of pinch. */
    private data class RoomBounds(val centre: Vec3, val reach: Float, val radius: Float, val step: Float)

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

        /** Room scans: the look-at distance in front of the eye (metres) and the steepest look up or down. */
        const val ROOM_LOOK_DISTANCE = 0.5f
        const val ROOM_MAX_PITCH_DEG = 85f

        /** Room scans: a pinch by a factor of e steps this fraction of the room's radius. */
        const val ROOM_STEP_FACTOR = 0.5f

        /** The view's pitch above the horizon (radians) for [q]. */
        internal fun pitchOf(q: Quat): Float = asin(q.rotate(-Vec3.UNIT_Z).y.coerceIn(-1f, 1f))

        /** Looks along [forward] with no roll: yaw about world up, then pitch within [ROOM_MAX_PITCH_DEG]. */
        fun levelled(forward: Vec3): Quat {
            val f = forward.normalized()
            val horizontal = kotlin.math.sqrt(f.x * f.x + f.z * f.z)
            val yaw = if (horizontal < 1e-6f) 0f else atan2(-f.x, -f.z)
            val limit = Math.toRadians(ROOM_MAX_PITCH_DEG.toDouble()).toFloat()
            val pitch = asin(f.y.coerceIn(-1f, 1f)).coerceIn(-limit, limit)
            return (Quat.fromAxisAngle(Vec3.UNIT_Y, yaw) * Quat.fromAxisAngle(Vec3.UNIT_X, pitch)).normalized()
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
