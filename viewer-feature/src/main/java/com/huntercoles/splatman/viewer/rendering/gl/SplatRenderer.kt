package com.huntercoles.splatman.viewer.rendering.gl

import android.opengl.GLSurfaceView
import com.huntercoles.splatman.splat.edit.CropBox
import com.huntercoles.splatman.splat.math.Vec3
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
 * Draws a [RenderScene] as sorted Gaussian quads (ADR 0006), [SplatShaders.CHUNK] splats per indexed draw with the
 * back-to-front order in a texture (see [SplatShaders]; no instancing, which the emulator's GL runs per instance).
 *
 * Threads: [setScene] may be called from any thread and only hands the scene over; every [gl] call happens inside
 * the three [GLSurfaceView.Renderer] callbacks, on the GL thread. Sorting runs on [sortDispatcher] through
 * [SplatSortWorker]; a frame never waits for it: it draws with the last finished order and uploads a new one into
 * the idle one of two order textures when it arrives.
 *
 * Context loss: [onSurfaceCreated] runs again on a new context, so it drops every old GL name, rebuilds the
 * program and buffers and marks the scene's texture and order for upload on the next frame.
 *
 * The frame is drawn into an offscreen target at [RenderScaleController.scale] of the surface and blitted up, or
 * straight to the surface at scale 1.
 *
 * Crop box ([setCropBox], ADR 0011): splats whose centre is outside the box draw at [CROP_DIM_ALPHA] of their alpha
 * (a shader uniform: no re-pack, no re-sort) and [CropBoxOverlay] draws the box on top.
 */
class SplatRenderer(
    private val camera: GaussianCameraController,
    private val gl: Gl = AndroidGl,
    sortDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val clockNs: () -> Long = System::nanoTime,
    private val onStats: (RenderStats) -> Unit = {},
) : GLSurfaceView.Renderer {

    private class SceneUpdate(val scene: RenderScene?)
    private class CropUpdate(val box: CropBox?)

    private val sortScope = CoroutineScope(SupervisorJob() + sortDispatcher)
    private val pending = AtomicReference<SceneUpdate?>(null)
    private val pendingCrop = AtomicReference<CropUpdate?>(null)
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
    private var uOrder = -1
    private var uBase = -1
    private var uView = -1
    private var uProj = -1
    private var uFocal = -1
    private var uViewport = -1
    private var uCropDim = -1
    private var uCropCentre = -1
    private var uCropAxes = -1
    private var uCropHalf = -1
    private val overlay = CropBoxOverlay(gl)
    private var cropBox: CropBox? = null
    private val cropAxes = FloatArray(9)
    private var cropModel = FloatArray(16)
    private var vao = 0
    private var indexBuffer = 0
    private val orderTextures = IntArray(2)
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

    /**
     * Dims splats outside [box] and draws it from the next frame on (null = off). Any thread; makes no GL call. A
     * box with a non-finite or non-positive half extent, or a non-finite centre, is ignored (the last good box stays).
     */
    fun setCropBox(box: CropBox?) {
        if (box != null && !isUsable(box)) {
            Timber.w("Ignoring unusable crop box %s", box)
            return
        }
        pendingCrop.set(CropUpdate(box))
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
        uOrder = gl.getUniformLocation(program, "u_order")
        uBase = gl.getUniformLocation(program, "u_base")
        uView = gl.getUniformLocation(program, "u_view")
        uProj = gl.getUniformLocation(program, "u_proj")
        uFocal = gl.getUniformLocation(program, "u_focal")
        uViewport = gl.getUniformLocation(program, "u_viewport")
        uCropDim = gl.getUniformLocation(program, "u_cropDim")
        uCropCentre = gl.getUniformLocation(program, "u_cropCentre")
        uCropAxes = gl.getUniformLocation(program, "u_cropAxes")
        uCropHalf = gl.getUniformLocation(program, "u_cropHalf")
        maxTextureSize = gl.getInteger(GlConst.MAX_TEXTURE_SIZE)

        vao = gl.genVertexArray()
        gl.bindVertexArray(vao)
        indexBuffer = gl.genBuffer()
        gl.bindBuffer(GlConst.ELEMENT_ARRAY_BUFFER, indexBuffer)
        val indices = SplatShaders.chunkIndices()
        val bytes = ByteBuffer.allocateDirect(indices.size * 2).order(ByteOrder.nativeOrder())
        bytes.asShortBuffer().put(indices)
        gl.bufferData(GlConst.ELEMENT_ARRAY_BUFFER, indices.size * 2, bytes, GlConst.STATIC_DRAW)
        gl.bindVertexArray(0)
        gl.bindBuffer(GlConst.ELEMENT_ARRAY_BUFFER, 0)
        for (k in orderTextures.indices) {
            orderTextures[k] = gl.genTexture()
            gl.bindTexture(GlConst.TEXTURE_2D, orderTextures[k])
            gl.texParameteri(GlConst.TEXTURE_2D, GlConst.TEXTURE_MIN_FILTER, GlConst.NEAREST)
            gl.texParameteri(GlConst.TEXTURE_2D, GlConst.TEXTURE_MAG_FILTER, GlConst.NEAREST)
            gl.texParameteri(GlConst.TEXTURE_2D, GlConst.TEXTURE_WRAP_S, GlConst.CLAMP_TO_EDGE)
            gl.texParameteri(GlConst.TEXTURE_2D, GlConst.TEXTURE_WRAP_T, GlConst.CLAMP_TO_EDGE)
        }
        gl.bindTexture(GlConst.TEXTURE_2D, 0)
        overlay.create()

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
        pendingCrop.getAndSet(null)?.let { applyCrop(it.box) }
        val current = scene
        if (current != null && !textureReady) uploadTexture(current.packed)

        synchronized(camera) {
            camera.advance(frameMs / MS_PER_S)
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
        if (cropBox != null && targetW > 0 && targetH > 0) overlay.draw(cropModel, view, proj)
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

    private fun applyCrop(box: CropBox?) {
        cropBox = box
        if (box == null) return
        val r = box.rotation.normalized()
        val axes = arrayOf(r.rotate(Vec3.UNIT_X), r.rotate(Vec3.UNIT_Y), r.rotate(Vec3.UNIT_Z))
        for (k in 0 until 3) {
            cropAxes[3 * k] = axes[k].x
            cropAxes[3 * k + 1] = axes[k].y
            cropAxes[3 * k + 2] = axes[k].z
        }
        cropModel = CropBoxOverlay.modelOf(box)
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

    /** Writes [order] into the idle order texture, whole rows of [SplatShaders.ORDER_WIDTH] (the tail is unread). */
    private fun uploadOrder(order: IntArray, count: Int) {
        if (count == 0) return
        val rows = (count + SplatShaders.ORDER_WIDTH - 1) / SplatShaders.ORDER_WIDTH
        val texels = rows * SplatShaders.ORDER_WIDTH
        if (orderCapacity < texels) {
            orderBytes = ByteBuffer.allocateDirect(texels * 4).order(ByteOrder.nativeOrder()).asIntBuffer()
            orderCapacity = texels
        }
        val buf = requireNotNull(orderBytes)
        buf.clear()
        buf.put(order, 0, count)
        buf.position(0)
        buf.limit(texels)
        val back = if (activeOrder == 0) 1 else 0
        gl.activeTexture(GlConst.TEXTURE1)
        gl.bindTexture(GlConst.TEXTURE_2D, orderTextures[back])
        gl.pixelStorei(GlConst.UNPACK_ALIGNMENT, 4)
        gl.texImage2D(
            GlConst.TEXTURE_2D, GlConst.R32UI, SplatShaders.ORDER_WIDTH, rows,
            GlConst.RED_INTEGER, GlConst.UNSIGNED_INT, buf,
        )
        gl.activeTexture(GlConst.TEXTURE0)
        activeOrder = back
        orderCount = count
    }

    private fun draw(targetW: Int, targetH: Int) {
        gl.useProgram(program)
        gl.activeTexture(GlConst.TEXTURE0)
        gl.bindTexture(GlConst.TEXTURE_2D, texture)
        gl.uniform1i(uSplats, 0)
        gl.activeTexture(GlConst.TEXTURE1)
        gl.bindTexture(GlConst.TEXTURE_2D, orderTextures[activeOrder])
        gl.uniform1i(uOrder, 1)
        gl.activeTexture(GlConst.TEXTURE0)
        gl.uniformMatrix4fv(uView, view)
        gl.uniformMatrix4fv(uProj, proj)
        gl.uniform2f(uFocal, proj[0] * targetW / 2f, proj[5] * targetH / 2f)
        gl.uniform2f(uViewport, targetW.toFloat(), targetH.toFloat())
        val box = cropBox
        if (box == null) {
            gl.uniform1f(uCropDim, 1f)
        } else {
            gl.uniform1f(uCropDim, CROP_DIM_ALPHA)
            gl.uniform3f(uCropCentre, box.centre.x, box.centre.y, box.centre.z)
            gl.uniformMatrix3fv(uCropAxes, cropAxes)
            gl.uniform3f(uCropHalf, box.halfExtents.x, box.halfExtents.y, box.halfExtents.z)
        }
        gl.bindVertexArray(vao)
        var base = 0
        while (base < orderCount) {
            val splats = minOf(SplatShaders.CHUNK, orderCount - base)
            gl.uniform1i(uBase, base)
            gl.drawElements(GlConst.TRIANGLES, splats * SplatShaders.INDICES_PER_SPLAT, GlConst.UNSIGNED_SHORT, 0)
            base += splats
        }
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
        /** Alpha factor of splats outside the crop box (docs/design/screens/crop.md, `CROP_DIM_ALPHA`). */
        const val CROP_DIM_ALPHA = 0.2f

        /** True when [box] can be drawn and tested: finite centre, finite half extents above zero. */
        fun isUsable(box: CropBox): Boolean {
            val c = box.centre
            val h = box.halfExtents
            return c.x.isFinite() && c.y.isFinite() && c.z.isFinite() &&
                h.x.isFinite() && h.y.isFinite() && h.z.isFinite() &&
                h.x > 0f && h.y > 0f && h.z > 0f
        }

        private const val NS_PER_MS = 1_000_000f
        private const val MS_PER_S = 1000f
        private const val STATS_INTERVAL_NS = 500_000_000L
        private const val BACKGROUND = 0.04f
    }
}
