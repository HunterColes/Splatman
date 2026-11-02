package com.huntercoles.splatman.capture.tracking

import android.view.Surface
import com.huntercoles.splatman.capture.preview.PreviewTarget
import com.huntercoles.splatman.splat.math.Quat
import com.huntercoles.splatman.splat.math.Vec3
import com.huntercoles.splatman.splat.model.CameraIntrinsics
import com.huntercoles.splatman.splat.model.CameraPose
import com.huntercoles.splatman.splat.model.RgbImage
import io.mockk.mockk
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TrackerDriverTest {
    private class FakeImage : CameraImage {
        var closes = 0
        override val width = 2
        override val height = 2
        override fun toRgb() = RgbImage(2, 2, ByteArray(16))
        override fun close() {
            closes++
        }
    }

    private class FakeSession(frames: List<TrackedFrame> = emptyList()) : TrackerSession {
        val queue = ArrayDeque(frames)
        var resumes = 0
        var pauses = 0
        var closes = 0
        var updateError: Exception? = null
        var previewError: Exception? = null
        val previews = mutableListOf<PreviewTarget?>()
        val calls = mutableListOf<String>()

        override fun setPreview(target: PreviewTarget?) {
            previewError?.let { throw it }
            calls += if (target == null) "preview-off" else "preview-on"
            previews += target
        }

        override fun resume() {
            calls += "resume"
            resumes++
        }

        override fun pause() {
            calls += "pause"
            pauses++
        }

        override fun update(): TrackedFrame? {
            updateError?.let { throw it }
            return queue.removeFirstOrNull()
        }

        override fun close() {
            calls += "close"
            closes++
        }
    }

    private fun frame(ts: Long, image: FakeImage = FakeImage()) = TrackedFrame(
        timestampNs = ts,
        trackingState = TrackingState.TRACKING,
        pose = CameraPose(Quat.IDENTITY, Vec3.ZERO),
        intrinsics = CameraIntrinsics(fx = 2f, fy = 2f, cx = 1f, cy = 1f, width = 2, height = 2),
        image = image,
        points = FeaturePoints.EMPTY,
        depth = null,
    )

    private fun TestScope.driver(
        availability: TrackingAvailability = TrackingAvailability.SUPPORTED,
        granted: Boolean = true,
        open: () -> TrackerSession,
    ) = TrackerDriver(
        availability = { availability },
        permissionGranted = { granted },
        openSession = open,
        dispatcher = StandardTestDispatcher(testScheduler),
    )

    @Test
    fun unsupportedPhoneNeverOpensASession() = runTest {
        var opens = 0
        val driver = driver(TrackingAvailability.UNSUPPORTED) { opens++; FakeSession() }
        driver.start()
        runCurrent()
        assertEquals(0, opens)
        assertEquals(PoseSourceStatus.Unavailable(TrackingAvailability.UNSUPPORTED), driver.status.value)
    }

    @Test
    fun installNeededNeverOpensASession() = runTest {
        var opens = 0
        val driver = driver(TrackingAvailability.INSTALL_NEEDED) { opens++; FakeSession() }
        driver.start()
        assertEquals(0, opens)
        assertEquals(PoseSourceStatus.Unavailable(TrackingAvailability.INSTALL_NEEDED), driver.status.value)
    }

    @Test
    fun aThrowingAvailabilityCheckReadsAsUnsupported() = runTest {
        var opens = 0
        val driver = TrackerDriver(
            availability = { throw IllegalStateException("no arcore") },
            permissionGranted = { true },
            openSession = { opens++; FakeSession() },
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        driver.start()
        assertEquals(0, opens)
        assertEquals(PoseSourceStatus.Unavailable(TrackingAvailability.UNSUPPORTED), driver.status.value)
    }

    @Test
    fun missingPermissionNeverOpensASession() = runTest {
        var opens = 0
        val driver = driver(granted = false) { opens++; FakeSession() }
        driver.start()
        assertEquals(0, opens)
        assertEquals(PoseSourceStatus.NeedsPermission, driver.status.value)
    }

    @Test
    fun aSessionThatFailsToOpenIsReportedNotThrown() = runTest {
        val driver = driver { throw SecurityException("camera") }
        driver.start()
        assertEquals(PoseSourceStatus.Failed("camera"), driver.status.value)
    }

    @Test
    fun startResumesAndDeliversFramesStopPausesAndCloses() = runTest {
        val session = FakeSession(listOf(frame(1), frame(2)))
        val driver = driver { session }
        val received = mutableListOf<Long>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            driver.frames.collect { received += it.timestampNs; it.close() }
        }
        driver.start()
        runCurrent()
        assertEquals(1, session.resumes)
        assertEquals(PoseSourceStatus.Running, driver.status.value)
        assertEquals(listOf(1L, 2L), received)

        driver.stop()
        runCurrent()
        assertEquals(listOf("resume", "pause", "close"), session.calls)
        assertEquals(PoseSourceStatus.Idle, driver.status.value)
    }

    @Test
    fun lifecyclePausesAndResumesTheSession() = runTest {
        val session = FakeSession()
        val driver = driver { session }
        driver.start()
        runCurrent()
        driver.setForeground(false)
        runCurrent()
        assertEquals(1, session.pauses)
        assertEquals(PoseSourceStatus.Paused, driver.status.value)
        driver.setForeground(true)
        runCurrent()
        assertEquals(2, session.resumes)
        assertEquals(PoseSourceStatus.Running, driver.status.value)
        driver.dispose()
        runCurrent()
    }

    @Test
    fun startInTheBackgroundOpensButWaitsForResume() = runTest {
        val session = FakeSession()
        val driver = driver { session }
        driver.setForeground(false)
        runCurrent()
        driver.start()
        runCurrent()
        assertEquals(0, session.resumes)
        assertEquals(PoseSourceStatus.Paused, driver.status.value)
        driver.setForeground(true)
        runCurrent()
        assertEquals(1, session.resumes)
        driver.dispose()
        runCurrent()
    }

    @Test
    fun disposeClosesTheSession() = runTest {
        val session = FakeSession()
        val driver = driver { session }
        driver.start()
        runCurrent()
        val done = driver.dispose()
        runCurrent()
        assertTrue(done.isCompleted)
        assertEquals(1, session.closes)
    }

    @Test
    fun aStartQueuedBehindDisposeNeverOpensASession() = runTest {
        var opens = 0
        val driver = driver { opens++; FakeSession() }
        driver.dispose()
        launch { driver.start() }
        advanceUntilIdle()
        assertEquals(0, opens)
        assertEquals(PoseSourceStatus.Idle, driver.status.value)
    }

    @Test
    fun everySessionOpenedAroundDisposeIsClosed() = runTest {
        // start and dispose launched in either order, with and without letting start run first.
        for (order in 0 until 4) {
            val opened = mutableListOf<FakeSession>()
            val driver = driver { FakeSession().also { opened += it } }
            if (order % 2 == 0) launch { driver.start() } else driver.dispose()
            if (order >= 2) runCurrent()
            if (order % 2 == 0) driver.dispose() else launch { driver.start() }
            advanceUntilIdle()
            assertTrue(opened.all { it.closes == 1 }, "order $order")
            if (order == 2) assertEquals(1, opened.size)
        }
    }

    @Test
    fun framesNobodyReceivesAreClosed() = runTest {
        val images = List(3) { FakeImage() }
        val session = FakeSession(images.mapIndexed { i, image -> frame(i + 1L, image) })
        val driver = driver { session }
        driver.start()
        runCurrent()
        assertEquals(listOf(1, 1, 0), images.map { it.closes })
        driver.stop()
        runCurrent()
        assertEquals(listOf(1, 1, 1), images.map { it.closes })
    }

    @Test
    fun anUpdateFailureClosesTheSession() = runTest {
        val session = FakeSession().apply { updateError = IllegalStateException("camera lost") }
        val driver = driver { session }
        driver.start()
        runCurrent()
        assertEquals(1, session.closes)
        assertEquals(PoseSourceStatus.Failed("camera lost"), driver.status.value)
        driver.stop()
        runCurrent()
        assertEquals(1, session.closes)
        assertEquals(PoseSourceStatus.Failed("camera lost"), driver.status.value)
    }

    @Test
    fun startTwiceOpensOnce() = runTest {
        var opens = 0
        val driver = driver { opens++; FakeSession() }
        driver.start()
        driver.start()
        assertEquals(1, opens)
        driver.dispose()
        runCurrent()
    }

    @Test
    fun availabilityClassification() {
        assertEquals(TrackingAvailability.SUPPORTED, TrackingAvailability.classify(true, true, false))
        assertEquals(TrackingAvailability.INSTALL_NEEDED, TrackingAvailability.classify(true, false, false))
        assertEquals(TrackingAvailability.UNSUPPORTED, TrackingAvailability.classify(false, false, false))
        assertEquals(TrackingAvailability.UNSUPPORTED, TrackingAvailability.classify(false, false, true))
        assertFalse(TrackingAvailability.classify(true, true, true) == TrackingAvailability.SUPPORTED)
    }

    private val target = PreviewTarget(mockk<Surface>(), 1080, 2340, 0)

    @Test
    fun aPreviewSetBeforeStartIsAppliedBeforeResumeAndToEveryNewSession() = runTest {
        val sessions = mutableListOf<FakeSession>()
        val driver = driver { FakeSession().also { sessions += it } }
        driver.setPreview(target)
        runCurrent()
        driver.start()
        runCurrent()
        assertEquals(listOf("preview-on", "resume"), sessions[0].calls.take(2))
        driver.stop()
        runCurrent()
        driver.start()
        runCurrent()
        assertEquals(listOf<PreviewTarget?>(target), sessions[1].previews)
        driver.dispose()
        runCurrent()
    }

    @Test
    fun aPreviewSetOrClearedWhileRunningReachesTheOpenSession() = runTest {
        val session = FakeSession()
        val driver = driver { session }
        driver.start()
        runCurrent()
        driver.setPreview(target)
        runCurrent()
        val detach = driver.setPreview(null)
        runCurrent()
        assertTrue(detach.isCompleted)
        assertEquals(listOf(target, null), session.previews)
        driver.dispose()
        runCurrent()
    }

    @Test
    fun aFailingPreviewDoesNotFailTracking() = runTest {
        val session = FakeSession().apply { previewError = IllegalStateException("no window") }
        val driver = driver { session }
        driver.start()
        runCurrent()
        driver.setPreview(target)
        runCurrent()
        assertEquals(PoseSourceStatus.Running, driver.status.value)
        assertEquals(0, session.closes)
        driver.dispose()
        runCurrent()
    }
}
