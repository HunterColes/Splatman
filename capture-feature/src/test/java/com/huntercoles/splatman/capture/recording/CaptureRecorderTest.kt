package com.huntercoles.splatman.capture.recording

import com.huntercoles.splatman.capture.tracking.CameraImage
import com.huntercoles.splatman.capture.tracking.FeaturePoints
import com.huntercoles.splatman.capture.tracking.LumaPlane
import com.huntercoles.splatman.capture.tracking.PoseSource
import com.huntercoles.splatman.capture.tracking.TrackedFrame
import com.huntercoles.splatman.capture.tracking.TrackingState
import com.huntercoles.splatman.splat.io.bundle.CaptureBundleReader
import com.huntercoles.splatman.splat.math.Quat
import com.huntercoles.splatman.splat.math.Vec3
import com.huntercoles.splatman.splat.model.CameraIntrinsics
import com.huntercoles.splatman.splat.model.CameraPose
import com.huntercoles.splatman.splat.model.DepthImage
import com.huntercoles.splatman.splat.model.RgbImage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

@OptIn(ExperimentalCoroutinesApi::class)
class CaptureRecorderTest {

    @TempDir
    lateinit var dir: File

    private val scans get() = File(dir, "scans")
    private val staging get() = File(dir, "recording")

    /** A textured image: the same sharpness every frame, a grey RGB of its own size. */
    private class FakeImage(override val width: Int = 640, override val height: Int = 480) : CameraImage {
        var closes = 0
        var open = true
            private set

        override fun toRgb(): RgbImage {
            check(open) { "toRgb after close" }
            return RgbImage(width, height, ByteArray(width * height * 4) { 0x80.toByte() })
        }

        override fun luma(): LumaPlane {
            check(open) { "luma after close" }
            val bytes = ByteArray(width * height) { i -> (((i % width) xor (i / width)) * 37).toByte() }
            return LumaPlane(width, height, width, bytes)
        }

        override fun close() {
            closes++
            open = false
        }
    }

    private class FakeEncoder : JpegEncoder {
        val calls = mutableListOf<Triple<Int, Int, Int>>()
        override fun encode(image: RgbImage, quality: Int): ByteArray {
            calls += Triple(image.width, image.height, quality)
            return byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
        }
    }

    /** A [PoseSource] that walks [count] frames once round a circle of [radiusM] about the origin, looking in. */
    private class CirclePoseSource(
        private val count: Int,
        private val radiusM: Float,
        private val imageWidth: Int = 640,
        private val imageHeight: Int = 480,
        private val state: TrackingState = TrackingState.TRACKING,
    ) : PoseSource {
        val images = mutableListOf<FakeImage>()
        val poses = mutableListOf<CameraPose>()

        override val frames: Flow<TrackedFrame> = flow {
            for (i in 0 until count) emit(frame(i))
        }

        override suspend fun start() = Unit
        override fun stop() = Unit

        private fun frame(i: Int): TrackedFrame {
            val theta = (2 * PI * i / count).toFloat()
            // Cgl looks down -Z; turning it by theta about +Y looks from (r sin, 0, r cos) at the origin.
            val pose = CameraPose(
                Quat.fromAxisAngle(Vec3(0f, 1f, 0f), theta),
                Vec3(radiusM * sin(theta), 0f, radiusM * cos(theta)),
            )
            val k = CameraIntrinsics(
                fx = 500f * imageWidth / 640,
                fy = 500f * imageHeight / 480,
                cx = (imageWidth - 1) / 2f,
                cy = (imageHeight - 1) / 2f,
                width = imageWidth,
                height = imageHeight,
            )
            val image = FakeImage(imageWidth, imageHeight)
            images += image
            poses += pose
            val depthK = k.scaled(4, 3)
            return TrackedFrame(
                timestampNs = 1_000_000L * i,
                trackingState = state,
                pose = pose,
                intrinsics = k,
                image = image,
                points = GRID,
                depth = DepthImage(depthK, FloatArray(12) { 0.3f }, confidence = null),
            )
        }

        companion object {
            /** 27 points 5 cm apart round the origin, full confidence. */
            val GRID: FeaturePoints = run {
                val p = FloatArray(81)
                var o = 0
                for (x in -1..1) for (y in -1..1) for (z in -1..1) {
                    p[o++] = 0.05f * x
                    p[o++] = 0.05f * y
                    p[o++] = 0.05f * z
                }
                FeaturePoints(27, p, FloatArray(27) { 1f })
            }
        }
    }

