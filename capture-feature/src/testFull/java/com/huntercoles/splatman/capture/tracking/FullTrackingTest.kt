package com.huntercoles.splatman.capture.tracking

import com.huntercoles.splatman.capture.tracking.arcore.ArCoreAvailability
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The `full` flavour keeps ARCore (ADR 0004): its library is on the classpath and backs the tracker support. */
class FullTrackingTest {
    @Test
    fun arCoreIsOnTheFullClasspath() {
        Class.forName("com.google.ar.core.ArCoreApk")
    }

    @Test
    fun arCoreAvailabilityIsTheFullTrackerSupport() {
        assertTrue(TrackerSupport::class.java.isAssignableFrom(ArCoreAvailability::class.java))
    }
}
