package com.huntercoles.splatman.splat.sfm

import com.huntercoles.splatman.splat.math.Quat
import com.huntercoles.splatman.splat.math.Vec3
import com.huntercoles.splatman.splat.model.CameraIntrinsics
import com.huntercoles.splatman.splat.model.CameraPose
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * #223: SfmAlignment.align puts a reconstruction into W (ADR 0003, ADR 0012): gravity to -Y (the accelerometer reads
 * +9.81 along true up at rest), origin at the median sparse point, median camera-to-origin distance 1.0.
 *
 * The truth: 12 cameras on a ring of radius 1 at height 0.25 around the origin, each looking at it, frame 5
 * unregistered; 41 points symmetric about the origin (coordinate-wise median exactly 0). The reconstruction is that
 * scene under a random similarity (scale 0.1..10, any rotation, shift up to 5), in OpenCV world-to-camera form,
 * with every registered frame observing every point. Alignment fixes everything but the yaw about +Y, so the checks
 * are yaw-free: up = +Y within 0.5 degrees, each camera at height 0.25, |c| and |p| as in the truth.
 */
class SfmAlignmentTest {

    private val k = CameraIntrinsics(500f, 500f, 320f, 240f, 640, 480)
    private val frameCount = 12
    private val unregisteredFrame = 5
    private val height = 0.25f
    private val upTolRad = 0.5 * PI / 180
    private val xHalfTurn = Quat(0f, 1f, 0f, 0f) // F = diag(1, -1, -1): R_W_Ccv = R_W_Cgl F

    private class Scene(
        val model: SparseModel,
        val accel: ImuSamples,
        val rotationCcvImu: Quat,
        val frames: List<SfmFrame>,
        val truePoses: List<CameraPose>,
        val truePoints: List<Vec3>,
    )

    private fun randomRotation(rng: Random): Quat =
        Quat(rng.nextFloat() * 2 - 1, rng.nextFloat() * 2 - 1, rng.nextFloat() * 2 - 1, rng.nextFloat() * 2 - 1)
            .normalized()

    private fun randomVec(rng: Random, half: Float) =
        Vec3((rng.nextFloat() * 2 - 1) * half, (rng.nextFloat() * 2 - 1) * half, (rng.nextFloat() * 2 - 1) * half)

    /** The scene under a random similarity; [mirror] reflects the reconstruction across a random plane as well. */
    private fun scene(rng: Random, mirror: Boolean = false): Scene {
        val rho = sqrt(1f - height * height)
        val pitch = Quat.fromAxisAngle(Vec3.UNIT_X, -atan2(height, rho)) // tilt -Z down to the origin
        val truePoses = List(frameCount) { f ->
            val yaw = (2 * PI * f / frameCount).toFloat()
            CameraPose(Quat.fromAxisAngle(Vec3.UNIT_Y, yaw) * pitch, Vec3(rho * sin(yaw), height, rho * cos(yaw)))
        }
        val half = List(20) { randomVec(rng, 0.3f) }
        val truePoints = listOf(Vec3.ZERO) + half + half.map { -it }
        val colours = ByteArray(3 * truePoints.size) { rng.nextInt(256).toByte() }

        val rotationCcvImu = randomRotation(rng)
        val s = 10.0.pow(rng.nextDouble(-1.0, 1.0)).toFloat()
        val rVW = randomRotation(rng)
        val tV = randomVec(rng, 5f)
        val n = randomVec(rng, 1f).normalized()
        fun toV(p: Vec3): Vec3 {
            val q = rVW.rotate(p) * s + tV
            return if (mirror) q - n * (2f * (n dot q)) else q
        }
        val qn = Quat(0f, n.x, n.y, n.z) // as a matrix -(I - 2 n n^T): conjugating by it mirrors a rotation

        val registered = BooleanArray(frameCount) { it != unregisteredFrame }
        val rotations = FloatArray(4 * frameCount)
        val translations = FloatArray(3 * frameCount)
        val stamps = mutableListOf<Long>()
        val accelXyz = mutableListOf<Vec3>()
        val frames = List(frameCount) { f -> SfmFrame(f, f * 1000 * MS, "f$f.jpg", k) }
        val obsFrame = mutableListOf<Int>()
        val obsPoint = mutableListOf<Int>()
        val obsUv = mutableListOf<Float>()
        for (f in 0 until frameCount) {
            val rWCcv = truePoses[f].rotation * xHalfTurn
            val rCcvW = rWCcv.conjugate()
            // accelerometer: +9.81 along true up, in the IMU frame; 41 samples in +-200 ms around the frame
            val aImu = rotationCcvImu.conjugate().rotate(rCcvW.rotate(Vec3(0f, 9.81f, 0f)))
            for (j in -20..20) {
                stamps += frames[f].timestampNs + j * 10 * MS
                accelXyz += aImu
            }
            var rVCcv = rVW * rWCcv
            if (mirror) rVCcv = qn * rVCcv * qn
            val rCcvV = rVCcv.conjugate()
            val c = toV(truePoses[f].translationM)
            val t = -rCcvV.rotate(c)
            rotations[4 * f] = rCcvV.w; rotations[4 * f + 1] = rCcvV.x
            rotations[4 * f + 2] = rCcvV.y; rotations[4 * f + 3] = rCcvV.z
            translations[3 * f] = t.x; translations[3 * f + 1] = t.y; translations[3 * f + 2] = t.z
            if (!registered[f]) continue
            truePoints.forEachIndexed { pi, p ->
                val pc = rCcvW.rotate(p - truePoses[f].translationM)
                obsFrame += f
                obsPoint += pi
                obsUv += k.fx * pc.x / pc.z + k.cx
                obsUv += k.fy * pc.y / pc.z + k.cy
            }
        }
        val points = FloatArray(3 * truePoints.size)
        truePoints.forEachIndexed { i, p ->
            val v = toV(p)
            points[3 * i] = v.x; points[3 * i + 1] = v.y; points[3 * i + 2] = v.z
        }
        val model = SparseModel(
            registered, rotations, translations, points, colours,
            obsFrame.toIntArray(), obsPoint.toIntArray(), obsUv.toFloatArray(),
        )
        val byTime = stamps.zip(accelXyz).toMap()
        val accel = imuAt(stamps) { byTime.getValue(it) }
        return Scene(model, accel, rotationCcvImu, frames, truePoses, truePoints)
    }

