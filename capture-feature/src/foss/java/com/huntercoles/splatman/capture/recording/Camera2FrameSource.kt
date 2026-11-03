package com.huntercoles.splatman.capture.recording

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.view.Surface
import com.huntercoles.splatman.capture.preview.PreviewTarget
import com.huntercoles.splatman.capture.tracking.CameraImage
import com.huntercoles.splatman.capture.tracking.MediaCameraImage
import com.huntercoles.splatman.splat.io.bundle.CameraCalibration
import com.huntercoles.splatman.splat.io.bundle.ImageSize
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import kotlin.coroutines.resume

/**
 * One camera frame for the [PoseFreeRecorder]: the receiver closes it exactly once (a second [close] does nothing).
 * [keep] copies a keyframe's pixels out before the close, so the camera gets its buffer back before the encode.
 */
class SourceFrame(
    /** `SENSOR_TIMESTAMP` of the matching CaptureResult (the image's own timestamp until it arrives), ns. */
    val timestampNs: Long,
    /** `SENSOR_EXPOSURE_TIME`, ns, when the matching CaptureResult arrived first. */
    val exposureNs: Long?,
    /** The YUV image, sensor orientation. */
    val image: CameraImage,
    /** Copies the image's planes as NV21 (the camera); null converts through [CameraImage.toRgb]. */
    private val copyNv21: (() -> KeptPixels.Nv21)? = null,
) : AutoCloseable {
    @Volatile private var closed = false

    /** The pixels a keyframe needs, owned by the caller: NV21 from the camera, RGB otherwise. Before [close] only. */
    fun keep(): KeptPixels {
        check(!closed) { "frame already closed" }
        return copyNv21?.invoke() ?: KeptPixels.Rgb(image.toRgb())
    }

    override fun close() {
        if (closed) return
        closed = true
        image.close()
    }
}

/** How [PoseFreeFrameSource.open] went. */
sealed interface SourceOpen {
    /** The camera runs; [calibration] is the chosen stream's. */
    data class Opened(val calibration: CameraCalibration) : SourceOpen

    /** No camera: [reason] says why (permission, no back camera, in use, ...). */
    data class Refused(val reason: String) : SourceOpen
}

/** Where a [FossPoseFreeRecorder] gets its frames: [Camera2FrameSource] on a phone, a fake in tests. */
interface PoseFreeFrameSource {
    /** Receives frames and failures on the source's own thread. */
    interface Listener {
        /** A new frame; the listener owns it and must close it. */
        fun onFrame(frame: SourceFrame)

        /** The camera was lost after [open] (disconnected, error, session refused). */
        fun onError(reason: String)
    }

    /** Opens the camera and starts frames into [listener]. */
    suspend fun open(listener: Listener): SourceOpen

    /** Shows frames on [target] too, or only feeds the listener (null). */
    suspend fun setPreview(target: PreviewTarget?)

    /** Background: closes the camera, keeps the choice of camera, size and preview. */
    fun suspend()

    /** Foreground again after [suspend]: reopens the camera; a failure reaches [Listener.onError]. */
    suspend fun resume()

    /** Closes everything; the source is not reused. */
    fun close()
}

/**
 * The back camera through Camera2 (ADR 0004 foss path): a YUV_420_888 [ImageReader] at 1920x1080 (1280x720 when the
 * camera does not offer 1080p) at 30 fps, [MAX_IMAGES] images, only the latest one read; frames carry the matching
 * CaptureResult's timestamp and exposure. Callbacks run on one camera thread. The preview surface should be sized to
 * the stream ([streamSize]) by its view.
 */
class Camera2FrameSource(private val context: Context) : PoseFreeFrameSource {
    private val manager: CameraManager = context.getSystemService(CameraManager::class.java)

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var listener: PoseFreeFrameSource.Listener? = null
    private var cameraId: String? = null
    private var fps: Pair<Int, Int>? = null
    private var preview: PreviewTarget? = null

    @Volatile private var device: CameraDevice? = null

    @Volatile private var session: CameraCaptureSession? = null

    @Volatile private var reader: ImageReader? = null

    @Volatile private var lease: ReaderLease? = null

