package com.huntercoles.splatman.capture.recording

import com.huntercoles.splatman.capture.tracking.CameraImage
import com.huntercoles.splatman.capture.tracking.LumaPlane
import com.huntercoles.splatman.splat.model.RgbImage
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer

/**
 * #340: how [Camera2FrameSource] hands images out ([Camera2FrameSource.deliver], [ReaderLease]) against a fake
 * reader that, like `ImageReader.close()`, closes every image it handed out, and images that throw once closed.
 */
class Camera2FrameSourceTest {

    /** Like `ImageReader`: [close] closes every image it handed out. */
    class ClosingReader : AutoCloseable {
        val images = mutableListOf<ClosingImage>()
        var closes = 0
            private set

        fun next(): ClosingImage = ClosingImage().also { images += it }

        override fun close() {
            closes++
            images.forEach { it.close() }
        }
    }

    /** Like `android.media.Image`: reading planes after close throws "Image is already closed". */
    class ClosingImage : CameraImage {
        override val width = 64
        override val height = 48
        var closed = false
            private set

        /** Closes that changed the state (a second close is a no-op, as on `Image`). */
        var closes = 0
            private set

        override fun toRgb(): RgbImage {
            check(!closed) { "Image is already closed" }
            return RgbImage(width, height, ByteArray(width * height * 4))
        }

        override fun luma(): LumaPlane {
            check(!closed) { "Image is already closed" }
            return LumaPlane(width, height, width, ByteArray(width * height) { (it * 31).toByte() })
        }

        override fun close() {
            if (closed) return
            closed = true
            closes++
        }
    }

    private class Catcher : PoseFreeFrameSource.Listener {
        val frames = mutableListOf<SourceFrame>()
        override fun onFrame(frame: SourceFrame) {
            frames += frame
        }

        override fun onError(reason: String) = Unit
    }

    @Test
    fun suspendWhileAFrameIsOutKeepsItReadableAndClosesTheReaderAfterIt() {
        val reader = ClosingReader()
        val lease = ReaderLease(reader)
        val catcher = Catcher()
        val image = reader.next()
        Camera2FrameSource.deliver(image, 1L, null, lease, catcher)
        val frame = catcher.frames.single()

        lease.retire() // what suspend() and close() do to the reader
        assertEquals(0, reader.closes)
        frame.image.luma() // master closed the reader here, and this threw "Image is already closed"
        frame.keep()

        frame.close()
        frame.close()
        assertEquals(1, reader.closes)
        assertEquals(1, image.closes)
    }

    @Test
    fun aFrameAfterSuspendIsClosedAtOnceAndNeverDelivered() {
        val reader = ClosingReader()
        val lease = ReaderLease(reader)
        val catcher = Catcher()
        lease.retire()
        assertEquals(1, reader.closes) // nothing out: closed at once

        val late = reader.next()
        Camera2FrameSource.deliver(late, 2L, null, lease, catcher)
        assertTrue(catcher.frames.isEmpty())
        assertTrue(late.closed)
        assertEquals(1, reader.closes)
    }

    @Test
    fun noListenerClosesTheImageAndKeepsNothingOut() {
        val reader = ClosingReader()
        val lease = ReaderLease(reader)
        val image = reader.next()
        Camera2FrameSource.deliver(image, 3L, null, lease, null)
        assertEquals(1, image.closes)
        lease.retire()
        assertEquals(1, reader.closes)
    }

    @Test
    fun theReaderWaitsForEveryFrameOut() {
        val reader = ClosingReader()
        val lease = ReaderLease(reader)
        val catcher = Catcher()
        repeat(3) { Camera2FrameSource.deliver(reader.next(), it.toLong(), null, lease, catcher) }
        lease.retire()
        catcher.frames[0].close()
        catcher.frames[2].close()
        assertEquals(0, reader.closes)
        catcher.frames[1].image.luma()
        catcher.frames[1].close()
        assertEquals(1, reader.closes)
        assertTrue(reader.images.all { it.closes == 1 })
    }

    @Test
    fun keepCopiesNv21WhenTheSourceOffersIt() {
        val reader = ClosingReader()
        val lease = ReaderLease(reader)
        val catcher = Catcher()
        val nv21 = KeptPixels.Nv21(2, 2, ByteArray(6))
        Camera2FrameSource.deliver(reader.next(), 4L, 5L, lease, catcher) { nv21 }
        val frame = catcher.frames.single()
        assertEquals(nv21, frame.keep())
        assertEquals(5L, frame.exposureNs)
        frame.close()
    }

    @Test
    fun nv21FromInterleavedPlanesWithRowPadding() {
        // 4x2: Y rows padded to 6; chroma one row of 2 samples, pixel stride 2 (V and U share memory, as on Pixels).
        val y = ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4, 0, 0, 5, 6, 7, 8))
        val vu = byteArrayOf(20, 10, 21, 11)
        val v = ByteBuffer.wrap(vu, 0, 3).slice()
        val u = ByteBuffer.wrap(vu, 1, 3).slice()
        val out = Nv21.copy(4, 2, y, 6, u, v, uvRowStride = 4, uvPixelStride = 2)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 20, 10, 21, 11), out)
        assertEquals(0, y.position())
    }

    @Test
    fun nv21FromPlanarPlanes() {
        // 4x4 planar (I420-like): chroma 2x2, pixel stride 1, row stride 2.
        val y = ByteBuffer.wrap(ByteArray(16) { it.toByte() })
        val u = ByteBuffer.wrap(byteArrayOf(50, 51, 52, 53))
        val v = ByteBuffer.wrap(byteArrayOf(60, 61, 62, 63))
        val out = Nv21.copy(4, 4, y, 4, u, v, uvRowStride = 2, uvPixelStride = 1)
        assertArrayEquals(ByteArray(16) { it.toByte() }, out.copyOfRange(0, 16))
        assertArrayEquals(byteArrayOf(60, 50, 61, 51, 62, 52, 63, 53), out.copyOfRange(16, 24))
    }

    @Test
    fun aFakeFrameKeepsRgb() {
        val image = ClosingImage()
        val frame = SourceFrame(1L, null, image)
        val kept = frame.keep()
        assertTrue(kept is KeptPixels.Rgb)
        assertEquals(64, kept.width)
        frame.close()
        assertFalse(runCatching { frame.keep() }.isSuccess)
        assertNull(frame.exposureNs)
    }
}
