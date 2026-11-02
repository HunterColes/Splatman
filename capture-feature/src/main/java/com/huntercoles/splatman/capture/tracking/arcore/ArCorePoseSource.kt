package com.huntercoles.splatman.capture.tracking.arcore

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.huntercoles.splatman.capture.tracking.CameraPermission
import com.huntercoles.splatman.capture.tracking.PoseSource
import com.huntercoles.splatman.capture.tracking.PoseSourceStatus
import com.huntercoles.splatman.capture.tracking.TrackedFrame
import com.huntercoles.splatman.capture.tracking.TrackerDriver
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

    override fun onResume(owner: LifecycleOwner) = driver.setForeground(true)

    override fun onPause(owner: LifecycleOwner) = driver.setForeground(false)

    override fun onDestroy(owner: LifecycleOwner) {
        owner.lifecycle.removeObserver(this)
        driver.dispose().invokeOnCompletion { executor.shutdown() }
    }
}