    private fun TestScope.recorder(encoder: JpegEncoder = FakeEncoder()): CaptureRecorder {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return CaptureRecorder(scans, staging, encoder, worker = dispatcher, io = dispatcher, newScanId = { "scan_test" })
    }

    /** Feeds every frame of [source] and lets the worker take it before the next, as a phone slow enough would. */
    private suspend fun TestScope.feed(recorder: CaptureRecorder, source: PoseSource) {
        source.frames.collect {
            recorder.submit(it)
            runCurrent()
        }
    }

    @Test
    fun `50 frames round a circle give every other frame as a keyframe and Stop writes a bundle the reader loads`() =
        runTest {
            val encoder = FakeEncoder()
            val recorder = recorder(encoder)
            // 0.3 m radius: one 7.2 degree step moves 3.8 cm (below 5 cm and 10 degrees), two steps move 7.5 cm.
            val source = CirclePoseSource(count = 50, radiusM = 0.3f)
            assertTrue(recorder.start(subject = Vec3.ZERO))
            runCurrent()
            feed(recorder, source)
            advanceUntilIdle()

            val live = recorder.state.value as RecorderState.Recording
            assertEquals(25, live.keyframes)
            // A level circle round the subject fills the whole level band and nothing else.
            assertEquals(CoverageCells(level = (1 shl Coverage.SEGMENTS) - 1), live.cells)
            assertEquals(1f / 3, live.coverage, 1e-6f)
            assertEquals(0, live.droppedFrames)
            assertTrue(live.seedPoints > 27, "feature points and depth both seed: ${live.seedPoints}")

            recorder.stop()
            advanceUntilIdle()

            assertEquals(RecorderState.Saved("scan_test", 25), recorder.state.value)
            val folder = File(scans, "scan_test")
            val bundle = CaptureBundleReader.read(folder)
            assertEquals(25, bundle.frames.size)
            assertEquals(live.seedPoints, bundle.seed?.count)
            assertEquals(Vec3.ZERO, bundle.subject)
            assertEquals(25, File(folder, "images").listFiles()!!.size)
            bundle.frames.forEachIndexed { i, f ->
                val expected = source.poses[2 * i]
                assertEquals(expected.translationM.x, f.pose.translationM.x, 1e-5f)
                assertEquals(expected.translationM.z, f.pose.translationM.z, 1e-5f)
                val q = f.pose.rotation
                val e = expected.rotation
                assertEquals(1f, abs(q.w * e.w + q.x * e.x + q.y * e.y + q.z * e.z), 1e-5f, "frame $i rotation")
                assertEquals(1_000_000L * 2 * i, f.timestampNs)
                assertNotNull(f.depthFile)
                assertEquals(640, f.intrinsics.width)
            }
            assertEquals(List(25) { Triple(640, 480, 90) }, encoder.calls)
            assertTrue(staging.listFiles().orEmpty().isEmpty(), "staging is emptied")
            assertEquals(List(50) { 1 }, source.images.map { it.closes }, "every image closed once")
        }

    @Test
    fun `Cancel leaves no folder and closes every frame`() = runTest {
        val recorder = recorder()
        val source = CirclePoseSource(count = 10, radiusM = 0.3f)
        recorder.start()
        runCurrent()
        feed(recorder, source)
        recorder.cancel()
        advanceUntilIdle()

        assertEquals(RecorderState.Idle, recorder.state.value)
        assertTrue(scans.listFiles().orEmpty().isEmpty())
        assertTrue(staging.listFiles().orEmpty().isEmpty())
        assertEquals(List(10) { 1 }, source.images.map { it.closes })
    }

