package com.huntercoles.splatman.capture.presentation.onboarding

import com.huntercoles.splatman.capture.tracking.TrackingAvailability
import com.huntercoles.splatman.capture.tracking.TrackingCheck

/** The pager's pages (docs/design/screens/onboarding.md); [CAMERA] is replaced by the AR states when needed. */
enum class OnboardingPage {
    WELCOME,
    TIPS,
    CAMERA,
    ;

    companion object {
        /** The page at [index], clamped into range (a bad route argument opens the nearest page). */
        fun at(index: Int): OnboardingPage = entries[index.coerceIn(0, entries.lastIndex)]
    }
}

/** What the last page shows, from the AR availability check. */
enum class ArState {
    /** The check has not answered yet: the camera page shows meanwhile. */
    CHECKING,

    /** ARCore installed: the camera permission page. */
    SUPPORTED,

    /** Supported, Play Services for AR missing or too old: the install page. */
    INSTALL_NEEDED,

    /** This phone cannot track: the open-a-file page. */
    UNSUPPORTED,

    /** The check failed or timed out: the couldn't-check page with Try again. */
    COULDNT_CHECK,
    ;

    companion object {
        /** The page state for an availability answer. */
        fun of(availability: TrackingAvailability): ArState = when (availability) {
            TrackingAvailability.SUPPORTED -> SUPPORTED
            TrackingAvailability.INSTALL_NEEDED -> INSTALL_NEEDED
            TrackingAvailability.UNSUPPORTED -> UNSUPPORTED
            TrackingAvailability.COULDNT_CHECK -> COULDNT_CHECK
        }
    }
}

/** Where onboarding hands over once it is finished or skipped. */
enum class OnboardingExit {
    /** The Library (home). */
    LIBRARY,

    /** The open-a-file flow ("Open a file" on the AR unsupported page). */
    IMPORT,
}

/** What the user did on the onboarding screen. */
sealed interface OnboardingIntent {
    /** The Next button. */
    data object Next : OnboardingIntent

    /** A swipe settled on page [index]. */
    data class PageShown(val index: Int) : OnboardingIntent

    /** The Skip button: straight to the Library. */
    data object Skip : OnboardingIntent

    /** The answer to the CAMERA permission request; either way onboarding is done (Capture handles a denial). */
    data class PermissionResult(val granted: Boolean) : OnboardingIntent

    /** The answer of a Play Services for AR install request. */
    data class InstallResult(val check: TrackingCheck) : OnboardingIntent

    /** The availability check answered (the first check, or one after Try again). */
    data class CheckResult(val check: TrackingCheck) : OnboardingIntent

    /** "Try again" on the couldn't-check page: check ARCore again. */
    data object RetryCheck : OnboardingIntent

    /** "Later" on the install page. */
    data object Later : OnboardingIntent

    /** "Continue" on the unsupported page. */
    data object Continue : OnboardingIntent

    /** "Open a file" on the unsupported page. */
    data object OpenFile : OnboardingIntent
}

/**
 * The onboarding screen's state. [arReason]: the raw reason behind [ar] (the Details line). [installPending]: an
 * install was started, ask again on resume. [exit]: set once onboarding is over; the screen then marks the first-run
 * flag and leaves.
 */
data class OnboardingUiState(
    val page: OnboardingPage = OnboardingPage.WELCOME,
    val ar: ArState = ArState.CHECKING,
    val arReason: String = "",
    val installPending: Boolean = false,
    val permissionGranted: Boolean? = null,
    val exit: OnboardingExit? = null,
) {
    /** Number of pages, for the dots and "Page n of 3". */
    val pageCount: Int get() = OnboardingPage.entries.size
}

/** The state after [intent]; pure. Nothing moves once [OnboardingUiState.exit] is set. */
fun reduce(state: OnboardingUiState, intent: OnboardingIntent): OnboardingUiState {
    if (state.exit != null) return state
    return when (intent) {
        OnboardingIntent.Next -> state.copy(page = OnboardingPage.at(state.page.ordinal + 1))
        is OnboardingIntent.PageShown -> state.copy(page = OnboardingPage.at(intent.index))
        OnboardingIntent.Skip, OnboardingIntent.Later, OnboardingIntent.Continue ->
            state.copy(exit = OnboardingExit.LIBRARY)
        is OnboardingIntent.PermissionResult ->
            state.copy(permissionGranted = intent.granted, exit = OnboardingExit.LIBRARY)
        is OnboardingIntent.InstallResult -> state.copy(
            ar = ArState.of(intent.check.availability),
            arReason = intent.check.reason,
            installPending = intent.check.availability == TrackingAvailability.INSTALL_NEEDED,
        )
        // An install result that came in first is newer than a check's answer.
        is OnboardingIntent.CheckResult -> if (state.ar == ArState.CHECKING) {
            state.copy(ar = ArState.of(intent.check.availability), arReason = intent.check.reason)
        } else {
            state
        }
        OnboardingIntent.RetryCheck -> state.copy(ar = ArState.CHECKING, arReason = "")
        OnboardingIntent.OpenFile -> state.copy(exit = OnboardingExit.IMPORT)
    }
}