    /** True while this source closes the camera on purpose, so the callbacks do not report it as lost. */
    @Volatile private var closing = false

    /** SENSOR_TIMESTAMP -> SENSOR_EXPOSURE_TIME of the latest results, oldest first. */
    private val results = object : LinkedHashMap<Long, Long?>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Long?>?) = size > RESULTS_KEPT
    }

    /** The YUV stream's size, null before [open]. */
    var streamSize: ImageSize? = null
        private set

    override suspend fun open(listener: PoseFreeFrameSource.Listener): SourceOpen {
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return SourceOpen.Refused("camera permission denied")
        }
        this.listener = listener
        val chosen = try {
            pickBackCamera()
        } catch (e: CameraAccessException) {
            return SourceOpen.Refused("camera unavailable: ${e.message}")
        } ?: return SourceOpen.Refused("no back camera with a 720p or larger YUV stream")
        val (id, chars, size) = chosen
        cameraId = id
        streamSize = size
        fps = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.map { it.lower to it.upper }
            ?.let { chooseFpsRange(it) }
        val calibration = Camera2Calibration.read(CharacteristicsView.of(chars), id, size)
        startThread()
        openDevice()?.let { return SourceOpen.Refused(it) }
        startSession()?.let { return SourceOpen.Refused(it) }
        return SourceOpen.Opened(calibration)
    }

    override suspend fun setPreview(target: PreviewTarget?) {
        preview = target
        if (device == null) return
        startSession()?.let { reason -> listener?.onError(reason) }
    }

    override fun suspend() = closeDevice()

    override suspend fun resume() {
        if (cameraId == null || device != null) return
        val failed = openDevice() ?: startSession()
        if (failed != null) listener?.onError(failed)
    }

    override fun close() {
        closeDevice()
        listener = null
        thread?.quitSafely()
        thread = null
        handler = null
    }

    private fun startThread() {
        if (thread != null) return
        val t = HandlerThread("splat-camera").also { it.start() }
        thread = t
        handler = Handler(t.looper)
    }

    private fun pickBackCamera(): Triple<String, CameraCharacteristics, ImageSize>? {
        for (id in manager.cameraIdList) {
            val chars = manager.getCameraCharacteristics(id)
            if (chars.get(CameraCharacteristics.LENS_FACING) != CameraMetadata.LENS_FACING_BACK) continue
            val sizes = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.YUV_420_888)
                ?.map { ImageSize(it.width, it.height) }
                .orEmpty()
            val size = chooseStreamSize(sizes) ?: continue
            return Triple(id, chars, size)
        }
        return null
    }

    /** Opens [cameraId] and its reader; null when open, else why not. */
    @SuppressLint("MissingPermission") // checked in open()
    private suspend fun openDevice(): String? {
        val id = cameraId ?: return "camera not chosen"
        val size = streamSize ?: return "camera not chosen"
        val h = handler ?: return "camera closed"
        closing = false
        val r = ImageReader.newInstance(size.w, size.h, ImageFormat.YUV_420_888, MAX_IMAGES)
        val l = ReaderLease(r)
        r.setOnImageAvailableListener({ onImage(it, l) }, h)
        reader = r
        lease = l
        val failure = suspendCancellableCoroutine<String?> { cont ->
            val callback = object : CameraDevice.StateCallback() {
                private var first = true

                private fun settle(reason: String?) {
                    if (first) {
                        first = false
                        cont.resume(reason)
                    } else if (reason != null && !closing) {
                        listener?.onError(reason)
                    }
                }

                override fun onOpened(camera: CameraDevice) {
                    device = camera
                    settle(null)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    if (device === camera) device = null
                    settle("camera disconnected")
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    if (device === camera) device = null
                    settle("camera error $error")
                }
            }
            try {
                manager.openCamera(id, callback, h)
            } catch (e: CameraAccessException) {
                cont.resume("camera unavailable: ${e.message}")
            } catch (e: SecurityException) {
                cont.resume("camera permission denied")
            }
        }
        if (failure != null) closeDevice()
        return failure
    }

    /** (Re)builds the session on the reader and the preview; null when frames flow, else why not. */
    @Suppress("DEPRECATION") // createCaptureSession(List<Surface>, ...) is the API 26 way
    private suspend fun startSession(): String? {
        val camera = device ?: return "camera closed"
        val r = reader ?: return "camera closed"
        val h = handler ?: return "camera closed"
        session?.close()
        session = null
        val surfaces = listOfNotNull<Surface>(r.surface, preview?.surface)
        val configured = suspendCancellableCoroutine<CameraCaptureSession?> { cont ->
            try {
                camera.createCaptureSession(
                    surfaces,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) = cont.resume(s)
                        override fun onConfigureFailed(s: CameraCaptureSession) = cont.resume(null)
                    },
                    h,
                )
            } catch (e: CameraAccessException) {
                cont.resume(null)
            } catch (e: IllegalArgumentException) {
                cont.resume(null) // a preview surface the camera cannot use
            } catch (e: IllegalStateException) {
                cont.resume(null) // the device closed meanwhile
            }
        } ?: return "camera session refused"
        session = configured
        return try {
            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                surfaces.forEach { addTarget(it) }
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                fps?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(it.first, it.second)) }
            }.build()
            configured.setRepeatingRequest(request, captureCallback, h)
            null
        } catch (e: CameraAccessException) {
            "camera unavailable: ${e.message}"
        } catch (e: IllegalStateException) {
            "camera closed"
        }
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            val t = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
            val exposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
            synchronized(results) { results[t] = exposure }
        }
    }

    /** Camera thread: the latest image only (older ones are closed by the reader), handed on at once. */
    private fun onImage(r: ImageReader, l: ReaderLease) {
        val image = try {
            r.acquireLatestImage()
        } catch (e: IllegalStateException) {
            Timber.w(e, "Camera2FrameSource: no image buffer free")
            null
        } ?: return
        val t = image.timestamp
        val exposure = synchronized(results) { results[t] }
        deliver(MediaCameraImage(image), t, exposure, l, listener) { Nv21.of(image) }
    }

    private fun closeDevice() {
        closing = true
        try {
            session?.close()
        } catch (e: IllegalStateException) {
            Timber.w(e, "Camera2FrameSource: session already closed")
        }
        session = null
        device?.close()
        device = null
        reader?.setOnImageAvailableListener(null, null)
        reader = null
        lease?.retire() // the reader closes once the recorder has closed every frame it still holds (#340)
        lease = null
        synchronized(results) { results.clear() }
    }

    companion object {
        /** Images the reader holds: one acquired, one waiting for the worker, one on the worker, one spare. */
        const val MAX_IMAGES = 4

        /**
         * Hands [image] to [listener] as a frame counted in [lease], or closes it at once when the lease is retired
         * (a frame arriving after suspend or close) or nobody listens. Each image is closed exactly once: here, or
         * by the frame's receiver.
         */
        internal fun deliver(
            image: CameraImage,
            timestampNs: Long,
            exposureNs: Long?,
            lease: ReaderLease,
            listener: PoseFreeFrameSource.Listener?,
            copyNv21: (() -> KeptPixels.Nv21)? = null,
        ) {
            if (listener == null || !lease.acquire()) {
                image.close()
                return
            }
            listener.onFrame(SourceFrame(timestampNs, exposureNs, LeasedImage(image, lease), copyNv21))
        }

        private const val RESULTS_KEPT = 16
        private const val TARGET_FPS = 30
        private val FULL_HD = ImageSize(1920, 1080)
        private val HD = ImageSize(1280, 720)

        /** 1920x1080 when offered, else 1280x720, else null (portrait sizes count the same). */
        fun chooseStreamSize(offered: List<ImageSize>): ImageSize? {
            fun find(want: ImageSize) = offered.firstOrNull { it == want } ?: offered.firstOrNull {
                it.w == want.h && it.h == want.w
            }
            return find(FULL_HD) ?: find(HD)
        }

        /** The AE range holding 30 fps steadiest: (30, 30) if offered, else the narrowest one ending at 30. */
        fun chooseFpsRange(offered: List<Pair<Int, Int>>): Pair<Int, Int>? =
            offered.filter { it.second == TARGET_FPS }.maxByOrNull { it.first }
                ?: offered.filter { TARGET_FPS in it.first..it.second }.maxByOrNull { it.first }
    }
}
