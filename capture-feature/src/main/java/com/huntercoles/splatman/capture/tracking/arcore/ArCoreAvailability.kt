package com.huntercoles.splatman.capture.tracking.arcore

import android.app.Activity
import android.content.Context
import com.google.ar.core.ArCoreApk
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import com.huntercoles.splatman.capture.tracking.TrackingAvailability
import kotlinx.coroutines.delay

/**
 * Whether ARCore can run here (ADR 0004: optional at install). Never throws: any failure of the check reads as
 * [TrackingAvailability.UNSUPPORTED], so a phone without ARCore never crashes.
 */
class ArCoreAvailability(context: Context) {
    private val appContext = context.applicationContext

    /** Checks availability, waiting out ARCore's transient "checking" answer for up to [timeoutMs]. */
    @Suppress("TooGenericExceptionCaught")
    suspend fun check(timeoutMs: Long = CHECK_TIMEOUT_MS): TrackingAvailability = try {
        val apk = ArCoreApk.getInstance()
        var answer = apk.checkAvailability(appContext)
        var waited = 0L
        while (answer.isTransient && waited < timeoutMs) {
            delay(POLL_MS)
            waited += POLL_MS
            answer = apk.checkAvailability(appContext)
        }
        TrackingAvailability.classify(
            supported = answer.isSupported,
            installed = answer == ArCoreApk.Availability.SUPPORTED_INSTALLED,
            unknown = answer.isUnknown,
        )
    } catch (e: Exception) {
        TrackingAvailability.UNSUPPORTED
    }

    /**
     * Asks Play Services for AR to install or update itself (call from the activity's onResume, ARCore's rule).
     * Returns [TrackingAvailability.SUPPORTED] when installed, [TrackingAvailability.INSTALL_NEEDED] while the install
     * is under way or the user declined, [TrackingAvailability.UNSUPPORTED] when the phone cannot run it.
     */
    @Suppress("TooGenericExceptionCaught")
    fun requestInstall(activity: Activity, userRequested: Boolean): TrackingAvailability = try {
        when (ArCoreApk.getInstance().requestInstall(activity, userRequested)) {
            ArCoreApk.InstallStatus.INSTALLED -> TrackingAvailability.SUPPORTED
            else -> TrackingAvailability.INSTALL_NEEDED
        }
    } catch (e: UnavailableUserDeclinedInstallationException) {
        TrackingAvailability.INSTALL_NEEDED
    } catch (e: Exception) {
        TrackingAvailability.UNSUPPORTED
    }

    private companion object {
        const val POLL_MS = 200L
        const val CHECK_TIMEOUT_MS = 5_000L
    }
}
