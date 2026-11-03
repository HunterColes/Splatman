package com.huntercoles.splatman.viewer.export.video

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.view.Surface
import com.huntercoles.splatman.viewer.rendering.GaussianCameraController
import com.huntercoles.splatman.viewer.rendering.ViewerConstants
import com.huntercoles.splatman.viewer.rendering.gl.RenderScene
import com.huntercoles.splatman.viewer.rendering.gl.SplatRenderer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.IOException
import kotlin.math.max

/** How a turntable encode ended. [Failed.encoder] tells the encoder's failure (exp_video_error) from a write one. */
sealed interface TurntableResult {
    data class Done(val file: File, val frames: Int) : TurntableResult
    data class Failed(val encoder: Boolean, val message: String) : TurntableResult
}

/** An H.264 encoder on this phone and the square it takes; [find] is null when there is none (the Video row hides). */
class VideoEncoderChoice(val codecName: String, val spec: VideoSpec) {
    companion object {
        fun find(): VideoEncoderChoice? = try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .asSequence()
                .filter { it.isEncoder && it.supportedTypes.any { t -> t.equals(VideoSpec.MIME, ignoreCase = true) } }
                .mapNotNull { info -> choose(info) }
                .firstOrNull()
        } catch (e: IllegalArgumentException) {
            Timber.w(e, "No H.264 encoder list")
            null
        } catch (e: IllegalStateException) {
            Timber.w(e, "No H.264 encoder list")
            null
        }

        private fun choose(info: MediaCodecInfo): VideoEncoderChoice? {
            val caps = info.getCapabilitiesForType(VideoSpec.MIME)
            if (MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface !in caps.colorFormats) return null
            val video = caps.videoCapabilities ?: return null
            val spec = VideoSpec.choose(
                supports = { s -> video.isSizeSupported(s, s) },
                clampBitrate = { b -> b.coerceIn(video.bitrateRange.lower, video.bitrateRange.upper) },
            ) ?: return null
            return VideoEncoderChoice(info.name, spec)
        }
    }
}

/**
 * Renders a [RenderScene] along an [OrbitPath] into an MP4 (#262): the Viewer's own [SplatRenderer] draws each frame
 * into an EGL window surface made from a MediaCodec H.264 encoder's input surface, MediaMuxer writes the track.
 * Frame `k` is stamped `k / FPS` seconds; the background is the Viewer's.
 *
 * Sorting: the renderer's sort worker runs on [Dispatchers.Unconfined], so the back-to-front order for a frame's view
 * is computed inside that frame's draw (the Viewer's async sort would draw each frame with the previous view's order).
 *
 * [encode] blocks one thread of [dispatcher] for the whole encode (the EGL context is bound to it), reports
 * frames done / frames after each frame, and checks for cancellation between frames: a cancelled or failed encode
 * deletes [encode]'s output file before it returns or throws. A crop is the caller's: pass the cropped scene.
 */
