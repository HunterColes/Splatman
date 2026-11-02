package com.huntercoles.splatman.capture.tracking.arcore

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.huntercoles.splatman.capture.preview.PreviewTarget
import com.huntercoles.splatman.capture.tracking.CameraPermission
import com.huntercoles.splatman.capture.tracking.PoseSource
import com.huntercoles.splatman.capture.tracking.PoseSourceStatus
import com.huntercoles.splatman.capture.tracking.TrackedFrame
import com.huntercoles.splatman.capture.tracking.TrackerDriver
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.Executors

/**
 * ARCore behind the [PoseSource] seam (ADR 0004). Add it to the capture screen's lifecycle: ON_RESUME resumes the
 * session, ON_PAUSE pauses it, ON_DESTROY closes it. [start] checks ARCore availability and the CAMERA permission
 * before any session call; a phone without ARCore ends in [PoseSourceStatus.Unavailable], never a crash.
 */
class ArCorePoseSource(context: Context) : PoseSource, DefaultLifecycleObserver {
    private val appContext = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "arcore-tracking") }
    private val availability = ArCoreAvailability(appContext)
    private val driver = TrackerDriver(
        availability = { availability.check() },
        permissionGranted = { CameraPermission.isGranted(appContext) },
        openSession = { ArCoreTrackerSession(appContext) },
        dispatcher = executor.asCoroutineDispatcher(),
    )

    /** What the source is doing now (for the capture screen). */
    val status: StateFlow<PoseSourceStatus> get() = driver.status

    override val frames: Flow<TrackedFrame> get() = driver.frames

    override suspend fun start() = driver.start()

    override fun stop() = driver.stop()

    /** Resumes the session in the foreground, pauses it otherwise (what ON_RESUME and ON_PAUSE do). */
    fun setForeground(inForeground: Boolean) = driver.setForeground(inForeground)

    /** Draws the camera into [target], or stops when null; the job completes once the session took it. */
    fun setPreview(target: PreviewTarget?): Job = driver.setPreview(target)

    /** Closes the session and the tracking thread for good (what ON_DESTROY does). */
    fun release() {
        driver.dispose().invokeOnCompletion { executor.shutdown() }
    }

    override fun onResume(owner: LifecycleOwner) = setForeground(true)

    override fun onPause(owner: LifecycleOwner) = setForeground(false)

    override fun onDestroy(owner: LifecycleOwner) {
        owner.lifecycle.removeObserver(this)
        release()
    }
}
