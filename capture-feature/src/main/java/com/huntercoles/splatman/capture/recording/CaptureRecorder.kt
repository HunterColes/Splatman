package com.huntercoles.splatman.capture.recording

import com.huntercoles.splatman.capture.tracking.FeaturePoints
import com.huntercoles.splatman.capture.tracking.LumaPlane
import com.huntercoles.splatman.capture.tracking.TrackedFrame
import com.huntercoles.splatman.capture.tracking.TrackingState
import com.huntercoles.splatman.splat.capture.KeyframePolicy
import com.huntercoles.splatman.splat.capture.KeyframeSelector
import com.huntercoles.splatman.splat.capture.SeedAccumulator
import com.huntercoles.splatman.splat.capture.Sharpness
import com.huntercoles.splatman.splat.io.bundle.BundleFrameInput
import com.huntercoles.splatman.splat.io.bundle.CaptureBundleWriter
import com.huntercoles.splatman.splat.math.Vec3
import com.huntercoles.splatman.splat.model.CameraIntrinsics
import com.huntercoles.splatman.splat.model.CameraPose
import com.huntercoles.splatman.splat.model.DepthImage
import com.huntercoles.splatman.splat.model.RgbImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/** What the recorder is doing. */
sealed interface RecorderState {
    /** Not recording; nothing kept. */
    data object Idle : RecorderState

    /**
     * Taking keyframes: [coverage] is [Coverage.fraction] of [cells], [droppedFrames] frames skipped to keep up.
     */
    data class Recording(
        val keyframes: Int,
        val coverage: Float,
        val seedPoints: Int,
        val droppedFrames: Int,
        val cells: CoverageCells = CoverageCells(),
    ) : RecorderState

    /** Writing the bundle of [keyframes] keyframes. */
    data class Saving(val keyframes: Int) : RecorderState

    /** The bundle is in `scans/[scanId]/`. */
    data class Saved(val scanId: String, val keyframes: Int) : RecorderState

    /** Stopped before any keyframe was taken: nothing written. */
    data object NothingRecorded : RecorderState

    /** Writing failed; nothing left in `scans/`. */
    data class Failed(val reason: String) : RecorderState
}

/**
 * Records a scan (ADR 0004, 0007): [submit] hands it every [TrackedFrame]; a worker on [worker] scores sharpness on
 * the luma plane, asks a [KeyframeSelector], and for each keyframe converts the image to RGBA, resizes it to
 * 640x480 (480x640 for a portrait image), encodes it as JPEG q90 and feeds feature points and depth to a
 * [SeedAccumulator]. [stop] writes the capture bundle with [CaptureBundleWriter] into `stagingRoot/<id>/` on [io],
 * then renames it to `scansRoot/<id>/`, so `scans/` never holds half a bundle; [cancel] keeps nothing.
 *
 * The preview never waits for recording: [submit] does not suspend, one frame waits for the worker and any other is
 * dropped. Every frame is closed exactly once, kept or not; its camera image is released before the JPEG is encoded.
 * Call [start], [submit], [stop], [cancel] and [release] from one thread.
 */
