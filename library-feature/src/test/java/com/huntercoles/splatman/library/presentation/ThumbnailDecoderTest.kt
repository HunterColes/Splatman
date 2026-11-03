package com.huntercoles.splatman.library.presentation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ThumbnailDecoderTest {

    @TempDir
    lateinit var tmp: File

    @Test
    fun `a huge picture is sampled down to no smaller than the card`() {
        // A 20 MB-class 4000 x 5000 picture into a 400 x 500 px card: decoded at 1/8, 500 x 625.
        assertEquals(8, ThumbnailDecoder.sampleSize(4_000, 5_000, 400, 500))
        assertEquals(2, ThumbnailDecoder.sampleSize(1_000, 1_250, 400, 500))
    }

    @Test
    fun `a picture already at or under card size is not sampled`() {
        assertEquals(1, ThumbnailDecoder.sampleSize(400, 500, 400, 500))
        assertEquals(1, ThumbnailDecoder.sampleSize(100, 100, 400, 500))
        assertEquals(1, ThumbnailDecoder.sampleSize(4_000, 5_000, 0, 0))
    }

    @Test
    fun `the tighter side decides`() {
        // Wide picture, tall card: the height limits the sampling.
        assertEquals(2, ThumbnailDecoder.sampleSize(4_000, 1_000, 400, 500))
    }

    @Test
    fun `a missing or 0-byte file is no picture, without touching the decoder`() {
        assertNull(ThumbnailDecoder.decode(File(tmp, "nowhere.png"), 400, 500))
        val empty = File(tmp, "thumbnail.png").apply { writeBytes(ByteArray(0)) }
        assertNull(ThumbnailDecoder.decode(empty, 400, 500))
        assertNull(ThumbnailDecoder.decode(tmp, 400, 500), "a folder is not a picture")
    }
}
