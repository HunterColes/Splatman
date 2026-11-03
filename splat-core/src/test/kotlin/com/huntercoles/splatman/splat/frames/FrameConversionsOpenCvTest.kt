package com.huntercoles.splatman.splat.frames

import com.huntercoles.splatman.splat.math.Quat
import com.huntercoles.splatman.splat.math.Vec3
import com.huntercoles.splatman.splat.math.assertQuat
import com.huntercoles.splatman.splat.math.assertVec
import com.huntercoles.splatman.splat.model.CameraPose
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * #223: OpenCV world-to-camera `(R_Ccv_W, t)` <-> [CameraPose] `T_W_Cgl` (ADR 0003 conversion 2): invert, then
 * `F = diag(1, -1, -1)`. The inverse is `poseToOpenCv(pose): Pair<Quat, Vec3>` (rotation, translation).
 * Tolerance 1e-6 (ADR 0009); quaternions compared up to sign.
 */
class FrameConversionsOpenCvTest {

    private val fc = FrameConversions

    private fun sameSign(reference: Quat, q: Quat): Quat {
        val dot = reference.w * q.w + reference.x * q.x + reference.y * q.y + reference.z * q.z
        return if (dot < 0f) Quat(-q.w, -q.x, -q.y, -q.z) else q
    }

    private fun randomRotation(rng: Random): Quat =
        Quat(rng.nextFloat() * 2 - 1, rng.nextFloat() * 2 - 1, rng.nextFloat() * 2 - 1, rng.nextFloat() * 2 - 1)
            .normalized()

    private fun randomVec(rng: Random, half: Float) =
        Vec3((rng.nextFloat() * 2 - 1) * half, (rng.nextFloat() * 2 - 1) * half, (rng.nextFloat() * 2 - 1) * half)

    @Test
    fun `A3 identity OpenCV rotation with the world 2 m ahead is a camera at z -2 turned half about X`() {
        val pose = fc.poseFromOpenCv(Quat.IDENTITY, Vec3(0f, 0f, 2f))
        assertVec(Vec3(0f, 0f, -2f), pose.translationM)
        val expected = Quat(0f, 1f, 0f, 0f) // R_W_Cgl = R_W_Ccv F = F
        assertQuat(expected, sameSign(expected, pose.rotation))
    }

    @Test
    fun `A3 a world point lands at the same Ccv coordinates through OpenCV and through the pose`() {
        val rng = Random(42)
        repeat(200) {
            val r = randomRotation(rng)
            val t = randomVec(rng, 2f)
            val p = randomVec(rng, 3f)
            val pose = fc.poseFromOpenCv(r, t)
            val viaOpenCv = r.rotate(p) + t
            val viaPose = fc.glToCv(pose.rotation.conjugate().rotate(p - pose.translationM))
            assertVec(viaOpenCv, viaPose, 1e-5f)
        }
    }

    @Test
    fun `A3 OpenCV to pose and back is exact`() {
        val rng = Random(7)
        repeat(200) {
            val r = randomRotation(rng)
            val t = randomVec(rng, 1f)
            val (r2, t2) = fc.poseToOpenCv(fc.poseFromOpenCv(r, t))
            assertQuat(r, sameSign(r, r2))
            assertVec(t, t2)
        }
    }

    @Test
    fun `A3 pose to OpenCV and back is exact`() {
        val rng = Random(11)
        repeat(200) {
            val pose = CameraPose(randomRotation(rng), randomVec(rng, 1f))
            val (r, t) = fc.poseToOpenCv(pose)
            val back = fc.poseFromOpenCv(r, t)
            assertQuat(pose.rotation, sameSign(pose.rotation, back.rotation))
            assertVec(pose.translationM, back.translationM)
        }
    }
}
