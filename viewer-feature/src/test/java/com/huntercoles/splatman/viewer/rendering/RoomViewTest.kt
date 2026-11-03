package com.huntercoles.splatman.viewer.rendering

import com.huntercoles.splatman.splat.capture.ScanMode
import com.huntercoles.splatman.splat.io.bundle.BundleFrameInput
import com.huntercoles.splatman.splat.io.bundle.CaptureBundleWriter
import com.huntercoles.splatman.splat.math.Quat
import com.huntercoles.splatman.splat.math.Vec3
import com.huntercoles.splatman.splat.model.CameraIntrinsics
import com.huntercoles.splatman.splat.model.CameraPose
import com.huntercoles.splatman.splat.model.SeedPoints
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** #293: a room scan opens from inside (docs/design/screens/viewer.md "Room scans"). */
class RoomViewTest {

    private val eps = 1e-3f

    private fun assertVec(expected: Vec3, actual: Vec3, tolerance: Float = eps) {
        val ok = abs(expected.x - actual.x) < tolerance && abs(expected.y - actual.y) < tolerance &&
            abs(expected.z - actual.z) < tolerance
        assertTrue(ok, "expected $expected, was $actual")
    }

    private fun rad(deg: Float) = Math.toRadians(deg.toDouble()).toFloat()

    /** A camera at [centre] yawed [yawDeg] about world up, pitched [pitchDeg] (up positive), rolled [rollDeg]. */
    private fun pose(centre: Vec3, yawDeg: Float = 0f, pitchDeg: Float = 0f, rollDeg: Float = 0f) = CameraPose(
        Quat.fromAxisAngle(Vec3.UNIT_Y, rad(yawDeg)) * Quat.fromAxisAngle(Vec3.UNIT_X, rad(pitchDeg)) *
            Quat.fromAxisAngle(Vec3.UNIT_Z, rad(rollDeg)),
        centre,
    )

    @Test
    fun `the start eye is the mean camera centre, looking along the first keyframe`() {
        val start = RoomStart.of(
            listOf(
                pose(Vec3(0f, 1f, 0f), yawDeg = 90f),
                pose(Vec3(2f, 1f, 0f), yawDeg = 0f),
                pose(Vec3(1f, 2.5f, 3f), yawDeg = 180f),
            ),
        )!!
        assertVec(Vec3(1f, 1.5f, 1f), start.eye)
        assertVec(Vec3(-1f, 0f, 0f), start.forward)
    }

    @Test
    fun `no poses gives no room start`() {
        assertNull(RoomStart.of(emptyList()))
    }

    @Test
    fun `the room view starts at the eye, levelled, with the first keyframe's pitch kept`() {
        val start = RoomStart.of(listOf(pose(Vec3(0.5f, 1.4f, -0.3f), yawDeg = 30f, pitchDeg = -10f, rollDeg = 25f)))!!
        val camera = GaussianCameraController()
        camera.setRoomView(start, Vec3.ZERO, 4f)

        assertTrue(camera.isRoom)
        assertVec(start.eye, camera.eye())
        val expected = Vec3(-sin(rad(30f)) * cos(rad(10f)), -sin(rad(10f)), -cos(rad(30f)) * cos(rad(10f)))
        assertVec(expected, camera.forward())
        // No roll: the camera's right axis stays horizontal.
        assertEquals(0f, camera.getRotation().rotate(Vec3.UNIT_X).y, eps)
    }

    @Test
    fun `a drag turns the view in place and pitch stops short of straight up`() {
        val camera = GaussianCameraController()
        val start = RoomStart(Vec3(1f, 1.5f, 2f), Vec3(0f, 0f, -1f))
        camera.setRoomView(start, Vec3.ZERO, 4f)

        camera.onRotate(200f, 0f)
        assertVec(start.eye, camera.eye())
        camera.onRotate(0f, 10_000f)
        assertVec(start.eye, camera.eye())
        val pitch = Math.toDegrees(GaussianCameraController.pitchOf(camera.getRotation()).toDouble())
        assertEquals(GaussianCameraController.ROOM_MAX_PITCH_DEG.toDouble(), pitch, 0.01)
        assertEquals(0f, camera.getRotation().rotate(Vec3.UNIT_X).y, eps)
    }

