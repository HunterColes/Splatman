package com.huntercoles.splatman.capture.tracking

import android.app.Activity
import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import com.huntercoles.splatman.capture.presentation.capture.CaptureTracker
import com.huntercoles.splatman.capture.preview.PreviewTarget
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import timber.log.Timber

/**
 * The `foss` flavour's tracking: none. ARCore is proprietary, so the F-Droid build leaves it out (ADR 0004) and
 * records pose-free instead (PoseFreeRecorder, #219): capture is supported wherever there is a back camera.
 */
object FlavourTracking {
    /** SUPPORTED ("pose-free capture available") with a back camera; UNSUPPORTED only without one. */
    fun support(context: Context): TrackerSupport = PoseFreeSupport { hasBackCamera(context) }

    /** A tracker that is never there: the capture view model records through the PoseFreeRecorder instead. */
    fun captureTracker(context: Context): CaptureTracker = UnavailableCaptureTracker(UnavailablePoseSource())

    /** True when Camera2 lists a back-facing camera; true too when it cannot answer (opening it then says why). */
    private fun hasBackCamera(context: Context): Boolean {
        val manager = context.getSystemService(CameraManager::class.java) ?: return false
        return try {
            manager.cameraIdList.any { id ->
                manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) ==
                    CameraMetadata.LENS_FACING_BACK
            }
        } catch (e: CameraAccessException) {
            Timber.w(e, "FlavourTracking: cannot list the cameras")
            true
        }
    }
}

/**
 * Pose-free capture's answer (#219): [TrackingAvailability.SUPPORTED] when [hasBackCamera], else
 * [TrackingAvailability.UNSUPPORTED] (the foss `ar_unsupported_*` copy: no camera to scan with). Never an install.
 */
class PoseFreeSupport(private val hasBackCamera: () -> Boolean) : TrackerSupport {
    override suspend fun check(): TrackingCheck = answer()

    override fun requestInstall(activity: Activity, userRequested: Boolean): TrackingCheck = answer()

    private fun answer(): TrackingCheck = if (hasBackCamera()) {
        TrackingCheck(TrackingAvailability.SUPPORTED, AVAILABLE)
    } else {
        TrackingCheck(TrackingAvailability.UNSUPPORTED, NO_CAMERA)
    }

    companion object {
        /** The reason of a phone that can scan pose-free. */
        const val AVAILABLE = "pose-free capture available"

        /** The Details line of a phone without a back camera. */
        const val NO_CAMERA = "foss build: no back camera"
    }
}

/** Always [TrackingAvailability.UNSUPPORTED]: no ARCore, and nothing to install from an F-Droid build. */
object NoTrackerSupport : TrackerSupport {
    /** Why the tracker is never there. */
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
