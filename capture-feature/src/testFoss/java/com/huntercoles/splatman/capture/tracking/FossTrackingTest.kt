package com.huntercoles.splatman.capture.tracking

import com.huntercoles.splatman.capture.presentation.capture.CapturePhase
import com.huntercoles.splatman.capture.presentation.capture.TrackingHint
import com.huntercoles.splatman.capture.presentation.capture.capturePhase
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The `foss` flavour (ADR 0004, F-Droid): no ARCore on the classpath; capture records pose-free wherever there is a back camera. */
class FossTrackingTest {
    @Test
    fun noArCoreClassIsOnTheFossClasspath() {
        for (name in listOf("com.google.ar.core.ArCoreApk", "com.google.ar.core.Session")) {
            assertThrows<ClassNotFoundException> { Class.forName(name) }
        }
    }

    @Test
    fun noPlayServicesClassIsOnTheFossClasspath() {
        assertThrows<ClassNotFoundException> { Class.forName("com.google.android.gms.common.GoogleApiAvailability") }
    }

    @Test
    fun theTrackerIsNeverAvailable() = runTest {
        assertEquals(TrackingCheck(TrackingAvailability.UNSUPPORTED, NoTrackerSupport.REASON), NoTrackerSupport.check())
    }

    @Test
    fun poseFreeCaptureIsSupportedWithABackCamera() = runTest {
        assertEquals(
            TrackingCheck(TrackingAvailability.SUPPORTED, PoseFreeSupport.AVAILABLE),
            PoseFreeSupport { true }.check(),
        )
    }

    @Test
    fun poseFreeCaptureIsUnsupportedOnlyWithoutABackCamera() = runTest {
        val answer = PoseFreeSupport { false }.check()
        assertEquals(TrackingCheck(TrackingAvailability.UNSUPPORTED, PoseFreeSupport.NO_CAMERA), answer)
        assertEquals(
            CapturePhase.Unsupported(PoseFreeSupport.NO_CAMERA),
            capturePhase(
                PoseSourceStatus.Unavailable(answer.availability, answer.reason),
                permissionDenied = false,
                hint = TrackingHint.TRACKING,
            ),
        )
    }

    @Test
    fun theStubTrackerStartsIntoUnavailable() = runTest {
        val tracker = UnavailableCaptureTracker(UnavailablePoseSource())
        assertEquals(PoseSourceStatus.Idle, tracker.status.value)

        tracker.start()

        assertEquals(
            PoseSourceStatus.Unavailable(TrackingAvailability.UNSUPPORTED, NoTrackerSupport.REASON),
            tracker.status.value,
        )
        assertEquals(
            CapturePhase.Unsupported(NoTrackerSupport.REASON),
            capturePhase(tracker.status.value, permissionDenied = false, hint = TrackingHint.LOOKING_FOR_SURFACES),
        )
        assertTrue(tracker.frames.toList().isEmpty())
        assertTrue(tracker.setPreview(null).isCompleted)
        tracker.release()
        assertEquals(PoseSourceStatus.Idle, tracker.status.value)
    }
}
