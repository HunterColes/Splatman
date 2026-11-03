package com.huntercoles.splatman.splat.io.bundle

import com.huntercoles.splatman.splat.math.Quat
import com.huntercoles.splatman.splat.math.Vec3
import com.huntercoles.splatman.splat.model.CameraIntrinsics
import com.huntercoles.splatman.splat.model.CameraPose
import com.huntercoles.splatman.splat.model.DepthImage
import com.huntercoles.splatman.splat.model.SeedPoints
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import kotlin.math.abs

class CaptureBundleTest {

    @TempDir
    lateinit var tmp: File

    private val colour = CameraIntrinsics(500f, 500.5f, 319.5f, 239.5f, 640, 480)
    private val depthK = CameraIntrinsics(3f, 3f, 0.5f, 0.5f, 2, 2)
    private val seed = SeedPoints(2, floatArrayOf(0.1f, -0.2f, 1.5f, 2f, 3f, -4f), byteArrayOf(255.toByte(), 0, 17, 1, 2, 200.toByte()))

    /** Exact poses (no rounding in their matrices) so the golden file is readable. */
    private fun goldenFrames() = listOf(
        BundleFrameInput(1_000_000_001L, colour, CameraPose(Quat.IDENTITY, Vec3(0.5f, 1.5f, -2f)), byteArrayOf(1, 2, 3)),
        BundleFrameInput(
            2_000_000_002L,
            colour,
            CameraPose(Quat(0f, 0f, 1f, 0f), Vec3(1f, 0f, 0f)),
            byteArrayOf(4, 5),
            DepthImage(depthK, floatArrayOf(1f, 1.25f, 0f, 3.5f), null),
        ),
    )

    @Test
    fun `write then read gives the same frames, seed and depth`() {
        val axis = Vec3(0.3f, -0.8f, 0.52f)
        val frames = listOf(
            BundleFrameInput(7L, colour, CameraPose(Quat.fromAxisAngle(axis.normalized(), 1.1f), Vec3(0.25f, -1f, 3f)), byteArrayOf(9)),
            BundleFrameInput(
                8L,
                colour.scaled(320, 240),
                CameraPose(Quat.fromAxisAngle(Vec3(0f, 1f, 0f), 3.0f), Vec3(-2f, 0.5f, 0.125f)),
                byteArrayOf(8, 7),
                DepthImage(depthK, floatArrayOf(0.5f, 1f, 1.5f, 2f), null),
            ),
            BundleFrameInput(9L, colour, CameraPose(Quat(0f, 1f, 0f, 0f), Vec3(0f, 0f, 0f)), byteArrayOf()),
        )
        val dir = File(tmp, "scan")
        CaptureBundleWriter.write(dir, frames, seed)
        val bundle = CaptureBundleReader.read(dir)

        assertEquals(SPLATMAN_BUNDLE_VERSION, bundle.version)
        assertEquals(frames.size, bundle.frames.size)
        frames.zip(bundle.frames).forEach { (want, got) ->
            assertEquals(want.timestampNs, got.timestampNs)
            assertEquals(want.intrinsics, got.intrinsics)
            assertPoseEquals(want.pose, got.pose)
            assertArrayEquals(want.jpeg, got.imageFile.readBytes())
            val depth = CaptureBundleReader.readDepth(got)
            if (want.depth == null) {
                assertNull(got.depthFile)
                assertNull(depth)
            } else {
                assertEquals(want.depth!!.intrinsics, depth!!.intrinsics)
                assertArrayEquals(want.depth!!.depthM, depth.depthM)
            }
        }
        val readSeed = bundle.seed!!
        assertEquals(seed.count, readSeed.count)
        assertArrayEquals(seed.positions, readSeed.positions)
        assertArrayEquals(seed.colours, readSeed.colours)
    }

    @Test
    fun `written JSON matches the golden file for a two-frame bundle`() {
        val dir = File(tmp, "golden")
        CaptureBundleWriter.write(dir, goldenFrames(), seed)
        val actual = File(dir, "transforms.json").readText()
        val expected = javaClass.getResource("/bundle/golden_transforms.json")!!.readText()
        if (actual != expected) {
            File("build/golden-actual").mkdirs()
            File("build/golden-actual/transforms.json").writeText(actual)
        }
        assertEquals(expected, actual)
        assertTrue(File(dir, "images/frame_00001.jpg").isFile)
        assertTrue(File(dir, "depth/frame_00002.bin").isFile)
        assertTrue(File(dir, "seed.ply").isFile)
    }

    @Test
    fun `the subject round-trips in frame W`() {
        val dir = File(tmp, "subject")
        CaptureBundleWriter.write(dir, goldenFrames(), seed, Vec3(0.5f, -1.25f, 2f))
        assertTrue(File(dir, "transforms.json").readText().contains("\"splatman_subject\""))
        assertEquals(Vec3(0.5f, -1.25f, 2f), CaptureBundleReader.read(dir).subject)
    }

    @Test
    fun `a bundle without a subject reads it as null`() {
        val dir = File(tmp, "nosubject")
        CaptureBundleWriter.write(dir, goldenFrames(), seed)
        assertTrue(!File(dir, "transforms.json").readText().contains("splatman_subject"))
        assertNull(CaptureBundleReader.read(dir).subject)
    }

