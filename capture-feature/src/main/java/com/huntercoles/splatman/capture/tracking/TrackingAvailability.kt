package com.huntercoles.splatman.capture.tracking

/** Whether this phone can track (ADR 0004: ARCore is optional at install). */
enum class TrackingAvailability {
    /** Supported and installed: a session may be opened. */
    SUPPORTED,

    /** Supported, but the tracker service is missing or too old: offer the install. */
    INSTALL_NEEDED,

    /** This phone cannot run the tracker (ARCore's UNSUPPORTED_DEVICE_NOT_CAPABLE, or a build without one). */
    UNSUPPORTED,

    /** The check itself failed or timed out: nothing is known about the phone, so offer Try again. */
    COULDNT_CHECK,
}

/**
 * One answer of an availability check or install request: the [availability] and the [reason] behind it (the raw
 * tracker answer such as `SUPPORTED_NOT_INSTALLED`, or the exception's class and message), shown as Details.
 */
data class TrackingCheck(val availability: TrackingAvailability, val reason: String = "") {
    companion object {
        /** The answer for a check that threw [e]: [TrackingAvailability.COULDNT_CHECK] with its class and message. */
        fun failed(e: Throwable): TrackingCheck = TrackingCheck(TrackingAvailability.COULDNT_CHECK, describe(e))

        /** `SimpleClassName: message`, or the class name alone when there is no message. */
        fun describe(e: Throwable): String {
            val name = e.javaClass.simpleName.ifEmpty { e.javaClass.name }
            return e.message?.takeIf { it.isNotBlank() }?.let { "$name: $it" } ?: name
        }
    }
}

/** What a [PoseSource] is doing, for the capture screen. */
sealed interface PoseSourceStatus {
    /** Not started. */
    data object Idle : PoseSourceStatus

    /** The tracker is missing or could not be checked ([reason]: the raw answer); nothing was opened. */
    data class Unavailable(val availability: TrackingAvailability, val reason: String = "") : PoseSourceStatus

    /** The CAMERA permission is not granted; nothing was opened. */
    data object NeedsPermission : PoseSourceStatus

    /** Started, session open; frames flow while in the foreground. */
    data object Running : PoseSourceStatus

    /** Started, session open but paused (app in the background). */
    data object Paused : PoseSourceStatus

    /** Opening or updating the session failed; the session is closed. */
    data class Failed(val reason: String) : PoseSourceStatus
}
