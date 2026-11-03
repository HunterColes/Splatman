package com.huntercoles.splatman.splat.frames

import com.huntercoles.splatman.splat.math.Mat4
import com.huntercoles.splatman.splat.math.Quat
import com.huntercoles.splatman.splat.math.Vec3
import com.huntercoles.splatman.splat.model.CameraIntrinsics
import com.huntercoles.splatman.splat.model.CameraPose
import com.huntercoles.splatman.splat.model.SplatCloud

/** A point in the image frame I (ADR 0003): u right, v down, pixel (i, j) centre at (i, j), pixels. */
data class Pixel(val u: Float, val v: Float)

/**
 * The one owner of every frame conversion (ADR 0003): axis flips, quaternion component reorders and projections
 * built from intrinsics live here and nowhere else. Frames: W world (ARCore, +Y up), Cgl camera (OpenGL axes,
 * looking down -Z), Ccv camera (OpenCV axes, +Y down, +Z forward), I image, F file (3DGS PLY and `.splat`, W turned
 * 180 degrees about X), NS Nerfstudio.
 *
 * Every function is pure: inputs are never written, arrays out are new. A zero quaternion is rejected with a
 * message wherever one is read.
 */
object FrameConversions {

    /** The X half-turn as a quaternion: both `F` (Cgl <-> Ccv) and `G` (W <-> F) are `diag(1, -1, -1)`. */
    private val X_HALF_TURN = Quat(0f, 1f, 0f, 0f)

    /**
     * Sign of each SH coefficient beyond degree 0 under W <-> F, `sh1..sh15` (the PLY `f_rest` order within a
     * channel): the parity of its basis function under `y -> -y, z -> -z` (ADR 0003 rule 5).
     */
    private val SH_REST_SIGNS = floatArrayOf(
        -1f, -1f, 1f, // degree 1: y, z, x
        -1f, 1f, 1f, -1f, 1f, // degree 2: xy, yz, 3z^2-1, xz, x^2-y^2
        -1f, 1f, -1f, -1f, 1f, -1f, 1f, // degree 3
    )

    // ---- ARCore and quaternion component order (rules 1, 6) ----

    /**
     * ARCore `Camera.getPose()` -> [CameraPose] (`T_W_Cgl`, ADR 0003 rule 1). [translation] is
     * `Pose.getTranslation()` (3 floats, m), [rotationXyzw] is `Pose.getRotationQuaternion()` (x, y, z, w);
     * the rotation is normalised.
     */
    fun poseFromArCore(translation: FloatArray, rotationXyzw: FloatArray): CameraPose {
        require(translation.size == 3) { "ARCore translation needs 3 floats, got ${translation.size}" }
        require(rotationXyzw.size == 4) { "ARCore rotation needs 4 floats (x, y, z, w), got ${rotationXyzw.size}" }
        return CameraPose(
            rotation = quatFromXyzw(rotationXyzw).normalized(),
            translationM = Vec3(translation[0], translation[1], translation[2]),
        )
    }

    /** Reads an x, y, z, w quaternion (ARCore, glTF, SPZ) at [offset] into the app's w, x, y, z [Quat]. */
    fun quatFromXyzw(xyzw: FloatArray, offset: Int = 0): Quat {
        require(offset >= 0 && offset + 4 <= xyzw.size) {
            "need 4 floats at offset $offset, array holds ${xyzw.size}"
        }
        return checkRotation(Quat(xyzw[offset + 3], xyzw[offset], xyzw[offset + 1], xyzw[offset + 2]))
    }

    /** Writes [q] as a new x, y, z, w array (glTF, SPZ order). */
    fun quatToXyzw(q: Quat): FloatArray {
        checkRotation(q)
        return floatArrayOf(q.x, q.y, q.z, q.w)
    }

    // ---- Cgl <-> Ccv (F = diag(1, -1, -1), rule 2) ----

    /** A point in Cgl to Ccv: `p_cv = F p_gl`. */
    fun glToCv(p: Vec3): Vec3 = Vec3(p.x, -p.y, -p.z)

    /** A point in Ccv to Cgl; `F` is its own inverse. */
    fun cvToGl(p: Vec3): Vec3 = Vec3(p.x, -p.y, -p.z)

    /** `T_W_Cgl` -> `T_W_Ccv = T_W_Cgl * F`: rotation `R F`, translation unchanged. */
    fun glToCv(pose: CameraPose): CameraPose =
        CameraPose(checkRotation(pose.rotation) * X_HALF_TURN, pose.translationM)

