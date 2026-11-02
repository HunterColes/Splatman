package com.huntercoles.splatman.capture.presentation.capture

import android.content.Context
import com.huntercoles.splatman.capture.preview.PreviewTarget
import com.huntercoles.splatman.capture.tracking.PoseSourceStatus
import com.huntercoles.splatman.capture.tracking.TrackedFrame
import com.huntercoles.splatman.capture.tracking.arcore.ArCorePoseSource
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** What the capture screen needs from the tracker; one per [CaptureViewModel], released with it. */
interface CaptureTracker {
    /** What the tracker is doing. */
    val status: StateFlow<PoseSourceStatus>

    /** Tracked frames; the collector closes each one. */
    val frames: Flow<TrackedFrame>

    /** Checks availability and permission, then opens the session (again after a refusal or failure). */
    suspend fun start()

    /** Resumes the session in the foreground, pauses it otherwise. */
    fun setForeground(inForeground: Boolean)

    /** Draws the camera into [target], or stops when null; the job completes once the tracker took it. */
    fun setPreview(target: PreviewTarget?): Job

    /** Closes the session for good. */
    fun release()
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

/** A fresh ARCore tracker for every capture view model (unscoped on purpose: each one is released with its owner). */
@Module
@InstallIn(ViewModelComponent::class)
object CaptureTrackerModule {
    @Provides
    fun captureTracker(@ApplicationContext context: Context): CaptureTracker =
        ArCoreCaptureTracker(ArCorePoseSource(context))
}
