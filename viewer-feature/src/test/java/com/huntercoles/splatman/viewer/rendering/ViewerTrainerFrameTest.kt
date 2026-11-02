package com.huntercoles.splatman.viewer.rendering

import com.huntercoles.splatman.splat.frames.FrameConversions
import com.huntercoles.splatman.splat.io.ply.PlyReader
import com.huntercoles.splatman.splat.math.Mat4
import com.huntercoles.splatman.splat.math.Quat
import com.huntercoles.splatman.splat.math.Vec3
import com.huntercoles.splatman.splat.model.CameraIntrinsics
import com.huntercoles.splatman.splat.model.CameraPose
import com.huntercoles.splatman.splat.model.SplatCloud
import com.huntercoles.splatman.splat.train.raster.Rasterizer
import com.huntercoles.splatman.splat.train.raster.RenderBuffers
import com.huntercoles.splatman.viewer.rendering.gl.SceneFraming
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Frame suite 4 (card #146): the viewer's orbit camera sees a scene exactly as the trainer's camera does. The up
 * fixture (frame suite 1) is framed with [SceneFraming.of] as the viewer frames a loaded cloud, orbited with
 * [GaussianCameraController], and the controller's [GaussianCameraController.getViewMatrix] is turned into a
 * [CameraPose] (`T_W_Cgl`) and [CameraIntrinsics] (its fovY and aspect). That camera, rendered on the CPU through
 * [Rasterizer.forward], must give the image of the trainer's camera built independently from the orbit (yaw about W
 * +Y, then pitch about the camera's right) with [FrameConversions].
 */
class ViewerTrainerFrameTest {

    private val cloud: SplatCloud = File(fixtureDir(), FIXTURE).inputStream().buffered().use(PlyReader::read)
    private val framing = SceneFraming.of(cloud.positions, cloud.count, ViewerConstants.FOV_DEGREES)
    private val intrinsics = intrinsicsFor(WIDTH, HEIGHT, ViewerConstants.FOV_DEGREES)

    @Test
    fun `an orbit, pan and zoom in the viewer render like the trainer's camera`() {
        val camera = framedCamera()
        camera.onRotate(-YAW_RAD / S, 0f)
        camera.onRotate(0f, -PITCH_RAD / S)
        camera.onPan(PAN_X, PAN_Y)
        camera.onZoom(ZOOM)

        // The intrinsics stand for the viewer's projection: same fovY and aspect, so the same pixels.
        val fromIntrinsics = FrameConversions.projectionFromIntrinsics(
            intrinsics, camera.getNearPlane(), camera.getFarPlane(),
        )
        assertMatrixClose(camera.getProjectionMatrix(), fromIntrinsics, "projection")
        val reference = referencePose(YAW_RAD, PITCH_RAD, PAN_X, PAN_Y, ZOOM)
        val viewerImage = render(cloud, poseOf(camera.getViewMatrix())).color
        val psnr = psnr(viewerImage, render(cloud, reference).color)
        assertTrue(psnr >= MIN_PSNR, "viewer camera vs trainer camera: $psnr dB, want >= $MIN_PSNR")
        assertMatrixClose(FrameConversions.viewMatrix(reference), camera.getViewMatrix(), "view")
    }

    @Test
    fun `in the default framing the known-down part is in the lower half of the image`() {
        val m = halfMass(knownDown(), poseOf(framedCamera().getViewMatrix()))
        assertTrue(m.total > MIN_MASS, "known-down part not on the image (mass ${m.total})")
        assertTrue(
            m.bottom >= IN_HALF * m.total,
            "only ${m.bottom / m.total} of the known-down mass is in the lower half",
        )
    }

    /**
     * Hardening: pitch is not clamped. An orbit 120 degrees over the pole keeps going: the camera's up stays the
     * orbit's (now pointing down in W), the image is the trainer's camera at that orbit and the scene is upside down.
     */
    @Test
    fun `an orbit past the pole is not clamped and keeps the orbit's up`() {
        val camera = framedCamera()
        camera.onRotate(-YAW_RAD / S, 0f)
        repeat(POLE_STEPS) { camera.onRotate(0f, -POLE_PITCH_RAD / POLE_STEPS / S) }

        val reference = referencePose(YAW_RAD, POLE_PITCH_RAD, 0f, 0f, 1f)
        val pose = poseOf(camera.getViewMatrix())
        val up = pose.rotation.rotate(Vec3.UNIT_Y)
        assertTrue(up.y < 0f, "camera up $up was clamped at the pole")
        val psnr = psnr(render(cloud, pose).color, render(cloud, reference).color)
        assertTrue(psnr >= MIN_PSNR, "past the pole: viewer vs trainer camera $psnr dB, want >= $MIN_PSNR")
        assertMatrixClose(FrameConversions.viewMatrix(reference), camera.getViewMatrix(), "view past the pole")
        val m = halfMass(knownDown(), pose)
        assertTrue(m.total > MIN_MASS, "known-down part not on the image (mass ${m.total})")
        assertTrue(m.top >= IN_HALF * m.total, "upside down, only ${m.top / m.total} of the known-down mass is on top")
    }

    // ---- helpers ----

    /** The camera as ViewerScreen sets it up for a loaded cloud. */
    private fun framedCamera() = GaussianCameraController().apply {
        setAspectRatio(WIDTH.toFloat() / HEIGHT)
        setTarget(framing.target)
        setDistance(framing.distance)
    }

    /**
     * The trainer's camera for the orbit, built without the controller: rotation `Ry(yaw) Rx(pitch)` (yaw about W +Y,
     * pitch about the camera's right), the target panned along the camera's right and up by [PAN_SCALE] of the
     * distance per unit, then the distance scaled by [zoom]; the eye sits on the camera's +Z at that distance.
     */
    private fun referencePose(yaw: Float, pitch: Float, panX: Float, panY: Float, zoom: Float): CameraPose {
        val r = Quat.fromAxisAngle(Vec3.UNIT_Y, yaw) * Quat.fromAxisAngle(Vec3.UNIT_X, pitch)
        val d0 = framing.distance
        val target = framing.target + (r.rotate(Vec3.UNIT_X) * panX + r.rotate(Vec3.UNIT_Y) * panY) * (PAN_SCALE * d0)
        return CameraPose(r, target + r.rotate(Vec3.UNIT_Z) * (d0 * zoom))
    }

    /**
     * `T_W_Cgl` of a view matrix: its rigid inverse, rotation block to a quaternion. The block must be a proper
     * rotation (orthonormal, det +1): a mirrored view has no camera pose, and the trainer could never render it.
     */
    private fun poseOf(view: Mat4): CameraPose {
        val t = view.inverse()
        val c = (0 until 3).map { j -> Vec3(t[0, j], t[1, j], t[2, j]) }
        for (j in 0 until 3) assertEquals(1f, c[j].length(), EPS, "view rotation column $j is not unit")
        assertEquals(0f, c[0] dot c[1], EPS, "view rotation is not orthogonal")
        val det = (c[0] cross c[1]) dot c[2]
        assertEquals(1f, det, EPS, "view rotation has det $det: the view is mirrored")
        val pose = CameraPose(quatFromColumns(c), Vec3(t[0, 3], t[1, 3], t[2, 3]))
        assertMatrixClose(view, FrameConversions.viewMatrix(pose), "pose round trip")
        return pose
    }

    /** The unit quaternion of the rotation whose columns are [c] (Shepperd's method, largest diagonal term first). */
    private fun quatFromColumns(c: List<Vec3>): Quat {
        val m00 = c[0].x
        val m11 = c[1].y
        val m22 = c[2].z
        val m01 = c[1].x
        val m02 = c[2].x
        val m10 = c[0].y
        val m12 = c[2].y
        val m20 = c[0].z
        val m21 = c[1].z
        val trace = m00 + m11 + m22
        val q = when {
            trace > 0f -> {
                val s = sqrt(trace + 1f) * 2f
                Quat(s / 4, (m21 - m12) / s, (m02 - m20) / s, (m10 - m01) / s)
            }
            m00 > m11 && m00 > m22 -> {
                val s = sqrt(1f + m00 - m11 - m22) * 2f
                Quat((m21 - m12) / s, s / 4, (m01 + m10) / s, (m02 + m20) / s)
            }
            m11 > m22 -> {
                val s = sqrt(1f + m11 - m00 - m22) * 2f
                Quat((m02 - m20) / s, (m01 + m10) / s, s / 4, (m12 + m21) / s)
            }
            else -> {
                val s = sqrt(1f + m22 - m00 - m11) * 2f
                Quat((m10 - m01) / s, (m02 + m20) / s, (m12 + m21) / s, s / 4)
            }
        }
        return q.normalized()
    }

    private fun render(scene: SplatCloud, pose: CameraPose): RenderBuffers {
        val out = RenderBuffers(intrinsics.width, intrinsics.height)
        Rasterizer(threads = 1).use { it.forward(scene, intrinsics, pose, out) }
        return out
    }

    private data class HalfMass(val top: Double, val bottom: Double) {
        val total: Double get() = top + bottom
    }

    /** Summed render alpha in each image half of the splats [keep] rendered alone from [pose]. */
    private fun halfMass(keep: IntArray, pose: CameraPose): HalfMass {
        val n = keep.size
        val part = SplatCloud(
            n, 0,
            FloatArray(3 * n) { cloud.positions[3 * keep[it / 3] + it % 3] },
            FloatArray(3 * n) { cloud.logScales[3 * keep[it / 3] + it % 3] },
            FloatArray(4 * n) { cloud.rotations[4 * keep[it / 4] + it % 4] },
            FloatArray(n) { cloud.opacityLogits[keep[it]] },
            FloatArray(3 * n) { cloud.shDc[3 * keep[it / 3] + it % 3] },
            FloatArray(0),
        )
        val out = render(part, pose)
        var top = 0.0
        var bottom = 0.0
        for (y in 0 until out.height) for (x in 0 until out.width) {
            val a = out.alpha[y * out.width + x].toDouble()
            if (2 * y < out.height) top += a else bottom += a
        }
        return HalfMass(top, bottom)
    }

    /**
     * The known-down part, picked from the raw file without the PLY reader: the [KNOWN_SHARE] of splats with the
     * highest file y (file y points down, ADR 0003), so physically the lowest. Splat order is the reader's.
     */
    private fun knownDown(): IntArray {
        val bytes = File(fixtureDir(), FIXTURE).readBytes()
        val marker = "end_header\n".toByteArray(Charsets.US_ASCII)
        val end = (0..bytes.size - marker.size).first { at -> marker.indices.all { bytes[at + it] == marker[it] } } +
            marker.size
        val lines = String(bytes, 0, end, Charsets.US_ASCII).lines()
        val props = lines.filter { it.startsWith("property float ") }.map { it.removePrefix("property float ") }
        val n = lines.first { it.startsWith("element vertex ") }.removePrefix("element vertex ").toInt()
        assertEquals(cloud.count, n, "$FIXTURE splat count")
        val yAt = props.indexOf("y")
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val fileY = FloatArray(n) { bb.getFloat(end + (it * props.size + yAt) * 4) }
        return (0 until n).sortedWith(compareBy<Int> { -fileY[it] }.thenBy { it })
            .take((n * KNOWN_SHARE).toInt()).sorted().toIntArray()
    }

    private fun psnr(a: FloatArray, b: FloatArray): Double {
        var sum = 0.0
        for (i in a.indices) {
            val d = a[i].coerceIn(0f, 1f) - b[i].coerceIn(0f, 1f).toDouble()
            sum += d * d
        }
        val mse = sum / a.size
        return if (mse == 0.0) Double.POSITIVE_INFINITY else -10.0 * log10(mse)
    }

    private fun assertMatrixClose(expected: Mat4, actual: Mat4, what: String) {
        val scale = 1f + expected.values.maxOf { abs(it) }
        for (i in 0 until 16) {
            assertEquals(expected.values[i], actual.values[i], EPS * scale, "$what matrix differs at $i: $actual")
        }
    }

    private companion object {
        const val FIXTURE = "cactus-up.ply"
        const val WIDTH = 96
        const val HEIGHT = 128
        const val S = ViewerConstants.ROTATION_SENSITIVITY
        const val PAN_SCALE = ViewerConstants.PAN_SENSITIVITY
        val YAW_RAD = Math.toRadians(90.0).toFloat()

        /** Negative pitch about the camera's right raises the camera: 30 degrees above the target. */
        val PITCH_RAD = Math.toRadians(-30.0).toFloat()

        /** 120 degrees over the top: past the pole by 30. */
        val POLE_PITCH_RAD = Math.toRadians(-120.0).toFloat()
        const val POLE_STEPS = 12
        const val PAN_X = 8f
        const val PAN_Y = -5f
        const val ZOOM = 0.8f
        const val MIN_PSNR = 40.0
        const val MIN_MASS = 50.0
        const val IN_HALF = 0.8
        const val KNOWN_SHARE = 0.2
        const val EPS = 1e-4f

        /** Square pixels, principal point at the centre, fy from the vertical field of view. */
        fun intrinsicsFor(width: Int, height: Int, fovYDeg: Float): CameraIntrinsics {
            val f = (height / 2.0 / tan(Math.toRadians(fovYDeg / 2.0))).toFloat()
            return CameraIntrinsics(f, f, (width - 1) / 2f, (height - 1) / 2f, width, height)
        }

        /** splat-train's frame fixtures, found by walking up to the repository root. */
        fun fixtureDir(): File {
            var dir: File? = File(System.getProperty("user.dir")).absoluteFile
            while (dir != null && !File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile
            val frames = dir?.let { File(it, "splat-train/src/test/resources/frames") }
            check(frames != null && frames.isDirectory) { "splat-train/src/test/resources/frames is missing" }
            return frames
        }
    }
}
