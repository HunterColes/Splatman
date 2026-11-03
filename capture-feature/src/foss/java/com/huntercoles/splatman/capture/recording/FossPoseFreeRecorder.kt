package com.huntercoles.splatman.capture.recording

import com.huntercoles.splatman.capture.presentation.capture.GuidanceWarning
import com.huntercoles.splatman.capture.preview.PreviewTarget
import com.huntercoles.splatman.splat.capture.GyroIntegrator
import com.huntercoles.splatman.splat.capture.ImageChange
import com.huntercoles.splatman.splat.capture.PoseFreeKeyframePolicy
import com.huntercoles.splatman.splat.capture.PoseFreeKeyframeSelector
import com.huntercoles.splatman.splat.capture.Sharpness
import com.huntercoles.splatman.splat.io.bundle.CameraCalibration
import com.huntercoles.splatman.splat.io.bundle.FrameEntry
import com.huntercoles.splatman.splat.io.bundle.IMU_FILE
import com.huntercoles.splatman.splat.io.bundle.ImuCsvWriter
import com.huntercoles.splatman.splat.io.bundle.PoseFreeCaptureReader
import com.huntercoles.splatman.splat.io.bundle.PoseFreeCaptureWriter
import com.huntercoles.splatman.splat.io.bundle.PoseFreeFrameInput
import com.huntercoles.splatman.splat.math.Quat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.min

/**
 * The foss [PoseFreeRecorder] (#216): frames from a [PoseFreeFrameSource] (Camera2 on a phone), gyro and
 * accelerometer from an [ImuRecorder] into `imu.csv`.
 *
 * Frames: the source hands each one to a one-slot queue; when the worker is still busy the new frame is closed at
 * once and counted in `droppedFrames`, so the camera's reader never stalls. Per frame on [worker]: the luma plane's
 * mean, [Sharpness.laplacianVariance], the gyro rate, [PoseFreeGuidanceTracker] warnings; while recording and not
 * paused, a 64x48 thumbnail for [ImageChange], [PoseFreeKeyframeSelector], and for a keyframe [GyroCoverage], RGB
 * conversion (the camera image is closed before encoding), [JpegEncoder] q90 and [PoseFreeCaptureWriter.writeFrame]
 * straight into `stagingRoot/<id>/`: no image is kept in memory.
 *
 * [stop] closes `imu.csv`, writes `calibration.json` and `capture.json` and renames the folder into
 * `scansRoot/<id>/` on [io]. [cancel] deletes it. A camera lost or a write failure mid-recording ends in
 * [PoseFreeState.Failed]; the staging folder stays for a retry only when `capture.json` could be finished.
 */
