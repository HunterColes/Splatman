package com.huntercoles.splatman.splat.io

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.random.Random

class FormatDetectorTest {

    private fun gzip(bytes: ByteArray): ByteArray =
        ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(bytes) } }.toByteArray()

    @Test
    fun ply() {
        val h = "ply\nformat binary_little_endian 1.0\n".toByteArray()
        assertEquals(SplatFormat.PLY, FormatDetector.detect(h, 1001))
    }

    @Test
    fun spz() {
        val gz = gzip("NGSP".toByteArray() + ByteArray(200))
        assertEquals(SplatFormat.SPZ, FormatDetector.detect(gz, gz.size.toLong()))
    }

    @Test
    fun `gzip without NGSP is unknown`() {
        val gz = gzip("HELLO world".toByteArray())
        assertEquals(SplatFormat.UNKNOWN, FormatDetector.detect(gz, 64))
    }

    @Test
    fun splat() {
        val h = ByteArray(64) { 1 }
        assertEquals(SplatFormat.SPLAT, FormatDetector.detect(h, 64))
    }

    @Test
    fun `random 33 byte file is unknown`() {
        val b = Random(33).nextBytes(33)
        b[0] = 0x42 // not ply, not gzip
        assertEquals(SplatFormat.UNKNOWN, FormatDetector.detect(b, 33))
    }

    @Test
    fun `empty file is unknown`() {
        assertEquals(SplatFormat.UNKNOWN, FormatDetector.detect(ByteArray(0), 0))
    }
}