class CaptureRecorder(
    private val scansRoot: File,
    private val stagingRoot: File,
    private val encoder: JpegEncoder,
    private val worker: CoroutineDispatcher,
    private val io: CoroutineDispatcher,
    policy: KeyframePolicy = KeyframePolicy(),
    private val newScanId: () -> String = ::timestampScanId,
) {
    private val scope = CoroutineScope(SupervisorJob())
    private val mutableState = MutableStateFlow<RecorderState>(RecorderState.Idle)

    /** What the recorder is doing, updated after every frame while recording. */
    val state: StateFlow<RecorderState> = mutableState.asStateFlow()

    // Owned by the worker while recording; the save reads them only after the worker has finished.
    private val selector = KeyframeSelector(policy)
    private var seeds: SeedAccumulator? = null
    private val coverage = Coverage()
    private val mutableMeanLuma = MutableStateFlow<Float?>(null)
    private val kept = ArrayList<BundleFrameInput>()
    private val dropped = AtomicInteger()

    /**
     * Mean luma (0..255) of the latest frame the worker read while recording, kept or not; null before one. The
     * exposure signal of the capture guidance.
     */
    val meanLuma: StateFlow<Float?> = mutableMeanLuma.asStateFlow()

    @Volatile
    private var inbox: Channel<Submitted>? = null
    private var workerJob: Job? = null
    private var controlJob: Job? = null

    /** True between [start] and [stop] or [cancel]. */
    val isRecording: Boolean get() = inbox != null

    /**
     * Starts a new recording whose coverage is measured round [subject] (world frame W); without one, the point
     * [DEFAULT_SUBJECT_M] in front of the first keyframe. False (and nothing changes) while recording or while a save
     * or cancel runs.
     */
    fun start(subject: Vec3? = null): Boolean {
        if (inbox != null || controlJob?.isActive == true) return false
        val channel = Channel<Submitted>(capacity = 1, onUndeliveredElement = { it.frame.close() })
        inbox = channel
        dropped.set(0)
        mutableMeanLuma.value = null
        mutableState.value = RecorderState.Recording(keyframes = 0, coverage = 0f, seedPoints = 0, droppedFrames = 0)
        workerJob = scope.launch(worker) {
            selector.reset()
            coverage.reset(subject)
            kept.clear()
            (seeds ?: SeedAccumulator().also { seeds = it }).clear()
            for (item in channel) process(item)
        }
        return true
    }

    /**
     * Takes ownership of [frame]: queues it for the worker, or closes it now when not recording or still busy. A
     * frame with [keep] false is only measured ([meanLuma]), never kept (paused, or a warning is live).
     */
    fun submit(frame: TrackedFrame, keep: Boolean = true) {
        val channel = inbox
        if (channel == null) {
            frame.close()
            return
        }
        if (!channel.trySend(Submitted(frame, keep)).isSuccess) {
            dropped.incrementAndGet()
            frame.close()
        }
    }

    /**
     * Ends the recording and writes the bundle in the background: [state] goes [RecorderState.Saving], then
     * [RecorderState.Saved], [RecorderState.NothingRecorded] or [RecorderState.Failed]. The job ends with the save.
     */
    fun stop(): Job {
        val channel = inbox ?: return controlJob ?: Job().apply { complete() }
        inbox = null
        channel.close() // the worker finishes the frame that waits, then ends
        val running = workerJob // kept, so a cancel during the save can stop it too
        return scope.launch(io) {
            running?.join()
            save()
        }.also { controlJob = it }
    }

    /**
     * Abandons the recording, or a save still under way: nothing it made stays on disk and [state] goes back to
     * [RecorderState.Idle]. A save that already finished is kept.
     */
    fun cancel(): Job {
        val channel = inbox
        inbox = null
        channel?.cancel() // a waiting frame is closed by the channel
        val running = workerJob
        workerJob = null
        val pending = controlJob
        // Cancelled here, not in the coroutine below, so a save that has not started yet never starts.
        running?.cancel()
        pending?.cancel()
        return scope.launch(io) {
            running?.join()
            pending?.join()
            kept.clear()
            if (mutableState.value !is RecorderState.Saved) mutableState.value = RecorderState.Idle
        }.also { controlJob = it }
    }

    /** The owner is gone: a recording is abandoned, a save under way still finishes. */
    fun release() {
        if (inbox != null) cancel()
    }

    private fun process(submitted: Submitted) {
        val frame = submitted.frame
        val candidate = try {
            frame.use { select(it, submitted.keep) }
        } catch (e: IllegalArgumentException) {
            skip(e)
        } catch (e: IllegalStateException) {
            skip(e)
        }
        if (candidate != null) {
            try {
                keep(candidate)
            } catch (e: IllegalArgumentException) {
                skip(e)
            } catch (e: IOException) {
                skip(e)
            }
        }
        mutableState.value = RecorderState.Recording(
            keyframes = kept.size,
            coverage = coverage.fraction,
            seedPoints = seeds?.count ?: 0,
            droppedFrames = dropped.get(),
            cells = coverage.cells,
        )
    }

    private fun skip(e: Exception): Candidate? {
        Timber.w(e, "Recording skipped a frame")
        dropped.incrementAndGet()
        return null
    }

    /** Sharpness and the keyframe policy; for a keyframe, copies out everything kept so the frame can be closed. */
    private fun select(frame: TrackedFrame, keep: Boolean): Candidate? {
        val image = frame.image ?: return null
        val luma = image.luma()
        mutableMeanLuma.value = meanLuma(luma)
        if (!keep || frame.trackingState != TrackingState.TRACKING) return null
        val sharpness = Sharpness.laplacianVariance(luma.bytes, luma.width, luma.height, luma.rowStride)
        if (!selector.offer(frame.pose, trackingOk = true, sharpness = sharpness)) return null
        return Candidate(frame.timestampNs, frame.pose, frame.intrinsics, image.toRgb(), frame.points, frame.depth)
    }

    private fun keep(c: Candidate) {
        val portrait = c.rgb.height > c.rgb.width
        val sized = RgbResize.toSize(
            c.rgb,
            c.intrinsics,
            if (portrait) IMAGE_SHORT_SIDE else IMAGE_LONG_SIDE,
            if (portrait) IMAGE_LONG_SIDE else IMAGE_SHORT_SIDE,
        )
        val jpeg = encoder.encode(sized.image, JPEG_QUALITY)
        val acc = checkNotNull(seeds)
        acc.addPoints(c.points.positions, c.points.confidence, c.points.count, sized.intrinsics, c.pose, sized.image)
        c.depth?.let { acc.addDepth(it, sized.intrinsics, c.pose, sized.image) }
        if (coverage.subject == null) coverage.reset(subjectAhead(c.pose))
        coverage.add(c.pose)
        kept += BundleFrameInput(c.timestampNs, sized.intrinsics, c.pose, jpeg, c.depth)
    }

    private suspend fun save() {
        val frames = kept.toList()
        kept.clear()
        if (frames.isEmpty()) {
            mutableState.value = RecorderState.NothingRecorded
            return
        }
        mutableState.value = RecorderState.Saving(frames.size)
        val id = freeScanId()
        val staging = File(stagingRoot, id)
        val target = File(scansRoot, id)
        var moved = false
        try {
            stagingRoot.listFiles()?.forEach { it.deleteRecursively() } // left by a process that died mid-save
            CaptureBundleWriter.write(staging, frames, checkNotNull(seeds).toSeedPoints())
            currentCoroutineContext().ensureActive()
            if (!scansRoot.isDirectory && !scansRoot.mkdirs()) throw IOException("cannot create ${scansRoot.path}")
            if (!staging.renameTo(target)) throw IOException("cannot move the scan into ${scansRoot.path}")
            moved = true
            currentCoroutineContext().ensureActive()
            mutableState.value = RecorderState.Saved(id, frames.size)
        } catch (e: CancellationException) {
            staging.deleteRecursively()
            if (moved) target.deleteRecursively()
            throw e
        } catch (e: IOException) {
            Timber.w(e, "Saving the scan failed")
            staging.deleteRecursively()
            mutableState.value = RecorderState.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun freeScanId(): String {
        val base = newScanId()
        var id = base
        var n = 2
        while (File(scansRoot, id).exists()) id = "${base}_${n++}"
        return id
    }

    /** A frame queued for the worker and whether it may be kept. */
    private class Submitted(val frame: TrackedFrame, val keep: Boolean)

    /** What a keyframe keeps of its frame once the camera image is closed. */
    private class Candidate(
        val timestampNs: Long,
        val pose: CameraPose,
        val intrinsics: CameraIntrinsics,
        val rgb: RgbImage,
        val points: FeaturePoints,
        val depth: DepthImage?,
    )

    companion object {
        /** Keyframe JPEG quality (ADR 0007). */
        const val JPEG_QUALITY = 90

        /** Keyframe image size, long side by short side, pixels. */
        const val IMAGE_LONG_SIDE = 640
        const val IMAGE_SHORT_SIDE = 480

        /** Without a subject, coverage is measured round the point this far in front of the first keyframe, metres. */
        const val DEFAULT_SUBJECT_M = 0.6f

        private const val LUMA_STEP = 8
        private val LOOK = Vec3(0f, 0f, -1f)

        private fun subjectAhead(pose: CameraPose): Vec3 =
            pose.translationM + pose.rotation.normalized().rotate(LOOK) * DEFAULT_SUBJECT_M

        /** Mean of every [LUMA_STEP]th pixel of every [LUMA_STEP]th row, 0..255. */
        internal fun meanLuma(plane: LumaPlane): Float {
            var sum = 0L
            var n = 0
            var y = 0
            while (y < plane.height) {
                var x = 0
                val row = y * plane.rowStride
                while (x < plane.width) {
                    sum += plane.bytes[row + x].toInt() and 0xFF
                    n++
                    x += LUMA_STEP
                }
                y += LUMA_STEP
            }
            return sum.toFloat() / n
        }
    }
}

/** `scan_20261102_143012`: the folder name, and so the library's first name, of a new scan. */
fun timestampScanId(): String = "scan_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