    private fun align(sc: Scene) = SfmAlignment.align(sc.model, sc.accel, sc.rotationCcvImu, sc.frames)

    @Test
    fun `A4 unregistered frames stay null, registered ones get a pose, every point is kept`() {
        val sc = scene(Random(42))
        val (poses, seed) = align(sc)
        assertEquals(frameCount, poses.size)
        for (f in 0 until frameCount) {
            if (f == unregisteredFrame) assertNull(poses[f], "frame $f") else assertNotNull(poses[f], "frame $f")
        }
        assertEquals(sc.truePoints.size, seed.count)
        assertArrayEquals(sc.model.colours, seed.colours)
    }

    @Test
    fun `A5 random similarities come back with up +Y within half a degree`() {
        val rng = Random(42)
        repeat(8) { trial ->
            val sc = scene(rng)
            val (poses, _) = align(sc)
            for (f in 0 until frameCount) {
                val pose = poses[f] ?: continue
                // true up seen from the camera, carried out by the aligned pose, must be +Y
                val upInCamera = sc.truePoses[f].rotation.conjugate().rotate(Vec3.UNIT_Y)
                val up = pose.rotation.rotate(upInCamera)
                assertTrue(vecAngle(up, Vec3.UNIT_Y) < upTolRad, "trial $trial frame $f: up $up")
            }
        }
    }

    @Test
    fun `A5 random similarities come back with the camera ring at unit median radius around the median point`() {
        val rng = Random(43)
        repeat(8) { trial ->
            val sc = scene(rng)
            val (poses, seed) = align(sc)
            val radii = poses.filterNotNull().map { it.translationM.length() }.sorted()
            assertEquals(1f, radii[radii.size / 2], 1e-3f, "trial $trial median radius")
            poses.forEachIndexed { f, pose ->
                if (pose == null) return@forEachIndexed
                assertEquals(1f, pose.translationM.length(), 1e-3f, "trial $trial frame $f radius")
                assertEquals(height, pose.translationM.y, 2e-3f, "trial $trial frame $f height")
            }
            sc.truePoints.forEachIndexed { i, p ->
                val q = Vec3(seed.positions[3 * i], seed.positions[3 * i + 1], seed.positions[3 * i + 2])
                assertEquals(p.length(), q.length(), 1e-3f, "trial $trial point $i distance from origin")
                assertEquals(p.y, q.y, 2e-3f, "trial $trial point $i height")
            }
        }
    }

    @Test
    fun `A5 a reconstruction mirrored in handedness is rejected`() {
        val rng = Random(44)
        repeat(4) { trial ->
            val sc = scene(rng, mirror = true)
            assertThrows<IllegalArgumentException>("trial $trial") { align(sc) }
        }
    }
}
