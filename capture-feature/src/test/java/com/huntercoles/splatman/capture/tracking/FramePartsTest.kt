package com.huntercoles.splatman.capture.tracking

import com.huntercoles.splatman.splat.model.CameraIntrinsics
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class FramePartsTest {
    private class Counted : AutoCloseable {
        var closes = 0
        override fun close() {
            closes++
        }
    }

    @Test
    fun closeOnFailureClosesTheResourceWhenTheBuildThrows() {
        val image = Counted()
        val error = IllegalStateException("zero quaternion")
        val thrown = runCatching { FrameParts.closeOnFailure(image) { throw error } }.exceptionOrNull()
        assertSame(error, thrown)
        assertEquals(1, image.closes)
    }

    @Test
    fun closeOnFailureLeavesTheResourceOpenOnSuccess() {
        val image = Counted()
        assertEquals("frame", FrameParts.closeOnFailure(image) { "frame" })
        assertEquals(0, image.closes)
    }

    @Test
    fun closeOnFailureAcceptsNoResource() {
        val thrown = runCatching { FrameParts.closeOnFailure(null) { error("boom") } }.exceptionOrNull()
        assertTrue(thrown is IllegalStateException)
    }

    @Test
    fun featurePointsSplitPositionsAndConfidence() {
        val buffer = FloatBuffer.wrap(floatArrayOf(9f, 1f, 2f, 3f, 0.5f, -1f, -2f, -3f, 0.25f))
        buffer.position(1)
        val points = FrameParts.featurePoints(buffer)
        assertEquals(2, points.count)
        assertArrayEquals(floatArrayOf(1f, 2f, 3f, -1f, -2f, -3f), points.positions, 0f)
        assertArrayEquals(floatArrayOf(0.5f, 0.25f), points.confidence, 0f)
        assertEquals(1, buffer.position())
    }

    @Test
    fun depthMillimetresBecomeMetresWithRowPadding() {
        val intrinsics = CameraIntrinsics(fx = 2f, fy = 2f, cx = 0.5f, cy = 0.5f, width = 2, height = 2)
        val rowStride = 6
        val plane = ByteBuffer.allocate(rowStride * 2).order(ByteOrder.LITTLE_ENDIAN)
        plane.putShort(0, 1000).putShort(2, 0).putShort(4, 0x7777)
        plane.putShort(6, 1500).putShort(8, 65_000.toShort())
        val depth = FrameParts.depthFromMillimetres(plane, rowStride, pixelStride = 2, intrinsics = intrinsics)
        assertArrayEquals(floatArrayOf(1f, 0f, 1.5f, 65f), depth.depthM, 1e-6f)
        assertSame(intrinsics, depth.intrinsics)
        assertNull(depth.confidence)
    }
}