class TurntableEncoder(
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    suspend fun encode(
        scene: RenderScene,
        path: OrbitPath,
        choice: VideoEncoderChoice,
        output: File,
        onProgress: (Float) -> Unit = {},
    ): TurntableResult = withContext(dispatcher) {
        val job = coroutineContext[Job]
        val session = Session(choice, output)
        var done = false
        try {
            session.open()
            val camera = GaussianCameraController()
            val renderer = SplatRenderer(camera, sortDispatcher = Dispatchers.Unconfined, clockNs = { 0L })
            try {
                renderer.onSurfaceCreated(null, null)
                renderer.onSurfaceChanged(null, choice.spec.size, choice.spec.size)
                renderer.setScene(scene)
                synchronized(camera) {
                    camera.setFarPlane(max(ViewerConstants.FAR_PLANE, path.distance * FAR_FACTOR))
                }
                for (k in 0 until path.frames) {
                    if (job?.isActive == false) throw CancellationException("Turntable encode cancelled")
                    synchronized(camera) { camera.setPose(path.rotation(k), path.target, path.distance) }
                    renderer.onDrawFrame(null)
                    session.submitFrame(k * NS_PER_S / OrbitPath.FPS)
                    onProgress((k + 1).toFloat() / path.frames)
                }
            } finally {
                renderer.dispose()
            }
            session.finish()
            done = true
            TurntableResult.Done(output, path.frames)
        } catch (e: WriteException) {
            Timber.w(e, "Turntable write failed")
            TurntableResult.Failed(encoder = false, message = e.message ?: "write failed")
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Timber.w(e, "Turntable write failed")
            TurntableResult.Failed(encoder = false, message = e.message ?: "write failed")
        } catch (e: OutOfMemoryError) {
            Timber.w(e, "Turntable out of memory")
            TurntableResult.Failed(encoder = true, message = "out of memory")
        } catch (e: IllegalStateException) {
            // MediaCodec.CodecException, a codec in the wrong state, EGL failures: the encoder's.
            encoderFailed(e)
        } catch (e: IllegalArgumentException) {
            // configure() refusing the format.
            encoderFailed(e)
        } finally {
            session.release()
            if (!done) output.delete()
        }
    }

    private fun encoderFailed(e: RuntimeException): TurntableResult {
        Timber.w(e, "Turntable encode failed")
        return TurntableResult.Failed(encoder = true, message = e.message ?: e.javaClass.simpleName)
    }

    /** A muxer step failed: the file or its storage, not the encoder. */
    private class WriteException(message: String, cause: Throwable) : IOException(message, cause)

    /** The encoder, its EGL surface and the muxer of one encode; [release] is safe after any failure. */
    private class Session(private val choice: VideoEncoderChoice, private val output: File) {
        private var codec: MediaCodec? = null
        private var inputSurface: Surface? = null
        private var muxer: MediaMuxer? = null
        private var track = -1
        private var muxing = false
        private var samples = 0
        private val info = MediaCodec.BufferInfo()

        private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var context: EGLContext = EGL14.EGL_NO_CONTEXT
        private var surface: EGLSurface = EGL14.EGL_NO_SURFACE

        fun open() {
            val size = choice.spec.size
            val format = MediaFormat.createVideoFormat(VideoSpec.MIME, size, size).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, choice.spec.bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, OrbitPath.FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            val c = try {
                MediaCodec.createByCodecName(choice.codecName)
            } catch (e: IOException) {
                throw IllegalStateException("cannot create ${choice.codecName}", e)
            }
            codec = c
            c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = c.createInputSurface()
            c.start()
            muxer = write("open") { MediaMuxer(output.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4) }
            makeEgl(requireNotNull(inputSurface))
        }

        fun submitFrame(ptsNs: Long) {
            EGLExt.eglPresentationTimeANDROID(display, surface, ptsNs)
            if (!EGL14.eglSwapBuffers(display, surface)) eglFail("eglSwapBuffers")
            drain(endOfStream = false)
        }

        fun finish() {
            requireNotNull(codec).signalEndOfInputStream()
            drain(endOfStream = true)
            check(samples > 0) { "the encoder produced no frames" }
            write("stop") { requireNotNull(muxer).stop() }
            muxing = false
        }

        private fun drain(endOfStream: Boolean) {
            val c = requireNotNull(codec)
            var idle = 0
            while (true) {
                val index = c.dequeueOutputBuffer(info, if (endOfStream) DEQUEUE_TIMEOUT_US else 0L)
                when {
                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (!endOfStream) return
                        if (++idle > MAX_IDLE_DEQUEUES) error("the encoder did not finish")
                    }
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        check(!muxing) { "the encoder changed format twice" }
                        val m = requireNotNull(muxer)
                        track = write("addTrack") { m.addTrack(c.outputFormat) }
                        write("start") { m.start() }
                        muxing = true
                    }
                    index >= 0 -> {
                        val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (!config && info.size > 0) {
                            check(muxing) { "the encoder sent data before its format" }
                            val buf = requireNotNull(c.getOutputBuffer(index))
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            write("writeSampleData") { requireNotNull(muxer).writeSampleData(track, buf, info) }
                            samples++
                        }
                        c.releaseOutputBuffer(index, false)
                        if (eos) return
                    }
                }
            }
        }

        private fun makeEgl(window: Surface) {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) eglFail("eglGetDisplay")
            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) eglFail("eglInitialize")
            val attribs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
                EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val found = IntArray(1)
            if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, found, 0) || found[0] < 1) {
                eglFail("eglChooseConfig")
            }
            val config = requireNotNull(configs[0])
            context = EGL14.eglCreateContext(
                display, config, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0,
            )
            if (context == EGL14.EGL_NO_CONTEXT) eglFail("eglCreateContext")
            surface = EGL14.eglCreateWindowSurface(display, config, window, intArrayOf(EGL14.EGL_NONE), 0)
            if (surface == EGL14.EGL_NO_SURFACE) eglFail("eglCreateWindowSurface")
            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) eglFail("eglMakeCurrent")
        }

        fun release() {
            quietly("codec stop") { codec?.stop() }
            quietly("codec release") { codec?.release() }
            codec = null
            if (display != EGL14.EGL_NO_DISPLAY) {
                quietly("egl") {
                    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                    if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                    EGL14.eglReleaseThread()
                    EGL14.eglTerminate(display)
                }
            }
            display = EGL14.EGL_NO_DISPLAY
            context = EGL14.EGL_NO_CONTEXT
            surface = EGL14.EGL_NO_SURFACE
            quietly("surface") { inputSurface?.release() }
            inputSurface = null
            if (muxing) quietly("muxer stop") { muxer?.stop() }
            muxing = false
            quietly("muxer release") { muxer?.release() }
            muxer = null
        }

        private fun eglFail(what: String): Nothing =
            error("$what failed: 0x${Integer.toHexString(EGL14.eglGetError())}")

        private inline fun <T> write(what: String, block: () -> T): T = try {
            block()
        } catch (e: IOException) {
            throw WriteException("muxer $what: ${e.message}", e)
        } catch (e: IllegalStateException) {
            throw WriteException("muxer $what: ${e.message}", e)
        }

        private inline fun quietly(what: String, block: () -> Unit) {
            try {
                block()
            } catch (e: IllegalStateException) {
                Timber.w(e, "Turntable release (%s)", what)
            }
        }
    }

    companion object {
        private const val NS_PER_S = 1_000_000_000L
        private const val FAR_FACTOR = 4f
        private const val DEQUEUE_TIMEOUT_US = 10_000L
        private const val MAX_IDLE_DEQUEUES = 500
        private const val EGL_RECORDABLE_ANDROID = 0x3142
    }
}
