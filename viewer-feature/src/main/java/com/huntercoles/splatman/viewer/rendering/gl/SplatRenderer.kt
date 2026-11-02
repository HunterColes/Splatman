package com.huntercoles.splatman.viewer.rendering.gl

import android.opengl.GLSurfaceView
import com.huntercoles.splatman.viewer.rendering.GaussianCameraController
import com.huntercoles.splatman.viewer.rendering.pack.PackedSplats
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.IntBuffer
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.roundToInt

/** A cloud ready to draw: its packed texture data, its centres (for sorting) and its framing radius. */
class RenderScene(val packed: PackedSplats, val positions: FloatArray, val radius: Float)

/** What the debug overlay shows, published about twice a second from the GL thread. */
data class RenderStats(val fps: Float, val frameMs: Float, val scale: Float, val splats: Int, val sorts: Int)

/**
 * Draws a [RenderScene] as sorted instanced Gaussian quads (ADR 0006).
 *
 * Threads: [setScene] may be called from any thread and only hands the scene over; every [gl] call happens inside
 * the three [GLSurfaceView.Renderer] callbacks, on the GL thread. Sorting runs on [sortDispatcher] through
 * [SplatSortWorker]; a frame never waits for it: it draws with the last finished order and uploads a new one into
 * the idle half of a double-buffered order VBO when it arrives.
 *
 * Context loss: [onSurfaceCreated] runs again on a new context, so it drops every old GL name, rebuilds the
 * program and buffers and marks the scene's texture and order for upload on the next frame.
 *
 * The frame is drawn into an offscreen target at [RenderScaleController.scale] of the surface and blitted up, or
 * straight to the surface at scale 1.
 */
