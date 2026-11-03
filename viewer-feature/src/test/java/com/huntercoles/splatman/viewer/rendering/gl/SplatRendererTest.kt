package com.huntercoles.splatman.viewer.rendering.gl

import com.huntercoles.splatman.splat.edit.BoxFace
import com.huntercoles.splatman.splat.edit.CropBox
import com.huntercoles.splatman.splat.math.Quat
import com.huntercoles.splatman.splat.math.Vec3
import com.huntercoles.splatman.splat.model.SplatCloud
import com.huntercoles.splatman.viewer.rendering.GaussianCameraController
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

    private fun draws() = gl.named("drawArraysInstanced")
    private fun splatUploads() = gl.named("texImage2D").filter { it.args[0] == GlConst.RGBA32UI }

    private fun assertBackToFront(scene: RenderScene, order: IntArray) {
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
        assertEquals(listOf(2048, 1, 2048 * 16), splatUploads().single().args.drop(1))
        assertTrue(draws().isEmpty(), "no order yet, nothing drawn")

        scheduler.advanceUntilIdle()
        frame()
        assertEquals(1, gl.orderUploads.size)
        assertBackToFront(s, gl.orderUploads.single().second)
        assertEquals(listOf(GlConst.TRIANGLE_STRIP, 4, 8), draws().single().args)
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
        assertEquals(second, gl.named("vertexAttribIPointer").last().args[1])
        assertBackToFront(s, gl.orderUploads.last().second)
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
            while (realGl.count("drawArraysInstanced") == 0 && System.nanoTime() < deadline) {
                glThread.submit { r.onDrawFrame(null) }.get()
            }
            assertEquals(64, realGl.named("drawArraysInstanced").first().args[2])
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
        val oldTexture = gl.named("genTexture").single().args[0]
        renderer.setScene(scene(6))
        frame()
        assertEquals(listOf(oldTexture), gl.named("deleteTexture").map { it.args[0] })
        assertEquals(2, splatUploads().size)
        scheduler.advanceUntilIdle()
        frame()
        assertEquals(6, gl.orderUploads.last().second.size)
        assertEquals(6, draws().last().args[2])
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
            assertTrue(small.named("texImage2D").isEmpty())
            assertTrue(small.named("drawArraysInstanced").isEmpty())
        } finally {
            r.dispose()
        }
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
}