class FossPoseFreeRecorder(
    private val source: PoseFreeFrameSource,
    private val imu: ImuRecorder,
    private val scansRoot: File,
    private val stagingRoot: File,
    private val encoder: JpegEncoder,
    private val worker: CoroutineDispatcher,
    private val io: CoroutineDispatcher,
    private val policy: PoseFreeKeyframePolicy = PoseFreeKeyframePolicy(),
    private val newScanId: () -> String = ::timestampScanId,
    private val onRelease: () -> Unit = {},
) : PoseFreeRecorder {
    private val scope = CoroutineScope(SupervisorJob())
    private val mutableState = MutableStateFlow<PoseFreeState>(PoseFreeState.Idle)
    override val state: StateFlow<PoseFreeState> = mutableState.asStateFlow()

    /** Held by the worker for each frame; a save takes it once so the frame in flight finishes first. */
    private val frameLock = Mutex()

    /** Orders the camera calls (preview, resume) made off the caller's thread. */
    private val controlLock = Mutex()

    private val gyroLock = Any()
    private val integrator = GyroIntegrator() // guarded by gyroLock

    // Worker only.
    private val guidance = PoseFreeGuidanceTracker()
    private val floor = SharpnessFloor(policy)
    private val thumb = ByteArray(ImageChange.SIZE)

    private val current = AtomicReference<Take?>(null)
    private val dropped = AtomicInteger()

    @Volatile private var inbox: Channel<SourceFrame>? = null

    @Volatile private var opened = false

    @Volatile private var foreground = true

    @Volatile private var released = false

    @Volatile private var calibration: CameraCalibration? = null

    @Volatile private var saveJob: Job? = null

    @Volatile private var savingTake: Take? = null

    init {
        imu.addGyroListener { t, x, y, z -> synchronized(gyroLock) { integrator.add(t, x, y, z) } }
    }

    private val listener = object : PoseFreeFrameSource.Listener {
        override fun onFrame(frame: SourceFrame) {
            val channel = inbox
            if (channel == null || !foreground) {
                frame.close()
                return
            }
            if (!channel.trySend(frame).isSuccess) {
                dropped.incrementAndGet()
                frame.close()
            }
        }

        override fun onError(reason: String) {
            if (!foreground || !opened) return // closed on purpose
            Timber.w("PoseFreeRecorder: camera lost: %s", reason)
            opened = false
            closeFrames()
            scope.launch(io) { controlLock.withLock { source.close() } }
            lose(reason)
        }
    }

    override suspend fun open(): Boolean {
        if (released) return false
        if (opened) return true
        mutableState.value = PoseFreeState.Opening
        val channel = Channel<SourceFrame>(capacity = 1, onUndeliveredElement = { it.close() })
        inbox = channel
        scope.launch(worker) { for (frame in channel) process(frame) }
        return when (val r = controlLock.withLock { source.open(listener) }) {
            is SourceOpen.Refused -> {
                closeFrames()
                controlLock.withLock { source.close() }
                mutableState.value = PoseFreeState.Failed(r.reason)
                false
            }
            is SourceOpen.Opened -> {
                calibration = r.calibration
                Camera2Calibration.log(r.calibration)
                opened = true
                mutableState.value = PoseFreeState.Previewing()
                true
            }
        }
    }

    override fun setPreview(target: PreviewTarget?): Job =
        scope.launch(io) { controlLock.withLock { if (!released) source.setPreview(target) } }

    override fun setForeground(inForeground: Boolean) {
        if (foreground == inForeground) return
        foreground = inForeground
        if (!opened) return
        val take = current.get()
        if (!inForeground) {
            source.suspend()
            if (take != null) imu.stop()
        } else {
            if (take != null && !imu.isRecording) imu.start(take.imuWriter)
            scope.launch(io) { controlLock.withLock { if (opened) source.resume() } }
        }
    }

    override fun start(): Boolean {
        if (!opened || !foreground || current.get() != null || saveJob?.isActive == true) return false
        if (calibration == null) return false
        val id = freeScanId()
        val dir = File(stagingRoot, id)
        val take = try {
            clearUnfinishedStaging()
            if (!dir.mkdirs()) throw IOException("cannot create ${dir.path}")
            Take(id, dir, ImuCsvWriter(File(dir, IMU_FILE)), PoseFreeKeyframeSelector(policy))
        } catch (e: IOException) {
            Timber.w(e, "PoseFreeRecorder: cannot start")
            dir.deleteRecursively()
            mutableState.value = PoseFreeState.Failed(e.message ?: e.javaClass.simpleName)
            return false
        }
        synchronized(gyroLock) { integrator.reset() }
        dropped.set(0)
        current.set(take)
        val imuStart = imu.start(take.imuWriter)
        if (imuStart.missing.isNotEmpty()) Timber.w("PoseFreeRecorder: no %s on this phone", imuStart.missing)
        mutableState.value = PoseFreeState.Recording(0, 0f, CoverageCells(), emptySet(), 0)
        return true
    }

    override fun pause(paused: Boolean) {
        current.get()?.paused = paused
    }

    override fun stop(): Job {
        val take = current.getAndSet(null) ?: return saveJob ?: done()
        imu.stop()
        savingTake = take
        return scope.launch(io) {
            frameLock.withLock { } // the frame in flight is written first
            save(take)
        }.also { saveJob = it }
    }

    override fun cancel(): Job {
        val take = current.getAndSet(null)
        if (take != null) imu.stop()
        val pending = saveJob
        val saving = savingTake
        pending?.cancel() // before it starts, if it has not
        return scope.launch(io) {
            frameLock.withLock { }
            pending?.join()
            take?.let { discard(it) }
            if (saving != null && mutableState.value !is PoseFreeState.Saved) discard(saving)
            savingTake = null
            if (mutableState.value !is PoseFreeState.Saved) {
                mutableState.value = if (opened) PoseFreeState.Previewing() else PoseFreeState.Idle
            }
        }.also { saveJob = it }
    }

    override fun release() {
        if (released) return
        released = true
        val take = current.getAndSet(null)
        imu.stop()
        opened = false
        closeFrames()
        scope.launch(io) {
            controlLock.withLock { source.close() }
            frameLock.withLock { }
            take?.let { discard(it) }
            val s = mutableState.value
            if (s is PoseFreeState.Opening || s is PoseFreeState.Previewing || s is PoseFreeState.Recording) {
                mutableState.value = PoseFreeState.Idle
            }
        }
        onRelease()
    }

    private fun closeFrames() {
        inbox?.close() // the worker drains what waits, then ends
        inbox = null
    }

    /** Camera lost or a write failed: the recording ends in Failed, keeping a folder only if it is a capture. */
    private fun lose(reason: String) {
        val take = current.getAndSet(null)
        if (take == null) {
            if (saveJob?.isActive != true) mutableState.value = PoseFreeState.Failed(reason)
            return
        }
        imu.stop()
        saveJob = scope.launch(io) {
            frameLock.withLock { }
            abandon(take, reason)
        }
    }

    private suspend fun process(frame: SourceFrame) {
        frameLock.withLock {
            val take = current.get()
            val warnings = try {
                frame.use { measure(it, take) }
            } catch (e: IllegalArgumentException) {
                skip(e)
            } catch (e: IllegalStateException) {
                skip(e)
            } catch (e: IOException) {
                Timber.w(e, "PoseFreeRecorder: writing a keyframe failed")
                lose("cannot write the scan: ${e.message}")
                null
            }
            if (warnings != null) publish(take, warnings)
        }
    }

    private fun publish(take: Take?, warnings: Set<GuidanceWarning>) {
        if (take == null) {
            if (mutableState.value is PoseFreeState.Previewing) mutableState.value = PoseFreeState.Previewing(warnings)
            return
        }
        imu.failure?.let { lose("cannot write imu.csv: ${it.message}") }
        if (current.get() !== take) return
        mutableState.value = PoseFreeState.Recording(
            keyframes = take.entries.size,
            coverage = take.coverage.fraction,
            cells = take.coverage.cells,
            warnings = warnings,
            droppedFrames = dropped.get(),
        )
    }

    private fun skip(e: Exception): Set<GuidanceWarning> {
        Timber.w(e, "PoseFreeRecorder: skipped a frame")
        dropped.incrementAndGet()
        return emptySet()
    }

    /** Guidance for [frame]; while [take] records, keeps it when the selector takes it. */
    private fun measure(frame: SourceFrame, take: Take?): Set<GuidanceWarning> {
        val luma = frame.image.luma()
        val meanLuma = CaptureRecorder.meanLuma(luma)
        val sharpness = Sharpness.laplacianVariance(
            luma.bytes,
            luma.width,
            luma.height,
            luma.rowStride,
            max(Sharpness.DEFAULT_DOWNSAMPLE, luma.width / SHARPNESS_WIDTH),
        )
        val turned: Float
        val rate: Float
        val orientation: Quat
        synchronized(gyroLock) {
            turned = integrator.rotationSinceMarkRad()
            rate = integrator.rateRadS()
            orientation = integrator.orientation()
        }
        val warnings = guidance.update(rate, sharpness, floor.offer(sharpness), meanLuma)
        if (take == null || take.paused) return warnings
        ImageChange.thumbnail(luma.bytes, luma.width, luma.height, luma.rowStride, thumb)
        val change = if (take.hasThumb) ImageChange.meanAbsDiff(thumb, take.lastThumb) else 0f
        if (!take.selector.offer(frame.timestampNs, turned, change, rate, sharpness)) return warnings
        synchronized(gyroLock) { integrator.mark() }
        thumb.copyInto(take.lastThumb)
        take.hasThumb = true
        take.coverage.add(orientation, imu.latestGravity)
        val rgb = frame.image.toRgb()
        frame.close() // the camera gets its buffer back before the slow part
        val jpeg = encoder.encode(rgb, JPEG_QUALITY)
        val input = PoseFreeFrameInput(frame.timestampNs, rgb.width, rgb.height, sharpness, jpeg, null, frame.exposureNs)
        take.entries += PoseFreeCaptureWriter.writeFrame(take.dir, take.entries.size, input)
        return warnings
    }

    private suspend fun save(take: Take) {
        val frames = take.entries.toList()
        if (frames.isEmpty()) {
            discard(take)
            savingTake = null
            mutableState.value = PoseFreeState.Failed(NO_FRAMES)
            return
        }
        mutableState.value = PoseFreeState.Saving(frames.size)
        var target: File? = null
        try {
            finishCapture(take)
            currentCoroutineContext().ensureActive()
            if (!scansRoot.isDirectory && !scansRoot.mkdirs()) throw IOException("cannot create ${scansRoot.path}")
            val id = if (File(scansRoot, take.id).exists()) freeScanId() else take.id
            val into = File(scansRoot, id)
            if (!take.dir.renameTo(into)) throw IOException("cannot move the scan into ${scansRoot.path}")
            target = into
            currentCoroutineContext().ensureActive()
            savingTake = null
            mutableState.value = PoseFreeState.Saved(id, frames.size)
        } catch (e: CancellationException) {
            discard(take)
            target?.deleteRecursively()
            throw e
        } catch (e: IOException) {
            Timber.w(e, "PoseFreeRecorder: saving the scan failed")
            discard(take)
            savingTake = null
            mutableState.value = PoseFreeState.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun abandon(take: Take, reason: String) {
        val kept = take.entries.isNotEmpty() && try {
            finishCapture(take)
            true
        } catch (e: IOException) {
            Timber.w(e, "PoseFreeRecorder: cannot finish the lost capture")
            false
        }
        if (!kept) discard(take)
        mutableState.value = PoseFreeState.Failed(reason)
    }

    /** Closes `imu.csv`, writes `calibration.json`, then `capture.json` last. */
    private fun finishCapture(take: Take) {
        take.imuWriter.close()
        val cal = checkNotNull(calibration)
        PoseFreeCaptureWriter.writeCalibration(take.dir, cal)
        PoseFreeCaptureWriter.finish(take.dir, take.entries, cal.timestampSource)
    }

    private fun discard(take: Take) {
        try {
            take.imuWriter.close()
        } catch (e: IOException) {
            Timber.w(e, "PoseFreeRecorder: closing imu.csv of a discarded scan")
        }
        take.dir.deleteRecursively()
    }

    /** Removes staging folders a dead process left without `capture.json`; finished ones wait for a retry. */
    private fun clearUnfinishedStaging() {
        stagingRoot.listFiles()?.forEach { dir ->
            if (!PoseFreeCaptureReader.isPoseFreeCapture(dir)) dir.deleteRecursively()
        }
    }

    private fun freeScanId(): String {
        val base = newScanId()
        var id = base
        var n = 2
        while (File(scansRoot, id).exists() || File(stagingRoot, id).exists()) id = "${base}_${n++}"
        return id
    }

    private fun done(): Job = Job().apply { complete() }

    /** One recording: its folder, its open `imu.csv` and what it has kept. */
    private class Take(
        val id: String,
        val dir: File,
        val imuWriter: ImuCsvWriter,
        val selector: PoseFreeKeyframeSelector,
    ) {
        val coverage = GyroCoverage()
        val entries = ArrayList<FrameEntry>() // worker only until the save
        val lastThumb = ByteArray(ImageChange.SIZE)
        var hasThumb = false

        @Volatile var paused = false
    }

    companion object {
        /** Keyframe JPEG quality (ADR 0007). */
        const val JPEG_QUALITY = 90

        /** The reason of a stop before any keyframe. */
        const val NO_FRAMES = "no frames"

        /** Sharpness is scored on a plane about this wide (1080p is box-downsampled by 3). */
        private const val SHARPNESS_WIDTH = 640
    }
}

/**
 * The guidance's blur floor: the [PoseFreeKeyframePolicy.sharpnessQuantile] of the last
 * [PoseFreeKeyframePolicy.sharpnessWindow] frames' sharpness, as the selector judges it. Not thread-safe.
 */
internal class SharpnessFloor(private val policy: PoseFreeKeyframePolicy) {
    private val window = FloatArray(policy.sharpnessWindow)
    private val scratch = FloatArray(policy.sharpnessWindow)
    private var size = 0
    private var next = 0

    /** The floor before [sharpness] joins the window (negative infinity while it is empty). */
    fun offer(sharpness: Float): Float {
        val floor = if (size == 0) {
            Float.NEGATIVE_INFINITY
        } else {
            System.arraycopy(window, 0, scratch, 0, size)
            scratch.sort(0, size)
            scratch[min((size * policy.sharpnessQuantile).toInt(), size - 1)]
        }
        window[next] = sharpness
        next = (next + 1) % window.size
        if (size < window.size) size++
        return floor
    }
}
