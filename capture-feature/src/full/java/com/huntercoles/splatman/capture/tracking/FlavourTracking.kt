package com.huntercoles.splatman.capture.tracking

import android.content.Context
import com.huntercoles.splatman.capture.presentation.capture.CaptureTracker
import com.huntercoles.splatman.capture.preview.PreviewTarget
import com.huntercoles.splatman.capture.tracking.arcore.ArCoreAvailability
import com.huntercoles.splatman.capture.tracking.arcore.ArCorePoseSource
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** The `full` flavour's tracking: ARCore (ADR 0004). The `foss` flavour has a same-named object without it. */
object FlavourTracking {
    /** ARCore's availability check and install offer. */
    fun support(context: Context): TrackerSupport = ArCoreAvailability(context)

    /** A fresh ARCore tracker for one capture view model. */
    fun captureTracker(context: Context): CaptureTracker = ArCoreCaptureTracker(ArCorePoseSource(context))
}

/** [CaptureTracker] on ARCore. */
class ArCoreCaptureTracker(private val source: ArCorePoseSource) : CaptureTracker {
    override val status: StateFlow<PoseSourceStatus> get() = source.status
    override val frames: Flow<TrackedFrame> get() = source.frames
    override suspend fun start() = source.start()
    override fun setForeground(inForeground: Boolean) = source.setForeground(inForeground)
    override fun setPreview(target: PreviewTarget?): Job = source.setPreview(target)
    override fun release() = source.release()
}
