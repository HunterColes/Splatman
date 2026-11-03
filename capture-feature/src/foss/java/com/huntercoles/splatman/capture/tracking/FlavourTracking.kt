package com.huntercoles.splatman.capture.tracking

import android.app.Activity
import android.content.Context
import com.huntercoles.splatman.capture.presentation.capture.CaptureTracker
import com.huntercoles.splatman.capture.preview.PreviewTarget
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * The `foss` flavour's tracking: none. ARCore is proprietary, so the F-Droid build leaves it out (ADR 0004) and capture
 * shows why instead of a camera; viewing, import and export are untouched.
 */
object FlavourTracking {
    /** No tracker in this build. */
    fun support(context: Context): TrackerSupport = NoTrackerSupport

    /** A tracker that is never there. */
    fun captureTracker(context: Context): CaptureTracker = UnavailableCaptureTracker(UnavailablePoseSource())
}

/** Always [TrackingAvailability.UNSUPPORTED]: there is nothing to install from an F-Droid build. */
object NoTrackerSupport : TrackerSupport {
    /** The Details line of the foss build's explanation. */
    const val REASON = "foss build: no ARCore"

    private val answer = TrackingCheck(TrackingAvailability.UNSUPPORTED, REASON)

    override suspend fun check(): TrackingCheck = answer

    override fun requestInstall(activity: Activity, userRequested: Boolean): TrackingCheck = answer
}

/** The stub [PoseSource]: no frames, and [start] ends in [PoseSourceStatus.Unavailable], never a session. */
class UnavailablePoseSource : PoseSource {
    private val state = MutableStateFlow<PoseSourceStatus>(PoseSourceStatus.Idle)

    /** What the source is doing: Idle until started, then Unavailable. */
    val status: StateFlow<PoseSourceStatus> = state.asStateFlow()

    override val frames: Flow<TrackedFrame> = emptyFlow()

    override suspend fun start() {
        state.value = PoseSourceStatus.Unavailable(TrackingAvailability.UNSUPPORTED, NoTrackerSupport.REASON)
    }

    override fun stop() {
        state.value = PoseSourceStatus.Idle
    }
}

/** [CaptureTracker] on the stub source: nothing to preview, pause or release. */
class UnavailableCaptureTracker(private val source: UnavailablePoseSource) : CaptureTracker {
    override val status: StateFlow<PoseSourceStatus> get() = source.status
    override val frames: Flow<TrackedFrame> get() = source.frames
    override suspend fun start() = source.start()
    override fun setForeground(inForeground: Boolean) = Unit
    override fun setPreview(target: PreviewTarget?): Job = CompletableDeferred(Unit)
    override fun release() = source.stop()
}
