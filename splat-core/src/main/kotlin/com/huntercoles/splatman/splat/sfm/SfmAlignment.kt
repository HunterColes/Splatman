package com.huntercoles.splatman.splat.sfm

import com.huntercoles.splatman.splat.frames.FrameConversions
import com.huntercoles.splatman.splat.math.Quat
import com.huntercoles.splatman.splat.math.Vec3
import com.huntercoles.splatman.splat.model.CameraPose
import com.huntercoles.splatman.splat.model.SeedPoints
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Puts a [SparseModel] (OpenCV world-to-camera, arbitrary world V) into Splatman's world W (ADR 0003, ADR 0012)
 * with one similarity: the mean accelerometer (specific force, +g along up) of the registered frames goes to +Y,
 * the coordinate-wise median sparse point to the origin, and the median camera-to-origin distance to 1.0. The yaw
 * about +Y is whatever the shortest tilt leaves.
 */
object SfmAlignment {

    /** Accelerometer samples within this distance of a frame's timestamp are averaged for that frame. */
    const val ACCEL_WINDOW_NS = 100_000_000L

    /**
     * A model whose median reprojection error is above this (pixels) does not reproduce its own observations:
     * mirrored in handedness or broken; it is rejected.
     */
    const val MAX_MEDIAN_REPROJ_PX = 10f

    /**
     * Poses `T_W_Cgl` index-aligned with [frames] (null = unregistered) and every sparse point in W, colours
     * copied. Throws [IllegalArgumentException] for a model that fails its own reprojection (e.g. mirrored), no
     * points, no registered frame, or no accelerometer reading near a registered frame.
     */
    fun align(
        model: SparseModel,
        accel: ImuSamples,
        rotationCcvImu: Quat,
        frames: List<SfmFrame>,
    ): Pair<List<CameraPose?>, SeedPoints> {
        require(model.frameCount == frames.size) {
            "model has ${model.frameCount} frames, frames list has ${frames.size}"
        }
        require(model.pointCount > 0) { "model has no points" }
        val registered = (0 until model.frameCount).filter { model.registered[it] }
        require(registered.isNotEmpty()) { "model has no registered frame" }

        // registered frames only: an unregistered slot may be zero-filled, which has no rotation to normalise
        val rotations = registered.associateWith { f ->
            Quat(
                model.rotationsCcvW[4 * f], model.rotationsCcvW[4 * f + 1],
                model.rotationsCcvW[4 * f + 2], model.rotationsCcvW[4 * f + 3],
            ).normalized()
        }
        val translations = registered.associateWith { f ->
            Vec3(model.translationsCcvW[3 * f], model.translationsCcvW[3 * f + 1], model.translationsCcvW[3 * f + 2])
        }
        val points = List(model.pointCount) { Vec3(model.points[3 * it], model.points[3 * it + 1], model.points[3 * it + 2]) }

        checkReprojection(model, frames, rotations, translations, points)

        // up in V: per registered frame the mean accelerometer, into Ccv, then into V by R_V_Ccv = R_Ccv_V^T
        var upSum = Vec3.ZERO
        var used = 0
        for (f in registered) {
            val a = meanAccel(accel, frames[f].timestampNs) ?: continue
            val inV = rotations.getValue(f).conjugate().rotate(rotationCcvImu.normalized().rotate(a))
            if (inV.lengthSquared() == 0f) continue
            upSum += inV.normalized()
            used++
        }
        require(used > 0) { "no accelerometer reading within 100 ms of a registered frame" }
        require(upSum.lengthSquared() > 0f) { "accelerometer readings cancel out: no up direction" }
        val tilt = shortestRotation(upSum.normalized(), Vec3.UNIT_Y) // R_W'_V, W' = W before the shift

        val rotated = points.map { tilt.rotate(it) }
        val origin = Vec3(
            median(rotated.map { it.x }),
            median(rotated.map { it.y }),
            median(rotated.map { it.z }),
        )
        val posesV = registered.associateWith {
            FrameConversions.poseFromOpenCv(rotations.getValue(it), translations.getValue(it))
        }
        val centres = posesV.mapValues { (_, p) -> tilt.rotate(p.translationM) - origin }
        val radius = median(centres.values.map { it.length() })
        require(radius > 0f && radius.isFinite()) { "median camera distance from the median point is $radius" }
        val scale = 1f / radius

        val poses = List(model.frameCount) { f ->
            val p = posesV[f] ?: return@List null
            CameraPose((tilt * p.rotation).normalized(), centres.getValue(f) * scale)
        }
        val positions = FloatArray(3 * points.size)
        rotated.forEachIndexed { i, p ->
            val q = (p - origin) * scale
            positions[3 * i] = q.x
            positions[3 * i + 1] = q.y
            positions[3 * i + 2] = q.z
        }
        return poses to SeedPoints(points.size, positions, model.colours.copyOf())
    }

    private fun checkReprojection(
        model: SparseModel,
        frames: List<SfmFrame>,
        rotations: Map<Int, Quat>,
        translations: Map<Int, Vec3>,
        points: List<Vec3>,
    ) {
        if (model.observationCount == 0) return
        val errors = FloatArray(model.observationCount) { k ->
            val f = model.obsFrame[k]
            val pc = rotations.getValue(f).rotate(points[model.obsPoint[k]]) + translations.getValue(f)
            val px = FrameConversions.project(frames[f].intrinsics, pc)
            if (px == null) {
                Float.POSITIVE_INFINITY
            } else {
                hypot(px.u - model.obsUv[2 * k], px.v - model.obsUv[2 * k + 1])
            }
        }
        val med = median(errors.asList())
        require(med <= MAX_MEDIAN_REPROJ_PX) {
            "median reprojection error $med px exceeds $MAX_MEDIAN_REPROJ_PX px: model mirrored in handedness or broken"
        }
    }

    private fun meanAccel(accel: ImuSamples, tNs: Long): Vec3? {
        var sum = Vec3.ZERO
        var n = 0
        for (k in 0 until accel.count) {
            val dt = accel.timestampsNs[k] - tNs
            if (dt < -ACCEL_WINDOW_NS || dt > ACCEL_WINDOW_NS) continue
            sum += Vec3(accel.xyz[3 * k], accel.xyz[3 * k + 1], accel.xyz[3 * k + 2])
            n++
        }
        return if (n == 0) null else sum / n.toFloat()
    }

    /** The shortest rotation taking unit [from] to unit [to]. */
    private fun shortestRotation(from: Vec3, to: Vec3): Quat {
        val axis = from cross to
        val cos = (from dot to).coerceIn(-1f, 1f)
        if (axis.lengthSquared() < 1e-12f) {
            if (cos > 0f) return Quat.IDENTITY
            // opposite: half-turn about any axis perpendicular to from
            val other = if (kotlin.math.abs(from.x) < 0.9f) Vec3.UNIT_X else Vec3.UNIT_Z
            return Quat.fromAxisAngle(from cross other, kotlin.math.PI.toFloat())
        }
        return Quat.fromAxisAngle(axis, atan2(axis.length(), cos))
    }

    private fun median(values: List<Float>): Float {
        val s = values.sorted()
        val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else 0.5f * (s[m - 1] + s[m])
    }
}
