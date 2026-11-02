package com.huntercoles.splatman.capture.presentation.capture

import com.huntercoles.splatman.capture.tracking.PoseSourceStatus
import com.huntercoles.splatman.capture.tracking.TrackingAvailability
import com.huntercoles.splatman.capture.tracking.TrackingFailure
import com.huntercoles.splatman.capture.tracking.TrackingState

/** What the user or the screen's lifecycle did. */
sealed interface CaptureIntent {
    /** ON_RESUME (true) or ON_PAUSE and leaving the screen (false). */
    data class Foreground(val inForeground: Boolean) : CaptureIntent

    /** The answer to the CAMERA permission request. */
    data class PermissionResult(val granted: Boolean) : CaptureIntent

    /** The answer of an ARCore install or update request. */
    data class InstallResult(val availability: TrackingAvailability) : CaptureIntent

    /** Try to open the tracker again (after a failure or a refused permission). */
    data object Retry : CaptureIntent
}

/** The one line telling the user how tracking goes. */
enum class TrackingHint {
    /** Tracking well. */
    TRACKING,

    /** Starting up: "looking for surfaces". */
    LOOKING_FOR_SURFACES,

    /** Moving too fast: "move slower". */
    MOVE_SLOWER,

    /** Too dark: "more light". */
    MORE_LIGHT,

    /** Too little texture: aim at something with more detail. */
    MORE_DETAIL,

    /** Another app holds the camera. */
    CAMERA_IN_USE,

    /** Tracking stopped or broke. */
    TRACKING_LOST,
}

/** Which screen the capture route shows. */
sealed interface CapturePhase {
    /** Checking ARCore and the permission. */
    data object Checking : CapturePhase

    /** This phone cannot run ARCore. */
    data object Unsupported : CapturePhase

    /** ARCore is supported but missing or too old: offer the install. */
    data object InstallNeeded : CapturePhase

    /** CAMERA not granted yet: the screen asks. */
    data object PermissionNeeded : CapturePhase

    /** The user refused CAMERA. */
    data object PermissionDenied : CapturePhase

    /** The live camera with the tracking [hint]; [paused] while in the background. */
    data class Preview(val hint: TrackingHint, val paused: Boolean) : CapturePhase

    /** The tracker failed to open or broke; [reason] is the error text. */
    data class Failed(val reason: String) : CapturePhase
}

/** The capture screen's state. [installPending]: an ARCore install was started; check again on resume. */
data class CaptureUiState(
    val phase: CapturePhase = CapturePhase.Checking,
    val installPending: Boolean = false,
)

/** The hint for one frame's tracking [state] and [failure]. */
fun trackingHint(state: TrackingState, failure: TrackingFailure): TrackingHint = when (failure) {
    TrackingFailure.NONE -> when (state) {
        TrackingState.TRACKING -> TrackingHint.TRACKING
        TrackingState.PAUSED -> TrackingHint.LOOKING_FOR_SURFACES
        TrackingState.STOPPED -> TrackingHint.TRACKING_LOST
    }
    TrackingFailure.BAD_STATE -> TrackingHint.TRACKING_LOST
    TrackingFailure.INSUFFICIENT_LIGHT -> TrackingHint.MORE_LIGHT
    TrackingFailure.EXCESSIVE_MOTION -> TrackingHint.MOVE_SLOWER
    TrackingFailure.INSUFFICIENT_FEATURES -> TrackingHint.MORE_DETAIL
    TrackingFailure.CAMERA_UNAVAILABLE -> TrackingHint.CAMERA_IN_USE
}

/** The phase for the tracker's [status], whether the user refused CAMERA, and the latest frame's [hint]. */
fun capturePhase(status: PoseSourceStatus, permissionDenied: Boolean, hint: TrackingHint): CapturePhase =
    when (status) {
        PoseSourceStatus.Idle -> CapturePhase.Checking
        is PoseSourceStatus.Unavailable -> when (status.availability) {
            TrackingAvailability.UNSUPPORTED -> CapturePhase.Unsupported
            TrackingAvailability.INSTALL_NEEDED -> CapturePhase.InstallNeeded
            TrackingAvailability.SUPPORTED -> CapturePhase.Checking
        }
        PoseSourceStatus.NeedsPermission ->
            if (permissionDenied) CapturePhase.PermissionDenied else CapturePhase.PermissionNeeded
        PoseSourceStatus.Running -> CapturePhase.Preview(hint, paused = false)
        PoseSourceStatus.Paused -> CapturePhase.Preview(hint, paused = true)
        is PoseSourceStatus.Failed -> CapturePhase.Failed(status.reason)
    }
