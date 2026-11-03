package com.huntercoles.splatman.splat.sfm

import com.huntercoles.splatman.splat.frames.FrameConversions
import com.huntercoles.splatman.splat.math.Quat
import com.huntercoles.splatman.splat.math.Vec3
import com.huntercoles.splatman.splat.model.CameraIntrinsics
import com.huntercoles.splatman.splat.model.CameraPose
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import kotlin.math.PI

/**
 * #223 rework: the native side leaves an unregistered frame's rotation and translation zero-filled (the FloatArray
 * default). align must not touch that slot: it aligns, and the frame comes back null.
 */
class SfmAlignmentZeroSlotTest {

    private val k = CameraIntrinsics(500f, 500f, 320f, 240f, 640, 480)

    @Test
    fun `an unregistered frame with zero rotation and translation aligns and comes back null`() {
        // frames 0 and 2 look at the origin from distance 2 (GL cameras look along -Z); frame 1 is unregistered
        val truePoses = mapOf(
            0 to CameraPose(Quat.IDENTITY, Vec3(0f, 0f, 2f)),
            2 to CameraPose(Quat.fromAxisAngle(Vec3.UNIT_Y, (PI / 2).toFloat()), Vec3(2f, 0f, 0f)),
        )
        val points = listOf(
            Vec3.ZERO, Vec3(0.2f, 0.1f, 0f), Vec3(-0.2f, -0.1f, 0f),
            Vec3(0f, 0.2f, 0.1f), Vec3(0f, -0.2f, -0.1f),
        )
        val registered = booleanArrayOf(true, false, true)
        val rotations = FloatArray(12) // slot 1 stays all zero
        val translations = FloatArray(9)
        val frames = List(3) { f -> SfmFrame(f, f * 1000 * MS, "f$f.jpg", k) }
        val obsFrame = mutableListOf<Int>()
        val obsPoint = mutableListOf<Int>()
        val obsUv = mutableListOf<Float>()
        val stamps = mutableListOf<Long>()
        val accelXyz = mutableListOf<Vec3>()
        for ((f, pose) in truePoses) {
            val (r, t) = FrameConversions.poseToOpenCv(pose)
            rotations[4 * f] = r.w
            rotations[4 * f + 1] = r.x
            rotations[4 * f + 2] = r.y
            rotations[4 * f + 3] = r.z
            translations[3 * f] = t.x
            translations[3 * f + 1] = t.y
            translations[3 * f + 2] = t.z
            points.forEachIndexed { pi, p ->
                val px = FrameConversions.project(k, r.rotate(p) + t)!!
                obsFrame += f
                obsPoint += pi
                obsUv += px.u
                obsUv += px.v
            }
            // identity IMU-to-camera: the accelerometer reads +9.81 along true up, in Ccv
            stamps += frames[f].timestampNs
            accelXyz += r.rotate(Vec3(0f, 9.81f, 0f))
        }
        val xyz = FloatArray(3 * points.size)
        points.forEachIndexed { i, p ->
            xyz[3 * i] = p.x
            xyz[3 * i + 1] = p.y
            xyz[3 * i + 2] = p.z
        }
        val model = SparseModel(
            registered, rotations, translations, xyz, ByteArray(3 * points.size),
            obsFrame.toIntArray(), obsPoint.toIntArray(), obsUv.toFloatArray(),
        )
        val byTime = stamps.zip(accelXyz).toMap()

        val (poses, seed) = SfmAlignment.align(model, imuAt(stamps) { byTime.getValue(it) }, Quat.IDENTITY, frames)

        assertEquals(3, poses.size)
        assertNull(poses[1])
        assertEquals(1f, poses[0]!!.translationM.length(), 1e-4f)
        assertEquals(1f, poses[2]!!.translationM.length(), 1e-4f)
        assertEquals(points.size, seed.count)
    }
}
