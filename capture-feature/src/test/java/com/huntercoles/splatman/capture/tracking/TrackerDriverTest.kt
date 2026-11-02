package com.huntercoles.splatman.capture.tracking

import com.huntercoles.splatman.splat.math.Quat
import com.huntercoles.splatman.splat.math.Vec3
import com.huntercoles.splatman.splat.model.CameraIntrinsics
import com.huntercoles.splatman.splat.model.CameraPose
import com.huntercoles.splatman.splat.model.RgbImage
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
        val calls = mutableListOf<String>()

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
}
