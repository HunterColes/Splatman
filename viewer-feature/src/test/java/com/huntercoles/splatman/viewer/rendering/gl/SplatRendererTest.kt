package com.huntercoles.splatman.viewer.rendering.gl

import com.huntercoles.splatman.splat.edit.BoxFace
import com.huntercoles.splatman.splat.edit.CropBox
import com.huntercoles.splatman.splat.edit.EraseMask
import com.huntercoles.splatman.splat.math.Quat
import com.huntercoles.splatman.splat.math.Vec3
import com.huntercoles.splatman.splat.model.SplatCloud
import com.huntercoles.splatman.viewer.rendering.GaussianCameraController
import com.huntercoles.splatman.viewer.rendering.ViewerConstants
import com.huntercoles.splatman.viewer.rendering.pack.SplatPacker
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SplatRendererTest {

    private val scheduler = TestCoroutineScheduler()
    private val sortDispatcher = StandardTestDispatcher(scheduler)
    private val gl = FakeGl()
    private val camera = GaussianCameraController()
    private var nowNs = 1_000_000_000L
    private val stats = mutableListOf<RenderStats>()
    private val renderer = SplatRenderer(camera, gl, sortDispatcher, clockNs = { nowNs }, onStats = { stats += it })

    @AfterEach
    fun tearDown() = renderer.dispose()

    /** [n] splats on the z axis in shuffled order, so the back-to-front order is not the index order. */
    private fun scene(n: Int = 8): RenderScene {
        val zs = (0 until n).map { -2f + 4f * ((it * 5) % n) / n }
        val cloud = SplatCloud(
            count = n,
            shDegree = 0,
            positions = FloatArray(3 * n) { if (it % 3 == 2) zs[it / 3] else 0f },
            logScales = FloatArray(3 * n) { -3f },
            rotations = FloatArray(4 * n) { if (it % 4 == 0) 1f else 0f },
            opacityLogits = FloatArray(n) { 2f },
            shDc = FloatArray(3 * n),
            shRest = FloatArray(0),
        )
        return RenderScene(SplatPacker().pack(cloud), cloud.positions, radius = 2f)
    }

    private fun start(width: Int = 1080, height: Int = 1920) {
        renderer.onSurfaceCreated(null, null)
        renderer.onSurfaceChanged(null, width, height)
    }

    private fun frame(stepMs: Long = 16) {
        nowNs += stepMs * 1_000_000
        renderer.onDrawFrame(null)
    }

    private fun draws() = gl.named("drawElements")
    private fun splatUploads() = gl.named("texImage2D").filter { it.args[0] == GlConst.RGBA32UI }

    /** The order in [upload]: its first `count` texels (the rest of the last row is padding). */
    private fun orderOf(scene: RenderScene, upload: Pair<Int, IntArray>) = upload.second.copyOf(scene.packed.count)

    private fun assertBackToFront(scene: RenderScene, upload: Pair<Int, IntArray>) {
        val order = orderOf(scene, upload)
        val view = camera.getViewMatrix().values
        val depths = FloatArray(scene.packed.count)
        ResortPolicy.depths(view, scene.positions, scene.packed.count, depths)
        assertEquals((0 until scene.packed.count).toSet(), order.toSet())
        for (k in 1 until order.size) assertTrue(depths[order[k - 1]] >= depths[order[k]], "order $k")
    }

    @Test
    fun `frames never wait for the sort and draw once its order arrives`() {
        val s = scene()
        start()
        renderer.setScene(s)
        repeat(3) { frame() }
        assertEquals(1, splatUploads().size)
        assertEquals(listOf(2048, 1, 2048 * 16), splatUploads().single().args.subList(1, 4))
        assertTrue(draws().isEmpty(), "no order yet, nothing drawn")

        scheduler.advanceUntilIdle()
        frame()
        assertEquals(1, gl.orderUploads.size)
        assertBackToFront(s, gl.orderUploads.single())
        val orderTexture = gl.orderUploads.single().first
        assertEquals(listOf(GlConst.TRIANGLES, 8 * 6, GlConst.UNSIGNED_SHORT, 0, orderTexture), draws().single().args)
    }

    @Test
    fun `a new order goes into the idle half of the order buffer`() {
        val s = scene()
        start()
        renderer.setScene(s)
        frame()
        scheduler.advanceUntilIdle()
        frame()
        synchronized(camera) { camera.onRotate(10f, 0f) }
        frame()
        scheduler.advanceUntilIdle()
        frame()

        assertEquals(2, gl.orderUploads.size)
        val (first, second) = gl.orderUploads.map { it.first }
        assertNotEquals(first, second)
        assertEquals(second, draws().last().args[4])
        assertBackToFront(s, gl.orderUploads.last())
        // The frame after the turn drew with the old order instead of waiting for the new one.
        assertEquals(3, draws().size)
    }

    @Test
    fun `an unchanged view is not sorted again`() {
        start()
        renderer.setScene(scene())
        repeat(5) {
            frame()
            scheduler.advanceUntilIdle()
        }
        assertEquals(1, gl.orderUploads.size)
        assertEquals(4, draws().size)
    }

    @Test
    fun `a lost context gets a new program, texture and order on the next frames`() {
        val s = scene()
        start()
        renderer.setScene(s)
        frame()
        scheduler.advanceUntilIdle()
        frame()
        assertEquals(1, draws().size)
        val callsBefore = gl.calls.size

        // GLSurfaceView calls onSurfaceCreated again after the EGL context was lost.
        start()
        frame()
        assertEquals(4, gl.count("createProgram"), "splat and crop box programs, twice")
        assertEquals(2, splatUploads().size)
        assertTrue(gl.calls.drop(callsBefore).none { it.name == "deleteTexture" }, "old names are not deleted")
        assertEquals(1, draws().size, "no draw before the order is uploaded again")

        scheduler.advanceUntilIdle()
        frame()
        assertEquals(2, gl.orderUploads.size, "the same view is sorted again after the loss")
        assertEquals(2, draws().size)
    }

    @Test
    fun `setScene makes no GL call and every GL call stays on the GL thread`() {
        val glThread = Executors.newSingleThreadExecutor { Thread(it, "gl") }
        val worker = Executors.newSingleThreadExecutor { Thread(it, "sort") }
        val realGl = FakeGl()
        val r = SplatRenderer(camera, realGl, worker.asCoroutineDispatcher(), clockNs = System::nanoTime)
        try {
            glThread.submit { r.onSurfaceCreated(null, null); r.onSurfaceChanged(null, 640, 480) }.get()
            val before = realGl.calls.size
            r.setScene(scene(64))
            assertEquals(before, realGl.calls.size)

            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (realGl.count("drawElements") == 0 && System.nanoTime() < deadline) {
                glThread.submit { r.onDrawFrame(null) }.get()
            }
            assertEquals(64 * 6, realGl.named("drawElements").first().args[1])
            val threads = realGl.calls.map { it.thread.name }.toSet()
            assertEquals(setOf("gl"), threads)
        } finally {
            r.dispose()
            glThread.shutdownNow()
            worker.shutdownNow()
        }
    }

    @Test
    fun `a new scene replaces the old texture`() {
        start()
        renderer.setScene(scene(4))
        frame()
        val oldTexture = gl.named("genTexture").last().args[0]
        renderer.setScene(scene(6))
        frame()
        assertEquals(listOf(oldTexture), gl.named("deleteTexture").map { it.args[0] })
        assertEquals(2, splatUploads().size)
        scheduler.advanceUntilIdle()
        frame()
        assertEquals(SplatShaders.ORDER_WIDTH, gl.orderUploads.last().second.size, "one whole row")
        assertEquals(6 * 6, draws().last().args[1])
    }

    @Test
    fun `slow frames move drawing into a smaller target that is blitted up`() {
        start(width = 1000, height = 2000)
        renderer.setScene(scene())
        frame()
        scheduler.advanceUntilIdle()
        repeat(RenderScaleController.WINDOW) { frame(stepMs = 25) }
        frame(stepMs = 25)
        assertEquals(1, gl.count("genFramebuffer"))
        assertEquals(listOf(900, 1800), gl.named("viewport").last().args)
        assertEquals(listOf(900, 1800, 1000, 2000), gl.named("blitFramebuffer").last().args)
        val focal = gl.named("uniform2f").filter { it.args[0] == "u_viewport".hashCode() }.last()
        assertEquals(listOf<Any?>("u_viewport".hashCode(), 900f, 1800f), focal.args)
    }

    @Test
    fun `a texture over GL_MAX_TEXTURE_SIZE is not uploaded or drawn`() {
        val small = FakeGl(maxTextureSize = 1)
        val r = SplatRenderer(camera, small, sortDispatcher, clockNs = { nowNs })
        try {
            r.onSurfaceCreated(null, null)
            r.onSurfaceChanged(null, 100, 100)
            r.setScene(scene(1100)) // 2 rows of 2048 texels
            r.onDrawFrame(null)
            scheduler.advanceUntilIdle()
            r.onDrawFrame(null)
            assertTrue(small.named("texImage2D").none { it.args[0] == GlConst.RGBA32UI })
            assertTrue(small.named("drawElements").isEmpty())
        } finally {
            r.dispose()
        }
    }

    @Test
    fun `a scene over one chunk is drawn in chunks, each from its first slot, with no instancing`() {
        val n = SplatShaders.CHUNK + 5
        val s = scene(n)
        start()
        renderer.setScene(s)
        frame()
        scheduler.advanceUntilIdle()
        frame()

        val upload = gl.orderUploads.single()
        assertBackToFront(s, upload)
        val rows = (n + SplatShaders.ORDER_WIDTH - 1) / SplatShaders.ORDER_WIDTH
        val orderTex = gl.named("texImage2D").single { it.args[0] == GlConst.R32UI }
        assertEquals(
            listOf<Any?>(GlConst.R32UI, SplatShaders.ORDER_WIDTH, rows, rows * SplatShaders.ORDER_WIDTH),
            orderTex.args.take(4),
        )
        assertEquals(listOf(SplatShaders.CHUNK * 6, 5 * 6), draws().map { it.args[1] })
        val drawCalls = gl.calls.filter { it.name == "drawElements" || it.args.firstOrNull() == "u_base".hashCode() }
        assertEquals(
            listOf("uniform1i" to 0, "drawElements" to null, "uniform1i" to SplatShaders.CHUNK, "drawElements" to null),
            drawCalls.map { it.name to (if (it.name == "uniform1i") it.args[1] else null) },
        )
    }

    @Test
    fun `the index buffer holds one chunk of two-triangle quads`() {
        start()
        val upload = gl.named("bufferData").single { it.args[3] == GlConst.ELEMENT_ARRAY_BUFFER }
        val indices = upload.args[4] as ShortArray
        assertEquals(SplatShaders.CHUNK * 6, indices.size)
        assertEquals(listOf<Short>(0, 1, 2, 2, 1, 3, 4, 5, 6, 6, 5, 7), indices.take(12))
        val vertices = indices.map { it.toInt() and 0xFFFF }
        assertEquals((0 until SplatShaders.CHUNK * 4).toSet(), vertices.toSet(), "every vertex of the chunk, once")
        assertEquals(GlConst.STATIC_DRAW, upload.args[2])
    }

    @Test
    @Timeout(5)
    fun `stats are published about twice a second`() {
        start()
        renderer.setScene(scene())
        repeat(40) { frame(stepMs = 16) }
        assertTrue(stats.isNotEmpty())
        val s = stats.first()
        assertEquals(8, s.splats)
        assertTrue(s.fps in 55f..70f, "fps ${s.fps}")
    }

    @Test
    @Timeout(5)
    fun `the stats flow holds the latest stats for the screen`() {
        start()
        renderer.setScene(scene())
        assertEquals(null, renderer.stats.value)
        repeat(40) { frame(stepMs = 16) }
        assertEquals(stats.last(), renderer.stats.value)
        assertEquals(8, renderer.stats.value?.splats)
    }

    private fun uniform(kind: String, name: String) = gl.named(kind).filter { it.args[0] == name.hashCode() }
    private fun overlayDraws() = gl.named("drawArrays")

    /** Draws a scene until its first order is on screen. */
    private fun drawing() {
        start()
        renderer.setScene(scene())
        frame()
        scheduler.advanceUntilIdle()
        frame()
        assertEquals(1, draws().size)
    }

    @Test
    fun `with no crop box the dim factor is one and no box is drawn`() {
        drawing()
        assertEquals(listOf(1f), uniform("uniform1f", "u_cropDim").map { it.args[1] })
        assertTrue(uniform("uniform3f", "u_cropCentre").isEmpty())
        assertTrue(overlayDraws().isEmpty())
    }

    @Test
    fun `a crop box sets the dim uniform and draws the box, null clears it, and nothing is re-uploaded or re-sorted`() {
        drawing()
        val textures = gl.count("texImage2D")
        val buffers = gl.count("bufferData")
        val box = CropBox(Vec3(0.5f, 0f, -1f), Vec3(1f, 2f, 3f), Quat.IDENTITY)

        val before = gl.calls.size
        renderer.setCropBox(box)
        assertEquals(before, gl.calls.size, "setCropBox makes no GL call")
        frame()
        scheduler.advanceUntilIdle()
        assertEquals(SplatRenderer.CROP_DIM_ALPHA, uniform("uniform1f", "u_cropDim").last().args[1])
        assertEquals(listOf(0.5f, 0f, -1f), uniform("uniform3f", "u_cropCentre").last().args.drop(1))
        assertEquals(listOf(1f, 2f, 3f), uniform("uniform3f", "u_cropHalf").last().args.drop(1))
        val axes = uniform("uniformMatrix3fv", "u_cropAxes").last().args[1] as FloatArray
        assertTrue(floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f).contentEquals(axes))
        assertEquals(
            listOf(listOf(GlConst.TRIANGLES, 0, 36), listOf(GlConst.LINES, 36, 24)),
            overlayDraws().map { it.args },
        )

        renderer.setCropBox(box.moveFace(BoxFace.X_POS, 0.5f))
        frame()
        scheduler.advanceUntilIdle()
        assertEquals(listOf(1.25f, 2f, 3f), uniform("uniform3f", "u_cropHalf").last().args.drop(1))

        renderer.setCropBox(null)
        frame()
        assertEquals(1f, uniform("uniform1f", "u_cropDim").last().args[1])
        assertEquals(4, overlayDraws().size, "no box drawn after clearing")

        assertEquals(textures, gl.count("texImage2D"), "no splat re-upload")
        assertEquals(buffers, gl.count("bufferData"), "no order or overlay buffer re-upload")
        assertEquals(1, gl.orderUploads.size, "no re-sort")
    }

    @Test
    fun `a NaN or degenerate crop box is ignored and the last good box kept`() {
        drawing()
        val good = CropBox(Vec3(0f, 0f, 0f), Vec3(1f, 1f, 1f), Quat.IDENTITY)
        renderer.setCropBox(good)
        frame()
        renderer.setCropBox(CropBox(Vec3(Float.NaN, 0f, 0f), Vec3(1f, 1f, 1f), Quat.IDENTITY))
        frame()
        renderer.setCropBox(CropBox(Vec3(0f, 0f, 0f), Vec3(0f, 1f, 1f), Quat.IDENTITY))
        frame()
        renderer.setCropBox(CropBox(Vec3(0f, 0f, 0f), Vec3(1f, Float.POSITIVE_INFINITY, 1f), Quat.IDENTITY))
        frame()
        val centres = uniform("uniform3f", "u_cropCentre").map { it.args.drop(1) }
        val halves = uniform("uniform3f", "u_cropHalf").map { it.args.drop(1) }
        assertEquals(4, centres.size)
        assertTrue(centres.all { it == listOf(0f, 0f, 0f) }, "centres $centres")
        assertTrue(halves.all { it == listOf(1f, 1f, 1f) }, "halves $halves")
        assertTrue(uniform("uniform1f", "u_cropDim").takeLast(4).all { it.args[1] == SplatRenderer.CROP_DIM_ALPHA })
    }

    @Test
    fun `the overlay program is rebuilt after a lost context`() {
        drawing()
        renderer.setCropBox(CropBox(Vec3(0f, 0f, 0f), Vec3(1f, 1f, 1f), Quat.IDENTITY))
        frame()
        start()
        assertEquals(4, gl.count("createProgram"), "splat and overlay programs, twice")
        frame()
        assertEquals(4, overlayDraws().size, "the box is still drawn on the new context")
    }

    private fun maskUploads(g: FakeGl = gl) = g.named("texImage2D").filter { it.args[0] == GlConst.R8UI }
    private fun maskOn() = uniform("uniform1i", "u_maskOn").last().args[1]

    /** The texture name bound just before the last mask upload. */
    private fun maskTextureName(): Any? {
        val upload = gl.calls.indexOfLast { it.name == "texImage2D" && it.args[0] == GlConst.R8UI }
        return gl.calls.subList(0, upload).last { it.name == "bindTexture" }.args[0]
    }

    @Test
    fun `with no erase mask nothing is uploaded and the mask is off`() {
        drawing()
        assertTrue(maskUploads().isEmpty())
        assertEquals(0, maskOn())
    }

    @Test
    fun `a mask uploads an R8UI texture 2048 wide on its own unit and turns the erase look on`() {
        drawing()
        val mask = EraseMask(5000).apply { mark(1) }
        val before = gl.calls.size
        renderer.setEraseMask(mask)
        assertEquals(before, gl.calls.size, "setEraseMask makes no GL call")
        frame()
        assertEquals(
            listOf(listOf<Any?>(GlConst.R8UI, 2048, 3, 3 * 2048, GlConst.RED_INTEGER, GlConst.UNSIGNED_BYTE)),
            maskUploads().map { it.args },
        )
        val upload = gl.calls.indexOfLast { it.name == "texImage2D" && it.args[0] == GlConst.R8UI }
        val unit = gl.calls.subList(0, upload).last { it.name == "activeTexture" }.args[0]
        assertEquals(GlConst.TEXTURE2, unit)
        assertEquals(1, maskOn())
        assertEquals(2, uniform("uniform1i", "u_mask").last().args[1])
        assertEquals(
            listOf(1f, 0x3D / 255f, 0x71 / 255f),
            uniform("uniform3f", "u_eraseTint").last().args.drop(1),
        )
        assertEquals(ViewerConstants.ERASE_TINT_MIX, uniform("uniform1f", "u_eraseMix").last().args[1])
        assertEquals(ViewerConstants.ERASE_ALPHA, uniform("uniform1f", "u_eraseAlpha").last().args[1])
    }

    @Test
    fun `new versions re-upload once a frame however many arrived, the same version never`() {
        drawing()
        val mask = EraseMask(8)
        renderer.setEraseMask(mask)
        frame()
        assertEquals(1, maskUploads().size)
        frame()
        frame()
        assertEquals(1, maskUploads().size, "same version, no re-upload")

        mask.mark(0)
        mask.mark(3)
        mask.beginStroke()
        mask.mark(5)
        frame()
        assertEquals(2, maskUploads().size, "three versions, one upload")
        frame()
        assertEquals(2, maskUploads().size)

        renderer.setEraseMask(mask)
        frame()
        assertEquals(2, maskUploads().size, "handing over the same mask again does not re-upload")
    }

    @Test
    fun `a mask change neither re-sorts nor re-uploads the splats`() {
        drawing()
        val buffers = gl.count("bufferData")
        val mask = EraseMask(8)
        renderer.setEraseMask(mask)
        frame()
        scheduler.advanceUntilIdle()
        for (i in 0 until 8) {
            mask.mark(i)
            frame()
            scheduler.advanceUntilIdle()
        }
        assertEquals(9, maskUploads().size)
        assertEquals(1, splatUploads().size, "no packed texture upload")
        assertEquals(1, gl.orderUploads.size, "no re-sort")
        assertEquals(buffers, gl.count("bufferData"))
    }

    @Test
    fun `null deletes the mask texture and turns the mask off`() {
        drawing()
        renderer.setEraseMask(EraseMask(8).apply { mark(2) })
        frame()
        val name = maskTextureName()
        renderer.setEraseMask(null)
        frame()
        assertEquals(listOf(name), gl.named("deleteTexture").map { it.args[0] })
        assertEquals(0, maskOn())
        frame()
        assertEquals(1, maskUploads().size)
    }

    @Test
    fun `a lost context re-creates the mask texture and re-uploads it`() {
        drawing()
        renderer.setEraseMask(EraseMask(8).apply { mark(2) })
        frame()
        val callsBefore = gl.calls.size
        start()
        frame()
        assertEquals(2, maskUploads().size)
        assertTrue(gl.calls.drop(callsBefore).none { it.name == "deleteTexture" }, "old names are not deleted")
        scheduler.advanceUntilIdle()
        frame()
        assertEquals(2, maskUploads().size)
        assertEquals(1, maskOn())
    }

    @Test
    fun `a mask over GL_MAX_TEXTURE_SIZE is skipped and the scene still drawn`() {
        val small = FakeGl(maxTextureSize = 1)
        val r = SplatRenderer(camera, small, sortDispatcher, clockNs = { nowNs })
        try {
            r.onSurfaceCreated(null, null)
            r.onSurfaceChanged(null, 100, 100)
            r.setScene(scene())
            r.setEraseMask(EraseMask(3000)) // 2 rows of 2048 texels
            r.onDrawFrame(null)
            scheduler.advanceUntilIdle()
            r.onDrawFrame(null)
            r.onDrawFrame(null)
            assertTrue(maskUploads(small).isEmpty())
            assertEquals(1, small.named("texImage2D").count { it.args[0] == GlConst.RGBA32UI })
            assertEquals(2, small.named("drawElements").size)
            val on = small.named("uniform1i").filter { it.args[0] == "u_maskOn".hashCode() }
            assertTrue(on.all { it.args[1] == 0 })
        } finally {
            r.dispose()
        }
    }

    @Test
    fun `with a crop box and a mask both set the erase look wins`() {
        drawing()
        renderer.setCropBox(CropBox(Vec3(0f, 0f, 0f), Vec3(1f, 1f, 1f), Quat.IDENTITY))
        renderer.setEraseMask(EraseMask(8).apply { mark(0) })
        frame()
        assertEquals(1, maskOn())
        assertEquals(1f, uniform("uniform1f", "u_cropDim").last().args[1])
    }
}
