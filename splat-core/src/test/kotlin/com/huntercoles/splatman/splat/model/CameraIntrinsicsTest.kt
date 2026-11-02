package com.huntercoles.splatman.splat.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class CameraIntrinsicsTest {

    private val tol = 1e-6f
    private val k = CameraIntrinsics(fx = 500f, fy = 480f, cx = 319.5f, cy = 241f, width = 640, height = 480)

    @Test
    fun `scaled to half size halves focal lengths and keeps pixel centres`() {
        val h = k.scaled(320, 240)
        assertEquals(250f, h.fx, tol)
        assertEquals(240f, h.fy, tol)
        assertEquals((319.5f + 0.5f) * 0.5f - 0.5f, h.cx, tol)
        assertEquals((241f + 0.5f) * 0.5f - 0.5f, h.cy, tol)
        assertEquals(159.5f, h.cx, tol)
        assertEquals(320, h.width)
        assertEquals(240, h.height)
        assertEquals(Distortion.NONE, h.distortion)
    }

    @Test
    fun `scaled to the same size is the identity`() {
        assertEquals(k, k.scaled(640, 480))
    }

    @Test
    fun `scaled keeps distortion`() {
        val d = Distortion(0.1f, -0.02f, 0.001f, 0.002f, 0.003f)
        assertEquals(d, k.copy(distortion = d).scaled(320, 240).distortion)
    }

    @Test
    fun `rejects non-positive size`() {
        assertThrows<IllegalArgumentException> { k.copy(width = 0) }
        assertThrows<IllegalArgumentException> { k.copy(height = -1) }
        assertThrows<IllegalArgumentException> { k.scaled(0, 240) }
        assertThrows<IllegalArgumentException> { k.scaled(320, 0) }
    }

    @Test
    fun `rejects non-positive focal length`() {
        assertThrows<IllegalArgumentException> { k.copy(fx = 0f) }
        assertThrows<IllegalArgumentException> { k.copy(fy = -1f) }
        assertThrows<IllegalArgumentException> { k.copy(fx = Float.NaN) }
    }
}