    @Test
    fun `a malformed subject is reported by key`() {
        val dir = File(tmp, "badsubject")
        CaptureBundleWriter.write(dir, goldenFrames(), seed)
        val json = File(dir, "transforms.json")
        json.writeText(json.readText().replace("\"splatman_version\": 1", "\"splatman_version\": 1, \"splatman_subject\": [1, 2]"))
        val e = assertThrows<CaptureBundleException> { CaptureBundleReader.read(dir) }
        assertTrue(e.message!!.contains("splatman_subject"), e.message)
    }

    @Test
    fun `a newer splatman_version is rejected`() {
        val dir = File(tmp, "newer")
        CaptureBundleWriter.write(dir, goldenFrames(), seed)
        val json = File(dir, "transforms.json")
        json.writeText(json.readText().replace("\"splatman_version\": 1", "\"splatman_version\": 2"))
        val e = assertThrows<CaptureBundleException> { CaptureBundleReader.read(dir) }
        assertTrue(e.message!!.contains("splatman_version 2"), e.message)
    }

    @Test
    fun `unknown keys are ignored and a missing version reads as 1`() {
        val dir = File(tmp, "nerfstudio")
        File(dir, "images").mkdirs()
        File(dir, "images/a.png").writeBytes(byteArrayOf(1))
        File(dir, "transforms.json").writeText(
            """
            {
              "camera_model": "OPENCV",
              "aabb_scale": 16,
              "applied_transform": [[1, 0, 0, 0], [0, 1, 0, 0], [0, 0, 1, 0]],
              "frames": [
                {
                  "file_path": "images/a.png",
                  "colmap_im_id": 3,
                  "transform_matrix": [[1, 0, 0, 1], [0, 1, 0, 2], [0, 0, 1, 3], [0, 0, 0, 1]],
                  "fl_x": 100.0, "fl_y": 101.0, "cx": 50, "cy": 40, "w": 100, "h": 80
                }
              ]
            }
            """.trimIndent(),
        )
        val bundle = CaptureBundleReader.read(dir)
        assertEquals(1, bundle.version)
        assertNull(bundle.seed)
        val f = bundle.frames.single()
        assertEquals(CameraIntrinsics(100f, 101f, 50f, 40f, 100, 80), f.intrinsics)
        assertPoseEquals(CameraPose(Quat.IDENTITY, Vec3(1f, 2f, 3f)), f.pose)
    }

    @Test
    fun `a missing image file is reported by name`() {
        val dir = File(tmp, "missing")
        CaptureBundleWriter.write(dir, goldenFrames(), seed)
        assertTrue(File(dir, "images/frame_00002.jpg").delete())
        val e = assertThrows<CaptureBundleException> { CaptureBundleReader.read(dir) }
        assertTrue(e.message!!.contains("images/frame_00002.jpg"), e.message)
    }

    @Test
    fun `a failed write leaves no transforms json and no temp files`() {
        val dir = File(tmp, "killed")
        CaptureBundleWriter.write(dir, goldenFrames(), seed)
        assertTrue(File(dir, "transforms.json").isFile)
        // Rewrite where the second image cannot land: a non-empty directory sits at its path.
        assertTrue(File(dir, "images/frame_00002.jpg").delete())
        File(dir, "images/frame_00002.jpg/blocker").apply { parentFile.mkdirs() }.writeBytes(byteArrayOf(0))
        assertThrows<IOException> { CaptureBundleWriter.write(dir, goldenFrames(), seed) }
        assertFalse(File(dir, "transforms.json").exists(), "a half bundle must not keep a transforms.json")
        assertEquals(emptyList<String>(), dir.walk().filter { it.name.endsWith(".tmp") }.map { it.path }.toList())
        val e = assertThrows<CaptureBundleException> { CaptureBundleReader.read(dir) }
        assertTrue(e.message!!.contains("not a capture bundle"), e.message)
    }

    private fun assertPoseEquals(want: CameraPose, got: CameraPose) {
        val w = if (want.rotation.w < 0) Quat(-want.rotation.w, -want.rotation.x, -want.rotation.y, -want.rotation.z) else want.rotation
        val g = got.rotation
        val sign = if (w.w == 0f && g.w == 0f && dot(w, g) < 0) -1f else 1f
        val tol = 1e-6f
        assertClose(w.w, sign * g.w, tol)
        assertClose(w.x, sign * g.x, tol)
        assertClose(w.y, sign * g.y, tol)
        assertClose(w.z, sign * g.z, tol)
        assertClose(want.translationM.x, got.translationM.x, tol)
        assertClose(want.translationM.y, got.translationM.y, tol)
        assertClose(want.translationM.z, got.translationM.z, tol)
    }

    private fun dot(a: Quat, b: Quat) = a.w * b.w + a.x * b.x + a.y * b.y + a.z * b.z

    private fun assertClose(a: Float, b: Float, tol: Float) =
        assertTrue(abs(a - b) <= tol, "expected $a, got $b (tolerance $tol)")
}