    @Test
    fun `turn and look steps name their direction`() {
        val camera = GaussianCameraController()
        camera.setRoomView(RoomStart(Vec3.ZERO, Vec3(0f, 0f, -1f)), Vec3.ZERO, 4f)
        camera.orbitStep(-1, 0)
        assertTrue(camera.forward().x < 0f, "turn left looks toward -X")
        camera.orbitStep(0, 1)
        assertTrue(camera.forward().y > 0f, "look up")
    }

    @Test
    fun `pinch steps along the view and the eye cannot leave the room`() {
        val camera = GaussianCameraController()
        camera.setRoomView(RoomStart(Vec3.ZERO, Vec3(0f, 0f, -1f)), Vec3.ZERO, 4f)
        camera.zoomStep(1)
        val eye = camera.eye()
        assertEquals(0f, eye.x, eps)
        assertEquals(0f, eye.y, eps)
        assertTrue(eye.z < 0f, "stepped in along -Z")

        repeat(500) { camera.zoomStep(1) }
        assertEquals(4f, camera.eye().length(), eps)
        repeat(500) { camera.onPan(100f, 100f) }
        assertTrue(camera.eye().length() <= 4f + eps)
    }

    @Test
    fun `reset goes back to the start pose and auto-rotate turns about the eye`() {
        val camera = GaussianCameraController()
        val start = RoomStart(Vec3(0.5f, 1f, 0f), Vec3(1f, 0f, 0f))
        camera.setRoomView(start, Vec3.ZERO, 3f)
        camera.onRotate(80f, 20f)
        camera.zoomStep(3)
        camera.resetToInitial(animated = true)
        repeat(100) { camera.advance(0.05f) }
        assertVec(start.eye, camera.eye())
        assertVec(start.forward, camera.forward())

        camera.autoRotate = true
        camera.advance(1f)
        assertVec(start.eye, camera.eye())
    }

    @Test
    fun `framing an object after a room goes back to orbiting`() {
        val camera = GaussianCameraController()
        camera.setRoomView(RoomStart(Vec3.ZERO, Vec3(0f, 0f, -1f)), Vec3.ZERO, 4f)
        camera.setInitialView(Vec3.ZERO, 1f)
        assertFalse(camera.isRoom)
        assertVec(Vec3.ZERO, camera.getTarget())
    }

    @TempDir
    lateinit var tmp: File

    private fun writeBundle(dir: File, mode: ScanMode) {
        val k = CameraIntrinsics(500f, 500f, 319.5f, 239.5f, 640, 480)
        CaptureBundleWriter.write(
            dir,
            listOf(
                BundleFrameInput(1L, k, pose(Vec3(0f, 1f, 0f), yawDeg = 90f), byteArrayOf(1)),
                BundleFrameInput(2L, k, pose(Vec3(2f, 1f, 2f)), byteArrayOf(2)),
            ),
            SeedPoints(0, FloatArray(0), ByteArray(0)),
            mode = mode,
        )
    }

    @Test
    fun `a room bundle whose frames were cleared still opens from inside`() {
        val dir = File(tmp, "room")
        writeBundle(dir, ScanMode.ROOM)
        assertTrue(File(dir, "images").deleteRecursively())

        val start = RoomStart.read(dir)!!
        assertVec(Vec3(1f, 1f, 1f), start.eye)
        assertVec(Vec3(-1f, 0f, 0f), start.forward)
    }

    @Test
    fun `an object bundle gives no room start`() {
        val dir = File(tmp, "object")
        writeBundle(dir, ScanMode.OBJECT)
        assertNull(RoomStart.read(dir))
    }
}