    /** `T_W_Ccv` -> `T_W_Cgl = T_W_Ccv * F`, using `F^-1` so `cvToGl(glToCv(pose))` gives back `q`, not `-q`. */
    fun cvToGl(pose: CameraPose): CameraPose =
        CameraPose(checkRotation(pose.rotation) * X_HALF_TURN.conjugate(), pose.translationM)

    // ---- OpenCV world-to-camera (R, t) <-> T_W_Cgl (rule 2) ----

    /**
     * OpenCV world-to-camera `x_cv = R x_W + t` ([rotationCcvW] = `R_Ccv_W`, [translationCcvW] = `t`) ->
     * [CameraPose] `T_W_Cgl`: invert (`R_W_Ccv = R^T`, centre `c = -R^T t`), then `T_W_Cgl = T_W_Ccv F`.
     * The rotation is normalised.
     */
    fun poseFromOpenCv(rotationCcvW: Quat, translationCcvW: Vec3): CameraPose {
        val rWCcv = checkRotation(rotationCcvW).normalized().conjugate()
        return cvToGl(CameraPose(rWCcv, -rWCcv.rotate(translationCcvW)))
    }

    /** The inverse of [poseFromOpenCv]: `T_W_Cgl` -> OpenCV `(R_Ccv_W, t)` with `t = -R_Ccv_W c`. */
    fun poseToOpenCv(pose: CameraPose): Pair<Quat, Vec3> {
        val wCcv = glToCv(pose)
        val rCcvW = wCcv.rotation.normalized().conjugate()
        return rCcvW to -rCcvW.rotate(wCcv.translationM)
    }

    // ---- View, projection, pinhole (rules 3, 4) ----

    /** `T_W_Cgl` of [pose] as a column-major matrix (rotation normalised). */
    fun poseMatrix(pose: CameraPose): Mat4 =
        Mat4.fromRotationTranslation(checkRotation(pose.rotation).normalized(), pose.translationM)

    /** OpenGL view matrix `V = inverse(T_W_Cgl)`: world W -> Cgl. */
    fun viewMatrix(pose: CameraPose): Mat4 = poseMatrix(pose).rigidInverse()

    /**
     * OpenGL projection Cgl -> clip reproducing the pinhole of [intrinsics]: pixel edges `u = -0.5` and
     * `u = w - 0.5` go to NDC x = -1 and +1, the image's top edge (`v = -0.5`) to NDC y = +1.
     */
    fun projectionFromIntrinsics(intrinsics: CameraIntrinsics, near: Float, far: Float): Mat4 {
        require(near > 0f && far > near && far.isFinite()) { "need 0 < near < far, got near=$near far=$far" }
        val w = intrinsics.width.toFloat()
        val h = intrinsics.height.toFloat()
        val m = FloatArray(16) // column-major: (row r, column c) at c * 4 + r
        m[0] = 2f * intrinsics.fx / w
        m[8] = 1f - 2f * (intrinsics.cx + 0.5f) / w
        m[5] = 2f * intrinsics.fy / h
        m[9] = 2f * (intrinsics.cy + 0.5f) / h - 1f
        m[10] = -(far + near) / (far - near)
        m[14] = -2f * far * near / (far - near)
        m[11] = -1f
        return Mat4(m)
    }

    /** The orbit viewer's field-of-view projection; [fovYRad] is the full vertical angle. */
    fun orbitProjection(fovYRad: Float, aspect: Float, near: Float, far: Float): Mat4 =
        Mat4.perspective(fovYRad, aspect, near, far)

    /** Pinhole Ccv -> I: `u = fx X / Z + cx`, `v = fy Y / Z + cy`; null when `Z <= 0` (behind the camera). */
    fun project(intrinsics: CameraIntrinsics, pointCv: Vec3): Pixel? {
        if (pointCv.z <= 0f) return null
        return Pixel(
            intrinsics.fx * pointCv.x / pointCv.z + intrinsics.cx,
            intrinsics.fy * pointCv.y / pointCv.z + intrinsics.cy,
        )
    }

    /**
     * Inverse pinhole I -> Ccv: the point at depth [depthM] (along +Z, metres) seen at pixel ([u], [v]):
     * `X = (u - cx) Z / fx`, `Y = (v - cy) Z / fy`, `Z = depthM`. `project(intrinsics, unproject(...))` gives
     * back (u, v) for any `depthM > 0`.
     */
    fun unproject(intrinsics: CameraIntrinsics, u: Float, v: Float, depthM: Float): Vec3 = Vec3(
        (u - intrinsics.cx) * depthM / intrinsics.fx,
        (v - intrinsics.cy) * depthM / intrinsics.fy,
        depthM,
    )

    // ---- W <-> F (G = diag(1, -1, -1), rule 5); every map is its own inverse ----

