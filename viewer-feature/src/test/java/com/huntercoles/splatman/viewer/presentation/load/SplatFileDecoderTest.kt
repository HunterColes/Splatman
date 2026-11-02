package com.huntercoles.splatman.viewer.presentation.load

import com.huntercoles.splatman.splat.io.SplatFormat
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

class SplatFileDecoderTest {

    private val decoder = SplatFileDecoder()

    @Test
    fun `a PLY is detected by content and read in frame W`() {
        val cloud = TestClouds.cloud(5, shDegree = 1)
        val decoded = decoder.decode(TestClouds.ply(cloud).inputStream())
        assertEquals(SplatFormat.PLY, decoded.format)
        assertEquals(5, decoded.cloud.count)
        assertEquals(1, decoded.cloud.shDegree)
        assertFalse(decoded.shDropped)
        assertArrayEquals(cloud.positions, decoded.cloud.positions, 1e-6f)
    }

    @Test
    fun `a splat file is detected by size and read in frame W`() {
        val cloud = TestClouds.cloud(4)
        val decoded = decoder.decode(TestClouds.splat(cloud).inputStream())
        assertEquals(SplatFormat.SPLAT, decoded.format)
        assertEquals(4, decoded.cloud.count)
        assertArrayEquals(cloud.positions, decoded.cloud.positions, 1e-6f)
    }

    @Test
    fun `the SH memory cap drops the rest terms instead of failing`() {
        // 3 splats x 3 channels x 15 coefficients x 4 bytes = 540 bytes of SH3 rest, over a 100-byte cap.
        val cloud = TestClouds.cloud(3, shDegree = 3)
        val decoded = SplatFileDecoder(shCapBytes = 100).decode(TestClouds.ply(cloud).inputStream())
        assertEquals(3, decoded.cloud.count)
        assertEquals(0, decoded.cloud.shDegree)
        assertEquals(0, decoded.cloud.shRest.size)
        assertTrue(decoded.shDropped)
    }

    @Test
    fun `an empty file is rejected with a message`() {
        val e = assertThrows<SplatFileException> { decoder.decode(ByteArray(0).inputStream()) }
        assertEquals("The file is empty.", e.message)
    }

    @Test
    fun `bytes that are no splat format are rejected`() {
        val e = assertThrows<SplatFileException> { decoder.decode(ByteArray(33) { 7 }.inputStream()) }
        assertEquals("Not a splat file (PLY or .splat).", e.message)
    }

    @Test
    fun `a truncated PLY is rejected as unreadable`() {
        val bytes = TestClouds.ply(TestClouds.cloud(10))
        val e = assertThrows<SplatFileException> { decoder.decode(bytes.copyOf(bytes.size - 10).inputStream()) }
        assertTrue(e.message!!.startsWith("Not a readable 3DGS PLY"), e.message)
    }

    @Test
    fun `an SPZ file is recognised but not opened yet`() {
        val gz = ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { it.write("NGSP".toByteArray() + ByteArray(12)) }
        }.toByteArray()
        val e = assertThrows<SplatFileException> { decoder.decode(gz.inputStream()) }
        assertEquals("SPZ files cannot be opened yet.", e.message)
    }

    @Test
    fun `a non-PLY file over the raw limit is refused before it is read whole`() {
        val e = assertThrows<SplatFileException> {
            SplatFileDecoder(maxRawBytes = 64).decode(TestClouds.splat(TestClouds.cloud(3)).inputStream())
        }
        assertTrue(e.message!!.contains("too large"), e.message)
    }
}