class SplatRenderer(
    private val camera: GaussianCameraController,
    private val gl: Gl = AndroidGl,
    sortDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val clockNs: () -> Long = System::nanoTime,
    private val onStats: (RenderStats) -> Unit = {},
) : GLSurfaceView.Renderer {

    private class SceneUpdate(val scene: RenderScene?)

    private val sortScope = CoroutineScope(SupervisorJob() + sortDispatcher)
    private val pending = AtomicReference<SceneUpdate?>(null)
    private val _stats = MutableStateFlow<RenderStats?>(null)

    /**
     * The latest [RenderStats], about twice a second, null until the first interval ends. Written on the GL thread;
     * a [StateFlow] so the UI collects it on its own thread instead of a Compose state written from the GL thread.
     */
    val stats: StateFlow<RenderStats?> = _stats.asStateFlow()

    // GL-thread state from here on.
    private var scene: RenderScene? = null
    private var sorter: SplatSortWorker? = null
    private val scaleController = RenderScaleController()

    private var program = 0
    private var uSplats = -1
    private var uView = -1
    private var uProj = -1
    private var uFocal = -1
    private var uViewport = -1
    private var vao = 0
    private var quadVbo = 0
    private val orderVbos = IntArray(2)
    private var activeOrder = -1
    private var orderCount = 0
    private var orderBytes: IntBuffer? = null
    private var orderCapacity = 0

    private var texture = 0
    private var textureReady = false
    private var maxTextureSize = 0

    private var fbo = 0
    private var fboTexture = 0
    private var fboWidth = 0
    private var fboHeight = 0

    private var width = 0
    private var height = 0
    private var lastFrameNs = 0L
    private var statsStartNs = 0L
    private var statsFrames = 0
    private var statsMs = 0.0
    private val proj = FloatArray(16)
    private val view = FloatArray(16)

    /** Draw [scene] from the next frame on (null clears). Any thread; makes no GL call. */
    fun setScene(scene: RenderScene?) {
        pending.set(SceneUpdate(scene))
    }

    /** Stops the sort worker. Any thread; GL names die with the context. */
    fun dispose() {
        sortScope.cancel()
    }

    override fun onSurfaceCreated(unused: GL10?, config: EGLConfig?) {
        // A new context: names from an old one are invalid and must not be deleted.
        texture = 0
        textureReady = false
        fbo = 0
        fboTexture = 0
        fboWidth = 0
        fboHeight = 0
        activeOrder = -1
        orderCount = 0
        sorter?.invalidate()

        program = buildProgram()
        uSplats = gl.getUniformLocation(program, "u_splats")
        uView = gl.getUniformLocation(program, "u_view")
        uProj = gl.getUniformLocation(program, "u_proj")
        uFocal = gl.getUniformLocation(program, "u_focal")
        uViewport = gl.getUniformLocation(program, "u_viewport")
        maxTextureSize = gl.getInteger(GlConst.MAX_TEXTURE_SIZE)

        vao = gl.genVertexArray()
        gl.bindVertexArray(vao)
        quadVbo = gl.genBuffer()
        gl.bindBuffer(GlConst.ARRAY_BUFFER, quadVbo)
        val quad = ByteBuffer.allocateDirect(SplatShaders.QUAD.size * 4).order(ByteOrder.nativeOrder())
        quad.asFloatBuffer().put(SplatShaders.QUAD)
        gl.bufferData(GlConst.ARRAY_BUFFER, SplatShaders.QUAD.size * 4, quad, GlConst.STATIC_DRAW)
        gl.enableVertexAttribArray(SplatShaders.ATTR_CORNER)
        gl.vertexAttribPointer(SplatShaders.ATTR_CORNER, 2, GlConst.FLOAT, 0, 0)
        orderVbos[0] = gl.genBuffer()
        orderVbos[1] = gl.genBuffer()
        gl.enableVertexAttribArray(SplatShaders.ATTR_INDEX)
        gl.vertexAttribDivisor(SplatShaders.ATTR_INDEX, 1)
        gl.bindVertexArray(0)
        gl.bindBuffer(GlConst.ARRAY_BUFFER, 0)

        gl.disable(GlConst.DEPTH_TEST)
        gl.disable(GlConst.CULL_FACE)
        gl.enable(GlConst.BLEND)
        gl.blendFunc(GlConst.ONE, GlConst.ONE_MINUS_SRC_ALPHA)
    }

    override fun onSurfaceChanged(unused: GL10?, width: Int, height: Int) {
        this.width = width
        this.height = height
        if (height > 0) synchronized(camera) { camera.setAspectRatio(width.toFloat() / height) }
    }

    override fun onDrawFrame(unused: GL10?) {
        val now = clockNs()
        val frameMs = if (lastFrameNs == 0L) 0f else (now - lastFrameNs) / NS_PER_MS
        lastFrameNs = now
        val scale = if (frameMs > 0f) scaleController.onFrame(frameMs) else scaleController.scale

        pending.getAndSet(null)?.let { applyScene(it.scene) }
        val current = scene
        if (current != null && !textureReady) uploadTexture(current.packed)

        synchronized(camera) {
            camera.getViewMatrix().values.copyInto(view)
            camera.getProjectionMatrix().values.copyInto(proj)
        }
        sorter?.let { worker ->
            worker.requestSort(view)
            worker.takeOrder()?.let { order ->
                uploadOrder(order, current?.packed?.count ?: 0)
                worker.recycle(order)
            }
        }

        val offscreen = scale < RenderScaleController.MAX_SCALE && ensureTarget(scale)
        val targetW = if (offscreen) fboWidth else width
        val targetH = if (offscreen) fboHeight else height
        gl.bindFramebuffer(GlConst.FRAMEBUFFER, if (offscreen) fbo else 0)
        gl.viewport(targetW, targetH)
        gl.clearColor(BACKGROUND, BACKGROUND, BACKGROUND, 1f)
        gl.clear(GlConst.COLOR_BUFFER_BIT)
        if (current != null && texture != 0 && orderCount > 0 && targetW > 0 && targetH > 0) {
            draw(targetW, targetH)
        }
        if (offscreen) {
            gl.bindFramebuffer(GlConst.READ_FRAMEBUFFER, fbo)
            gl.bindFramebuffer(GlConst.DRAW_FRAMEBUFFER, 0)
            gl.blitFramebuffer(fboWidth, fboHeight, width, height)
            gl.bindFramebuffer(GlConst.FRAMEBUFFER, 0)
        }
        publishStats(now, frameMs, scale, current?.packed?.count ?: 0)
    }

    private fun applyScene(next: RenderScene?) {
        sorter?.close()
        sorter = null
        if (texture != 0) gl.deleteTexture(texture)
        texture = 0
        textureReady = false
        activeOrder = -1
        orderCount = 0
        scene = next
        if (next != null && next.packed.count > 0) {
            sorter = SplatSortWorker(next.positions, next.packed.count, next.radius, sortScope)
        }
    }

    private fun uploadTexture(packed: PackedSplats) {
        if (packed.count == 0) {
            textureReady = true
            return
        }
        if (packed.height > maxTextureSize && maxTextureSize > 0) {
            Timber.w("Splat texture %dx%d exceeds GL_MAX_TEXTURE_SIZE %d", packed.width, packed.height, maxTextureSize)
            textureReady = true // nothing to draw; do not retry every frame
            return
        }
        if (texture == 0) texture = gl.genTexture()
        gl.activeTexture(GlConst.TEXTURE0)
        gl.bindTexture(GlConst.TEXTURE_2D, texture)
        gl.texParameteri(GlConst.TEXTURE_2D, GlConst.TEXTURE_MIN_FILTER, GlConst.NEAREST)
        gl.texParameteri(GlConst.TEXTURE_2D, GlConst.TEXTURE_MAG_FILTER, GlConst.NEAREST)
        gl.texParameteri(GlConst.TEXTURE_2D, GlConst.TEXTURE_WRAP_S, GlConst.CLAMP_TO_EDGE)
        gl.texParameteri(GlConst.TEXTURE_2D, GlConst.TEXTURE_WRAP_T, GlConst.CLAMP_TO_EDGE)
        gl.pixelStorei(GlConst.UNPACK_ALIGNMENT, 4)
        val data = packed.data.duplicate()
        data.position(0)
        gl.texImage2D(
            GlConst.TEXTURE_2D, GlConst.RGBA32UI, packed.width, packed.height,
            GlConst.RGBA_INTEGER, GlConst.UNSIGNED_INT, data,
        )
        textureReady = true
    }

    private fun uploadOrder(order: IntArray, count: Int) {
        if (count == 0) return
        if (orderCapacity < count) {
            orderBytes = ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder()).asIntBuffer()
            orderCapacity = count
        }
        val buf = requireNotNull(orderBytes)
        buf.clear()
        buf.put(order, 0, count)
        buf.flip()
        val back = if (activeOrder == 0) 1 else 0
        gl.bindBuffer(GlConst.ARRAY_BUFFER, orderVbos[back])
        gl.bufferData(GlConst.ARRAY_BUFFER, count * 4, buf, GlConst.DYNAMIC_DRAW)
        gl.bindVertexArray(vao)
        gl.vertexAttribIPointer(SplatShaders.ATTR_INDEX, 1, GlConst.UNSIGNED_INT, 0, 0)
        gl.bindVertexArray(0)
        gl.bindBuffer(GlConst.ARRAY_BUFFER, 0)
        activeOrder = back
        orderCount = count
    }

    private fun draw(targetW: Int, targetH: Int) {
        gl.useProgram(program)
        gl.activeTexture(GlConst.TEXTURE0)
        gl.bindTexture(GlConst.TEXTURE_2D, texture)
        gl.uniform1i(uSplats, 0)
        gl.uniformMatrix4fv(uView, view)
        gl.uniformMatrix4fv(uProj, proj)
        gl.uniform2f(uFocal, proj[0] * targetW / 2f, proj[5] * targetH / 2f)
        gl.uniform2f(uViewport, targetW.toFloat(), targetH.toFloat())
        gl.bindVertexArray(vao)
        gl.drawArraysInstanced(GlConst.TRIANGLE_STRIP, 0, 4, orderCount)
        gl.bindVertexArray(0)
    }

    /** Makes the offscreen target match [scale] of the surface; false when it cannot be made (draw direct). */
    private fun ensureTarget(scale: Float): Boolean {
        val w = (width * scale).roundToInt().coerceAtLeast(1)
        val h = (height * scale).roundToInt().coerceAtLeast(1)
        if (fbo != 0 && w == fboWidth && h == fboHeight) return true
        if (fbo == 0) fbo = gl.genFramebuffer()
        if (fboTexture == 0) fboTexture = gl.genTexture()
        gl.bindTexture(GlConst.TEXTURE_2D, fboTexture)
        gl.texParameteri(GlConst.TEXTURE_2D, GlConst.TEXTURE_MIN_FILTER, GlConst.LINEAR)
        gl.texParameteri(GlConst.TEXTURE_2D, GlConst.TEXTURE_MAG_FILTER, GlConst.LINEAR)
        gl.texParameteri(GlConst.TEXTURE_2D, GlConst.TEXTURE_WRAP_S, GlConst.CLAMP_TO_EDGE)
        gl.texParameteri(GlConst.TEXTURE_2D, GlConst.TEXTURE_WRAP_T, GlConst.CLAMP_TO_EDGE)
        gl.texImage2D(GlConst.TEXTURE_2D, GlConst.RGBA8, w, h, GlConst.RGBA, GlConst.UNSIGNED_BYTE, null)
        gl.bindFramebuffer(GlConst.FRAMEBUFFER, fbo)
        gl.framebufferTexture2D(GlConst.FRAMEBUFFER, GlConst.COLOR_ATTACHMENT0, GlConst.TEXTURE_2D, fboTexture)
        val complete = gl.checkFramebufferStatus(GlConst.FRAMEBUFFER) == GlConst.FRAMEBUFFER_COMPLETE
        gl.bindFramebuffer(GlConst.FRAMEBUFFER, 0)
        if (!complete) {
            gl.deleteFramebuffer(fbo)
            gl.deleteTexture(fboTexture)
            fbo = 0
            fboTexture = 0
            fboWidth = 0
            fboHeight = 0
            return false
        }
        fboWidth = w
        fboHeight = h
        return true
    }

    private fun buildProgram(): Int {
        val vs = compile(GlConst.VERTEX_SHADER, SplatShaders.VERTEX)
        val fs = compile(GlConst.FRAGMENT_SHADER, SplatShaders.FRAGMENT)
        val p = gl.createProgram()
        gl.attachShader(p, vs)
        gl.attachShader(p, fs)
        gl.bindAttribLocation(p, SplatShaders.ATTR_CORNER, "a_corner")
        gl.bindAttribLocation(p, SplatShaders.ATTR_INDEX, "a_index")
        gl.linkProgram(p)
        gl.deleteShader(vs)
        gl.deleteShader(fs)
        if (gl.getProgramiv(p, GlConst.LINK_STATUS) == 0) {
            val log = gl.getProgramInfoLog(p)
            gl.deleteProgram(p)
            error("splat program link failed: $log")
        }
        return p
    }

    private fun compile(type: Int, source: String): Int {
        val s = gl.createShader(type)
        gl.shaderSource(s, source)
        gl.compileShader(s)
        if (gl.getShaderiv(s, GlConst.COMPILE_STATUS) == 0) {
            val log = gl.getShaderInfoLog(s)
            gl.deleteShader(s)
            error("splat shader compile failed: $log")
        }
        return s
    }

    private fun publishStats(now: Long, frameMs: Float, scale: Float, splats: Int) {
        if (statsStartNs == 0L) statsStartNs = now
        statsFrames++
        statsMs += frameMs
        val elapsed = now - statsStartNs
        if (elapsed < STATS_INTERVAL_NS) return
        val next = RenderStats(
            fps = (statsFrames * 1e9 / elapsed).toFloat(),
            frameMs = (statsMs / statsFrames).toFloat(),
            scale = scale,
            splats = splats,
            sorts = sorter?.sorts ?: 0,
        )
        _stats.value = next
        onStats(next)
        statsStartNs = now
        statsFrames = 0
        statsMs = 0.0
    }

    companion object {
        private const val NS_PER_MS = 1_000_000f
        private const val STATS_INTERVAL_NS = 500_000_000L
        private const val BACKGROUND = 0.04f
    }
}
