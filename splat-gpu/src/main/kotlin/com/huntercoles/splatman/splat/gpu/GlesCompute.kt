package com.huntercoles.splatman.splat.gpu

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES20
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** A GL or EGL failure. [infoLog] carries the driver's shader/program log, or the GL/EGL error code. */
class GpuException(
    message: String,
    val infoLog: String = "",
) : RuntimeException(if (infoLog.isBlank()) message else "$message:\n$infoLog")

/**
 * An offscreen EGL ES 3 context (1x1 pbuffer) owned by one dedicated thread (ADR 0010). Every GL call runs on that
 * thread through [run]; [Ssbo] and [ComputeProgram] check it. [close] frees every buffer and program made here, then
 * the context and the thread. GL never runs on the caller's thread: a caller only waits on [run].
 */
class GlesCompute private constructor() : AutoCloseable {
    @Volatile
    private var glThread: Thread? = null
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, THREAD_NAME).apply { isDaemon = true }.also { glThread = it }
    }
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
    private val resources = LinkedHashSet<GlResource>()

    @Volatile
    private var closed = false

    /** Runs [block] on the GL thread and returns its result; its exception is rethrown here. Re-entrant. */
    fun <T> run(block: GlesCompute.() -> T): T {
        check(!closed) { "GlesCompute is closed" }
        if (isGlThread()) return block()
        try {
            return executor.submit<T> { block() }.get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    /** A new SSBO of [bytes] bytes (contents undefined). GL thread only. */
    fun ssbo(bytes: Int): Ssbo = Ssbo(this, bytes)

    /** Compiles and links a compute shader; a compile or link error throws [GpuException] with the log. GL thread only. */
    fun program(source: String): ComputeProgram = ComputeProgram.compile(this, source)

    /** One integer GL state value (e.g. a limit); 0 when the driver does not know the name. GL thread only. */
    fun getInt(name: Int): Int {
        requireGlThread()
        val v = IntArray(1)
        GLES20.glGetIntegerv(name, v, 0)
        GLES20.glGetError()
        return v[0]
    }

    /** Throws [GpuException] when GL has a pending error, naming [where]. GL thread only. */
    fun checkError(where: String) {
        requireGlThread()
        val err = GLES20.glGetError()
        if (err != GLES20.GL_NO_ERROR) throw GpuException("$where failed", "GL error 0x${Integer.toHexString(err)}")
    }

    internal fun isGlThread(): Boolean = Thread.currentThread() === glThread

    internal fun requireGlThread() {
        check(!closed) { "GlesCompute is closed" }
        check(isGlThread()) { "GL call on ${Thread.currentThread().name}, not the GL thread; use GlesCompute.run" }
    }

    internal fun track(r: GlResource) {
        resources += r
    }

    internal fun untrack(r: GlResource) {
        resources -= r
    }

    /** Frees every SSBO and program, the EGL context and the GL thread. Idempotent. */
    override fun close() {
        if (closed) return
        if (isGlThread()) {
            try {
                teardown()
            } finally {
                closed = true
                executor.shutdown()
            }
            return
        }
        try {
            executor.submit { teardown() }.get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        } finally {
            closed = true
            executor.shutdown()
        }
    }

    private fun teardown() {
        resources.toList().forEach { it.release() }
        resources.clear()
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(display)
        }
        surface = EGL14.EGL_NO_SURFACE
        context = EGL14.EGL_NO_CONTEXT
        display = EGL14.EGL_NO_DISPLAY
    }

    private fun initEgl() {
        val d = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (d == null || d == EGL14.EGL_NO_DISPLAY) throw eglFailure("eglGetDisplay")
        val version = IntArray(2)
        if (!EGL14.eglInitialize(d, version, 0, version, 1)) throw eglFailure("eglInitialize")
        display = d
        val attribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        if (!EGL14.eglChooseConfig(d, attribs, 0, configs, 0, 1, count, 0) || count[0] == 0) {
            throw eglFailure("eglChooseConfig (ES3, pbuffer)")
        }
        val config = configs[0]
        val ctx = EGL14.eglCreateContext(
            d, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0,
        )
        if (ctx == null || ctx == EGL14.EGL_NO_CONTEXT) throw eglFailure("eglCreateContext (ES3)")
        context = ctx
        val surf = EGL14.eglCreatePbufferSurface(
            d, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0,
        )
        if (surf == null || surf == EGL14.EGL_NO_SURFACE) throw eglFailure("eglCreatePbufferSurface")
        surface = surf
        if (!EGL14.eglMakeCurrent(d, surf, surf, ctx)) throw eglFailure("eglMakeCurrent")
    }

    private fun eglFailure(call: String) =
        GpuException("$call failed", "EGL error 0x${Integer.toHexString(EGL14.eglGetError())}")

    companion object {
        const val THREAD_NAME = "splat-gpu-gl"

        /** Starts the GL thread and makes an ES 3 context current on it; throws (thread stopped) when EGL fails. */
        fun create(): GlesCompute {
            val gl = GlesCompute()
            try {
                gl.executor.submit {
                    try {
                        gl.initEgl()
                    } catch (e: Throwable) {
                        gl.teardown()
                        throw e
                    }
                }.get()
            } catch (e: ExecutionException) {
                gl.closed = true
                gl.executor.shutdown()
                throw e.cause ?: e
            }
            return gl
        }
    }
}

/** Something on the GL thread that [GlesCompute.close] must free. */
internal interface GlResource {
    fun release()
}