    /** The W <-> F signs of `sh1..shK` at [shDegree] (K = 0, 3, 8 or 15), as a new array. */
    fun shRestSigns(shDegree: Int): FloatArray = SH_REST_SIGNS.copyOf(SplatCloud.restCoefficients(shDegree))

    /** A position W -> F: `G p`. */
    fun worldToFile(p: Vec3): Vec3 = Vec3(p.x, -p.y, -p.z)

    /** A position F -> W: `G p` (G is its own inverse). */
    fun fileToWorld(p: Vec3): Vec3 = worldToFile(p)

    /** A rotation W -> F: `g q g*` with g the X half-turn, i.e. `(w, x, -y, -z)`; the length is kept. */
    fun worldToFile(q: Quat): Quat {
        checkRotation(q)
        return Quat(q.w, q.x, -q.y, -q.z)
    }

    /** A rotation F -> W, the same map. */
    fun fileToWorld(q: Quat): Quat = worldToFile(q)

    /** 3N positions x, y, z W -> F, as a new array. */
    fun positionsWorldToFile(positions: FloatArray): FloatArray {
        require(positions.size % 3 == 0) { "positions must hold 3 floats per splat, got ${positions.size}" }
        val out = positions.copyOf()
        for (i in out.indices step 3) {
            out[i + 1] = -out[i + 1]
            out[i + 2] = -out[i + 2]
        }
        return out
    }

    /** 3N positions F -> W, the same map. */
    fun positionsFileToWorld(positions: FloatArray): FloatArray = positionsWorldToFile(positions)

    /** 4N quaternions w, x, y, z W -> F, as a new array; a zero quaternion is rejected. */
    fun rotationsWorldToFile(rotations: FloatArray): FloatArray {
        require(rotations.size % 4 == 0) { "rotations must hold 4 floats per splat, got ${rotations.size}" }
        val out = rotations.copyOf()
        for (i in out.indices step 4) {
            require(out[i] != 0f || out[i + 1] != 0f || out[i + 2] != 0f || out[i + 3] != 0f) {
                "zero quaternion for splat ${i / 4} is not a rotation"
            }
            out[i + 2] = -out[i + 2]
            out[i + 3] = -out[i + 3]
        }
        return out
    }

    /** 4N quaternions F -> W, the same map. */
    fun rotationsFileToWorld(rotations: FloatArray): FloatArray = rotationsWorldToFile(rotations)

    /** `shRest` in the [SplatCloud] layout ([splat][channel][coeff]) W -> F at [shDegree], as a new array. */
    fun shRestWorldToFile(shRest: FloatArray, shDegree: Int): FloatArray {
        val signs = shRestSigns(shDegree)
        val k = signs.size
        require(if (k == 0) shRest.isEmpty() else shRest.size % (3 * k) == 0) {
            "shRest must hold 3 * $k floats per splat at degree $shDegree, got ${shRest.size}"
        }
        return FloatArray(shRest.size) { shRest[it] * signs[it % k] }
    }

    /** `shRest` F -> W, the same map. */
    fun shRestFileToWorld(shRest: FloatArray, shDegree: Int): FloatArray = shRestWorldToFile(shRest, shDegree)

    /** A whole cloud W -> F: positions, rotations and SH converted; scales, opacity and DC copied. All arrays new. */
    fun worldToFile(cloud: SplatCloud): SplatCloud = SplatCloud(
        count = cloud.count,
        shDegree = cloud.shDegree,
        positions = positionsWorldToFile(cloud.positions),
        logScales = cloud.logScales.copyOf(),
        rotations = rotationsWorldToFile(cloud.rotations),
        opacityLogits = cloud.opacityLogits.copyOf(),
        shDc = cloud.shDc.copyOf(),
        shRest = shRestWorldToFile(cloud.shRest, cloud.shDegree),
    )

    /** A whole cloud F -> W, the same map. */
    fun fileToWorld(cloud: SplatCloud): SplatCloud = worldToFile(cloud)

    // ---- W <-> NS (rule 7) ----

    /** Nerfstudio `transform_matrix`: `T_W_Cgl` row-major, 4 new rows of 4. */
    fun nerfstudioMatrix(pose: CameraPose): Array<FloatArray> {
        val m = poseMatrix(pose)
        return Array(4) { r -> FloatArray(4) { c -> m[r, c] } }
    }

    private fun checkRotation(q: Quat): Quat {
        require(q.w != 0f || q.x != 0f || q.y != 0f || q.z != 0f) { "zero quaternion is not a rotation: $q" }
        require(q.w.isFinite() && q.x.isFinite() && q.y.isFinite() && q.z.isFinite()) {
            "non-finite quaternion is not a rotation: $q"
        }
        return q
    }
}
