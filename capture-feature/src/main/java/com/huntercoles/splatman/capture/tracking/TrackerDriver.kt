package com.huntercoles.splatman.capture.tracking

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

/** One open tracker session, platform types hidden. Every call comes from the driver's single thread. */
interface TrackerSession : AutoCloseable {
    /** Starts (or restarts) the camera and tracking. */
    fun resume()

    /** Stops the camera; the session stays open. */
    fun pause()

    /** The newest frame not yet returned (the caller owns it), or null when there is none yet. */
    fun update(): TrackedFrame?
}

/**
 * The tracker-independent half of a [PoseSource]: availability and permission are checked before any session call,
 * the session is resumed only while started and in the foreground, closed on [stop] and [dispose], and frames go to
 * one consumer through a one-slot buffer whose dropped frames are closed.
 *
 * Every session call runs on [dispatcher], which must be single-threaded (ARCore needs its GL context current on
 * the thread that calls `update`).
 */
class TrackerDriver(
    private val availability: suspend () -> TrackingAvailability,
    private val permissionGranted: () -> Boolean,
    private val openSession: () -> TrackerSession,
    private val dispatcher: CoroutineDispatcher,
    private val idleDelayMs: Long = IDLE_DELAY_MS,
) : PoseSource {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val channel = Channel<TrackedFrame>(
        capacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
        onUndeliveredElement = { it.close() },
    )
    private val mutableStatus = MutableStateFlow<PoseSourceStatus>(PoseSourceStatus.Idle)

    // Touched on [dispatcher] only.
    private var session: TrackerSession? = null
    private var pump: Job? = null
    private var foreground = true

    /** What the source is doing now. */
    val status: StateFlow<PoseSourceStatus> = mutableStatus.asStateFlow()

    override val frames: Flow<TrackedFrame> = channel.receiveAsFlow()

    override suspend fun start() {
        val available = try {
            availability()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TrackingAvailability.UNSUPPORTED
        }
        if (available != TrackingAvailability.SUPPORTED) {
            mutableStatus.value = PoseSourceStatus.Unavailable(available)
            return
        }
        if (!permissionGranted()) {
            mutableStatus.value = PoseSourceStatus.NeedsPermission
            return
        }
        withContext(dispatcher) {
            if (session != null) return@withContext
            val opened = try {
                openSession()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableStatus.value = PoseSourceStatus.Failed(e.message ?: e.javaClass.simpleName)
                return@withContext
            }
            session = opened
            if (foreground) resumeSession(opened) else mutableStatus.value = PoseSourceStatus.Paused
        }
    }

    override fun stop() {
        scope.launch {
            closeSession()
            if (mutableStatus.value !is PoseSourceStatus.Failed) mutableStatus.value = PoseSourceStatus.Idle
        }
    }

    /** Lifecycle: resume the session when [inForeground] (ON_RESUME), pause it otherwise (ON_PAUSE). */
    fun setForeground(inForeground: Boolean) {
        scope.launch {
            foreground = inForeground
            val open = session ?: return@launch
            if (inForeground) {
                if (pump == null) resumeSession(open)
            } else {
                pauseSession(open)
            }
        }
    }

    /** Closes the session and stops the driver for good; the returned job completes once the session is closed. */
    fun dispose(): Job = scope.launch { closeSession() }.also { job ->
        job.invokeOnCompletion { scope.cancel() }
    }

    private fun resumeSession(open: TrackerSession) {
        try {
            open.resume()
        } catch (e: Exception) {
            fail(e)
            return
        }
        mutableStatus.value = PoseSourceStatus.Running
        pump = scope.launch { pumpFrames(open) }
    }

    private suspend fun pauseSession(open: TrackerSession) {
        pump?.cancelAndJoin()
        pump = null
        try {
            open.pause()
        } catch (e: Exception) {
            fail(e)
            return
        }
        mutableStatus.value = PoseSourceStatus.Paused
    }

    private suspend fun pumpFrames(open: TrackerSession) {
        while (scope.isActive) {
            val frame = try {
                open.update()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                pump = null
                fail(e)
                return
            }
            if (frame == null) {
                delay(idleDelayMs)
            } else {
                channel.trySend(frame).onFailure { frame.close() }
                yield()
            }
        }
    }

    private suspend fun closeSession() {
        val running = pump
        pump = null
        running?.cancelAndJoin()
        val open = session
        session = null
        if (open != null) {
            try {
                open.pause()
            } catch (e: Exception) {
                // Closing anyway: a pause failure must not leak the session.
            } finally {
                open.close()
            }
        }
        while (true) {
            val pending = channel.tryReceive().getOrNull() ?: break
            pending.close()
        }
    }

    /** Closes the session after a failure; called on [dispatcher]. */
    private fun fail(e: Exception) {
        val open = session
        session = null
        try {
            open?.close()
        } catch (closeError: Exception) {
            e.addSuppressed(closeError)
        }
        mutableStatus.value = PoseSourceStatus.Failed(e.message ?: e.javaClass.simpleName)
    }

    private companion object {
        /** Wait between polls when the tracker has no new frame yet (~4 ms, well under one 30 fps frame). */
        const val IDLE_DELAY_MS = 4L
    }
}
