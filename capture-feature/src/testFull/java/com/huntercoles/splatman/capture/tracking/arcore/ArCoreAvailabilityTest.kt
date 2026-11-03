package com.huntercoles.splatman.capture.tracking.arcore

import android.app.Activity
import com.google.ar.core.ArCoreApk
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import com.huntercoles.splatman.capture.tracking.TrackingAvailability
import com.huntercoles.splatman.capture.tracking.TrackingCheck
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/** Only a phone ARCore calls not capable is "unsupported"; a missing ARCore or a failed check never is. */
class ArCoreAvailabilityTest {
    private class FakeApk(
        private val answers: () -> ArCoreApk.Availability,
        private val install: () -> ArCoreApk.InstallStatus = { ArCoreApk.InstallStatus.INSTALLED },
    ) : ArCoreApkCalls {
        var checks = 0
        override fun checkAvailability(): ArCoreApk.Availability {
            checks++
            return answers()
        }

        override fun requestInstall(activity: Activity, userRequested: Boolean) = install()
    }

    private fun expected(answer: ArCoreApk.Availability) = when (answer) {
        ArCoreApk.Availability.SUPPORTED_INSTALLED -> TrackingAvailability.SUPPORTED
        ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED,
        ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD,
        -> TrackingAvailability.INSTALL_NEEDED
        ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE -> TrackingAvailability.UNSUPPORTED
        ArCoreApk.Availability.UNKNOWN_CHECKING,
        ArCoreApk.Availability.UNKNOWN_ERROR,
        ArCoreApk.Availability.UNKNOWN_TIMED_OUT,
        -> TrackingAvailability.COULDNT_CHECK
    }

    @ParameterizedTest
    @EnumSource(ArCoreApk.Availability::class)
    fun `every ARCore answer maps to one state and carries its raw name`(answer: ArCoreApk.Availability) {
        val check = ArCoreAvailability.classify(answer)
        assertEquals(TrackingCheck(expected(answer), answer.name), check)
        if (answer != ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE) {
            assertNotEquals(TrackingAvailability.UNSUPPORTED, check.availability)
        }
    }

    @ParameterizedTest
    @EnumSource(ArCoreApk.Availability::class, names = ["UNKNOWN_CHECKING"], mode = EnumSource.Mode.EXCLUDE)
    fun `a final answer is what check returns`(answer: ArCoreApk.Availability) = runTest {
        assertEquals(ArCoreAvailability.classify(answer), ArCoreAvailability(FakeApk({ answer })).check())
    }

    @Test
    fun `a transient answer is waited out until the real one comes`() = runTest {
        val answers = ArrayDeque(
            listOf(ArCoreApk.Availability.UNKNOWN_CHECKING, ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED),
        )
        val apk = FakeApk({ answers.removeFirst() })
        assertEquals(
            TrackingCheck(TrackingAvailability.INSTALL_NEEDED, "SUPPORTED_NOT_INSTALLED"),
            ArCoreAvailability(apk).check(),
        )
        assertEquals(2, apk.checks)
    }

    @Test
    fun `still checking past the timeout is couldn't-check, not unsupported`() = runTest {
        val apk = FakeApk({ ArCoreApk.Availability.UNKNOWN_CHECKING })
        val check = ArCoreAvailability(apk, timeoutMs = 1_000).check()
        assertEquals(TrackingCheck(TrackingAvailability.COULDNT_CHECK, "UNKNOWN_CHECKING after 1000 ms"), check)
        assertEquals(1_000, currentTime)
        assertTrue(apk.checks > 1)
    }

    @Test
    fun `a SecurityException from the check is couldn't-check with its class and message`() = runTest {
        val apk = FakeApk({ throw SecurityException("no permission to bind") })
        assertEquals(
            TrackingCheck(TrackingAvailability.COULDNT_CHECK, "SecurityException: no permission to bind"),
            ArCoreAvailability(apk).check(),
        )
    }

    @Test
    fun `a RuntimeException from the check is couldn't-check with its class and message`() = runTest {
        val apk = FakeApk({ throw IllegalStateException("binder died") })
        assertEquals(
            TrackingCheck(TrackingAvailability.COULDNT_CHECK, "IllegalStateException: binder died"),
            ArCoreAvailability(apk).check(),
        )
    }

    @Test
    fun `an exception partway through the wait is couldn't-check too`() = runTest {
        var first = true
        val apk = FakeApk({
            if (first) {
                first = false
                ArCoreApk.Availability.UNKNOWN_CHECKING
            } else {
                throw RuntimeException("gone")
            }
        })
        assertEquals(TrackingAvailability.COULDNT_CHECK, ArCoreAvailability(apk).check().availability)
    }

    @Test
    fun `an install answer is installed or still needed`() {
        assertEquals(
            TrackingCheck(TrackingAvailability.SUPPORTED, "INSTALLED"),
            ArCoreAvailability.installAnswer { ArCoreApk.InstallStatus.INSTALLED },
        )
        assertEquals(
            TrackingCheck(TrackingAvailability.INSTALL_NEEDED, "INSTALL_REQUESTED"),
            ArCoreAvailability.installAnswer { ArCoreApk.InstallStatus.INSTALL_REQUESTED },
        )
    }

    @Test
    fun `a declined install is still install-needed`() {
        val check = ArCoreAvailability.installAnswer { throw UnavailableUserDeclinedInstallationException() }
        assertEquals(TrackingAvailability.INSTALL_NEEDED, check.availability)
        assertTrue(check.reason.startsWith("UnavailableUserDeclinedInstallationException"))
    }

    @Test
    fun `a throwing install request is couldn't-check, never unsupported`() {
        assertEquals(
            TrackingCheck(TrackingAvailability.COULDNT_CHECK, "SecurityException: blocked"),
            ArCoreAvailability.installAnswer { throw SecurityException("blocked") },
        )
        assertEquals(
            TrackingCheck(TrackingAvailability.COULDNT_CHECK, "RuntimeException: play store gone"),
            ArCoreAvailability.installAnswer { throw RuntimeException("play store gone") },
        )
    }
}
