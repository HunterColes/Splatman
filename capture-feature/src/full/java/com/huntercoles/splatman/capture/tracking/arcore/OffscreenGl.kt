package com.huntercoles.splatman.capture.tracking.arcore

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.view.Surface

/**
 * A 1x1 pbuffer GL ES 2 context, current on the thread that built it, with one external OES texture for ARCore's
 * camera feed. `Session.update()` needs a current GL context and a camera texture even when nothing is drawn; the
 * capture preview draws the same texture into a window surface ([attachWindow]) from the same context and thread.
 * Build, use and [release] on one thread.
 */
internal class OffscreenGl {
    private val display: EGLDisplay
    private val context: EGLContext
    private val surface: EGLSurface
    private val config: EGLConfig
    private val windowCapable: Boolean
    private var window: EGLSurface = EGL14.EGL_NO_SURFACE

    /** The external OES texture name for `Session.setCameraTextureName`. */
    val cameraTexture: Int

    init {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "no EGL display" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }
        val windowConfig = chooseConfig(display, EGL14.EGL_PBUFFER_BIT or EGL14.EGL_WINDOW_BIT)
        windowCapable = windowConfig != null
        config = windowConfig
            ?: checkNotNull(chooseConfig(display, EGL14.EGL_PBUFFER_BIT)) { "no EGL config for a pbuffer" }
        context = EGL14.eglCreateContext(
            display,
            config,
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
            0,
        )
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
        surface = EGL14.eglCreatePbufferSurface(
            display,
            config,
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE),
            0,
        )
        check(surface != EGL14.EGL_NO_SURFACE) { "eglCreatePbufferSurface failed" }
        check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "eglMakeCurrent failed" }
        val names = IntArray(1)
        GLES20.glGenTextures(1, names, 0)
        cameraTexture = names[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexture)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
    }

    /**
     * Makes a window surface on [target] current (replacing any earlier one); false when this context cannot draw to
     * a window, and then the pbuffer stays current.
     */
    fun attachWindow(target: Surface): Boolean {
        detachWindow()
        if (!windowCapable) return false
        val created = EGL14.eglCreateWindowSurface(display, config, target, intArrayOf(EGL14.EGL_NONE), 0)
        if (created == EGL14.EGL_NO_SURFACE) return false
        if (!EGL14.eglMakeCurrent(display, created, created, context)) {
            EGL14.eglDestroySurface(display, created)
            EGL14.eglMakeCurrent(display, surface, surface, context)
            return false
        }
        window = created
        return true
    }

    /** Shows what was drawn into the window surface; false when there is none or the swap failed. */
    fun swapWindow(): Boolean = window != EGL14.EGL_NO_SURFACE && EGL14.eglSwapBuffers(display, window)

    /** Makes the pbuffer current again and destroys the window surface, if any. */
    fun detachWindow() {
        if (window == EGL14.EGL_NO_SURFACE) return
        EGL14.eglMakeCurrent(display, surface, surface, context)
        EGL14.eglDestroySurface(display, window)
        window = EGL14.EGL_NO_SURFACE
    }

    /** Deletes the texture and tears the context down. */
    fun release() {
        detachWindow()
        GLES20.glDeleteTextures(1, intArrayOf(cameraTexture), 0)
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, surface)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglReleaseThread()
        EGL14.eglTerminate(display)
    }

    private fun chooseConfig(display: EGLDisplay, surfaceTypes: Int): EGLConfig? {
        val attributes = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, surfaceTypes,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        val found = EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0
        return if (found) configs[0] else null
    }
}
