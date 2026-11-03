package com.huntercoles.splatman.capture.tracking.arcore

import android.app.Activity
import android.content.Context
import com.google.ar.core.ArCoreApk
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import com.huntercoles.splatman.capture.tracking.TrackerSupport
import com.huntercoles.splatman.capture.tracking.TrackingAvailability
import com.huntercoles.splatman.capture.tracking.TrackingCheck
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import timber.log.Timber

/** The two ARCore calls the availability check makes (a seam so the tests can make them throw or hang). */
interface ArCoreApkCalls {
    /** `ArCoreApk.checkAvailability`. */
    fun checkAvailability(): ArCoreApk.Availability

    /** `ArCoreApk.requestInstall`. */
    fun requestInstall(activity: Activity, userRequested: Boolean): ArCoreApk.InstallStatus

    companion object {
        /** The real calls on [context]'s application context. */
        fun of(context: Context): ArCoreApkCalls {
            val appContext = context.applicationContext
            return object : ArCoreApkCalls {
                override fun checkAvailability(): ArCoreApk.Availability =
                    ArCoreApk.getInstance().checkAvailability(appContext)

                override fun requestInstall(activity: Activity, userRequested: Boolean): ArCoreApk.InstallStatus =
                    ArCoreApk.getInstance().requestInstall(activity, userRequested)
            }
        }
    }
}

/**
 * Whether ARCore can run here (ADR 0004: optional at install). Never throws. Only UNSUPPORTED_DEVICE_NOT_CAPABLE
 * means "this phone can't"; a missing or old ARCore asks for the install, and a check that errs, times out or
 * throws is [TrackingAvailability.COULDNT_CHECK]. Every answer carries the raw reason and is logged.
 */
class ArCoreAvailability(
    private val apk: ArCoreApkCalls,
    private val timeoutMs: Long = CHECK_TIMEOUT_MS,
) : TrackerSupport {
    constructor(context: Context) : this(ArCoreApkCalls.of(context))

    @Suppress("TooGenericExceptionCaught")
    override suspend fun check(): TrackingCheck {
        val result = try {
            var answer = apk.checkAvailability()
            var waited = 0L
            while (answer.isTransient && waited < timeoutMs) {
                delay(POLL_MS)
                waited += POLL_MS
                answer = apk.checkAvailability()
            }
            if (answer.isTransient) {
                TrackingCheck(TrackingAvailability.COULDNT_CHECK, "$answer after $timeoutMs ms")
            } else {
                classify(answer)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TrackingCheck.failed(e)
        }
        Timber.i("ARCore availability: %s (%s)", result.availability, result.reason)
        return result
    }

    /**
     * Asks Play Services for AR to install or update itself (call from the activity's onResume, ARCore's rule).
     * [TrackingAvailability.SUPPORTED] when installed, [TrackingAvailability.INSTALL_NEEDED] while the install is
     * under way or the user declined, [TrackingAvailability.COULDNT_CHECK] when the request throws.
     */
    override fun requestInstall(activity: Activity, userRequested: Boolean): TrackingCheck =
        installAnswer { apk.requestInstall(activity, userRequested) }

    companion object {
        private const val POLL_MS = 200L
        private const val CHECK_TIMEOUT_MS = 5_000L

        /** The answer for one finished (non-transient) ARCore [answer]; its name is the reason. */
        fun classify(answer: ArCoreApk.Availability): TrackingCheck {
            val availability = when (answer) {
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
            return TrackingCheck(availability, answer.name)
        }

        /** Runs one install [request]; a decline is still install-needed, any other throw is couldn't-check. */
        @Suppress("TooGenericExceptionCaught")
        fun installAnswer(request: () -> ArCoreApk.InstallStatus): TrackingCheck {
            val result = try {
                val status = request()
                when (status) {
                    ArCoreApk.InstallStatus.INSTALLED -> TrackingCheck(TrackingAvailability.SUPPORTED, status.name)
                    else -> TrackingCheck(TrackingAvailability.INSTALL_NEEDED, status.name)
                }
            } catch (e: UnavailableUserDeclinedInstallationException) {
                TrackingCheck(TrackingAvailability.INSTALL_NEEDED, TrackingCheck.describe(e))
            } catch (e: Exception) {
                TrackingCheck.failed(e)
            }
            Timber.i("ARCore install request: %s (%s)", result.availability, result.reason)
            return result
        }
    }
}
