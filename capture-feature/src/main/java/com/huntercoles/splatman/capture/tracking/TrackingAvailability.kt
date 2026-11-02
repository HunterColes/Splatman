package com.huntercoles.splatman.capture.tracking

/** Whether this phone can track (ADR 0004: ARCore is optional at install). */
enum class TrackingAvailability {
    /** Supported and installed: a session may be opened. */
    SUPPORTED,

    /** Supported, but the tracker service is missing or too old: offer the install. */
    INSTALL_NEEDED,

    /** Not supported (or the check failed): capture is off, view, import and export still work. */
    UNSUPPORTED,
    ;

    companion object {
        /**
         * Classifies a finished availability check. [supported] false or [unknown] true (the check failed or timed
         * out) is [UNSUPPORTED]; supported and [installed] is [SUPPORTED]; supported otherwise is [INSTALL_NEEDED].
         */
        fun classify(supported: Boolean, installed: Boolean, unknown: Boolean): TrackingAvailability = when {
            unknown || !supported -> UNSUPPORTED
            installed -> SUPPORTED
            else -> INSTALL_NEEDED
        }
    }
}

/** What a [PoseSource] is doing, for the capture screen. */
sealed interface PoseSourceStatus {
    /** Not started. */
    data object Idle : PoseSourceStatus

    /** The tracker is missing on this phone; nothing was opened. */
    data class Unavailable(val availability: TrackingAvailability) : PoseSourceStatus

    /** The CAMERA permission is not granted; nothing was opened. */
    data object NeedsPermission : PoseSourceStatus

    /** Started, session open; frames flow while in the foreground. */
    data object Running : PoseSourceStatus

    /** Started, session open but paused (app in the background). */
    data object Paused : PoseSourceStatus

    /** Opening or updating the session failed; the session is closed. */
    data class Failed(val reason: String) : PoseSourceStatus
}