    @Test
    fun `Cancel right after Stop abandons the save and leaves no folder`() = runTest {
        val recorder = recorder()
        recorder.start()
        runCurrent()
        feed(recorder, CirclePoseSource(count = 10, radiusM = 0.3f))
        recorder.stop()
        recorder.cancel()
        advanceUntilIdle()

        assertEquals(RecorderState.Idle, recorder.state.value)
        assertTrue(scans.listFiles().orEmpty().isEmpty())
        assertTrue(staging.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `a busy worker drops frames at once instead of making the preview wait, and closes them`() = runTest {
        val recorder = recorder()
        val source = CirclePoseSource(count = 6, radiusM = 0.3f)
        recorder.start()
        runCurrent()
        source.frames.collect { recorder.submit(it) } // the worker never gets a turn while these arrive
        val closedAtOnce = source.images.count { it.closes == 1 }
        // One frame goes to the waiting worker, one waits in the slot; the rest are dropped and closed right away.
        assertEquals(4, closedAtOnce)
        advanceUntilIdle()

        assertEquals(List(6) { 1 }, source.images.map { it.closes })
        assertEquals(4, (recorder.state.value as RecorderState.Recording).droppedFrames)
    }

    @Test
    fun `frames that are not tracking are closed and never kept`() = runTest {
        val recorder = recorder()
        val source = CirclePoseSource(count = 8, radiusM = 0.3f, state = TrackingState.PAUSED)
        recorder.start()
        runCurrent()
        feed(recorder, source)
        recorder.stop()
        advanceUntilIdle()

        assertEquals(RecorderState.NothingRecorded, recorder.state.value)
        assertFalse(File(scans, "scan_test").exists())
        assertEquals(List(8) { 1 }, source.images.map { it.closes })
    }

    @Test
    fun `a larger camera image is stored at 640x480 with its intrinsics scaled`() = runTest {
        val encoder = FakeEncoder()
        val recorder = recorder(encoder)
        recorder.start()
        runCurrent()
        feed(recorder, CirclePoseSource(count = 1, radiusM = 0.3f, imageWidth = 1280, imageHeight = 960))
        recorder.stop()
        advanceUntilIdle()

        assertEquals(listOf(Triple(640, 480, 90)), encoder.calls)
        val k = CaptureBundleReader.read(File(scans, "scan_test")).frames.single().intrinsics
        assertEquals(640, k.width)
        assertEquals(480, k.height)
        assertEquals(500f, k.fx, 1e-3f)
        assertEquals(319.5f, k.cx, 1e-3f)
    }

    @Test
    fun `a frame submitted while not recording is closed at once`() = runTest {
        val recorder = recorder()
        val source = CirclePoseSource(count = 1, radiusM = 0.3f)
        source.frames.collect { recorder.submit(it) }
        assertEquals(1, source.images.single().closes)
        assertEquals(RecorderState.Idle, recorder.state.value)
    }

    @Test
    fun `a second recording starts from nothing`() = runTest {
        val recorder = recorder()
        recorder.start()
        runCurrent()
        feed(recorder, CirclePoseSource(count = 50, radiusM = 0.3f))
        recorder.stop()
        advanceUntilIdle()
        assertTrue(recorder.start())
        runCurrent()
        feed(recorder, CirclePoseSource(count = 10, radiusM = 0.3f))
        advanceUntilIdle()

        // 36 degrees a step: every frame is a keyframe, and none carry over from the first recording.
        assertEquals(10, (recorder.state.value as RecorderState.Recording).keyframes)
    }

    @Test
    fun `frames submitted with keep false are measured but never kept`() = runTest {
        val recorder = recorder()
        recorder.start(subject = Vec3.ZERO)
        runCurrent()
        CirclePoseSource(count = 10, radiusM = 0.3f).frames.collect {
            recorder.submit(it, keep = false)
            runCurrent()
        }
        val live = recorder.state.value as RecorderState.Recording
        assertEquals(0, live.keyframes)
        assertEquals(CoverageCells(), live.cells)
        assertNotNull(recorder.meanLuma.value)
    }

    @Test
    fun `without a subject coverage is measured round the point in front of the first keyframe`() = runTest {
        val recorder = recorder()
        // Radius 0.6 m, the default subject distance: the derived subject is the circle's centre.
        recorder.start()
        runCurrent()
        feed(recorder, CirclePoseSource(count = 50, radiusM = CaptureRecorder.DEFAULT_SUBJECT_M))
        val live = recorder.state.value as RecorderState.Recording
        assertEquals(Coverage.SEGMENTS, live.cells.count(CoverageBand.LEVEL))
        assertEquals(1, live.cells.bandsTouched)
    }

    @Test
    fun `mean luma samples the plane`() {
        val plane = LumaPlane(16, 16, 16, ByteArray(256) { if (it < 128) 0 else 200.toByte() })
        assertEquals(100f, CaptureRecorder.meanLuma(plane))
    }
}
