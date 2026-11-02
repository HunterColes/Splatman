package com.huntercoles.splatman.viewer.rendering.gl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RenderScaleControllerTest {

    private fun RenderScaleController.frames(n: Int, ms: Float): Float {
        var s = scale
        repeat(n) { s = onFrame(ms) }
        return s
    }

    @Test
    fun `starts at full resolution`() {
        assertEquals(1f, RenderScaleController().scale)
    }

    @Test
    fun `a window of slow frames drops one step, only once the window is full`() {
        val c = RenderScaleController()
        assertEquals(1f, c.frames(RenderScaleController.WINDOW - 1, 25f))
        assertEquals(0.9f, c.onFrame(25f), 1e-6f)
    }

    @Test
    fun `keeps dropping per window but never below half`() {
        val c = RenderScaleController()
        assertEquals(0.5f, c.frames(RenderScaleController.WINDOW * 10, 40f), 1e-6f)
    }

    @Test
    fun `fast frames raise the scale back, never above full`() {
        val c = RenderScaleController(initial = 0.5f)
        assertEquals(0.6f, c.frames(RenderScaleController.WINDOW, 8f), 1e-6f)
        assertEquals(1f, c.frames(RenderScaleController.WINDOW * 10, 8f), 1e-6f)
    }

    @Test
    fun `frame times between the thresholds hold the scale`() {
        val c = RenderScaleController(initial = 0.7f)
        assertEquals(0.7f, c.frames(RenderScaleController.WINDOW * 3, 16f), 1e-6f)
    }

    @Test
    fun `the mean decides, not a single spike`() {
        val c = RenderScaleController()
        c.frames(RenderScaleController.WINDOW - 1, 10f)
        // One 100 ms hitch: mean (29 * 10 + 100) / 30 = 13 ms, between the thresholds.
        assertEquals(1f, c.onFrame(100f))
    }

    @Test
    fun `invalid frame times are ignored`() {
        val c = RenderScaleController()
        repeat(RenderScaleController.WINDOW * 2) { c.onFrame(Float.NaN) }
        repeat(RenderScaleController.WINDOW * 2) { c.onFrame(-5f) }
        assertEquals(1f, c.scale)
    }
}
