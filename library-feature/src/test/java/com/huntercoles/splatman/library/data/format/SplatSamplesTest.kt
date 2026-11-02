package com.huntercoles.splatman.library.data.format

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The bundled splat samples (app/src/main/assets/samples) and the synthetic test fixture. */
class SplatSamplesTest {

    private val samples: File = listOf("../app/src/main/assets/samples", "app/src/main/assets/samples")
        .map(::File).first { it.isDirectory }

    private val standard3dgs = listOf("x", "y", "z", "f_dc_0", "f_dc_1", "f_dc_2", "opacity") +
        listOf("scale_0", "scale_1", "scale_2", "rot_0", "rot_1", "rot_2", "rot_3")

    private fun ply(name: String): Pair<SplatFileHeader.Ply, File> {
        val file = File(samples, name)
        val header = file.inputStream().use { SplatFileHeader.readPly(it) }
        assertEquals("binary_little_endian", header.format)
        assertTrue(header.properties.containsAll(standard3dgs))
        assertEquals(file.length(), header.bodyOffset + 4L * header.properties.size * header.splatCount)
        return header to file
    }

    @Test
    fun `cactus PLY has 139410 splats with SH degree 1`() {
        val (header, _) = ply("cactus-sh1.ply")
        assertEquals(139_410, header.splatCount)
        assertEquals(1, header.shDegree)
        assertEquals(23, header.properties.size)
    }

    @Test
    fun `measuring tape PLY has 19469 splats with SH degree 0`() {
        val (header, _) = ply("measuring-tape.ply")
        assertEquals(19_469, header.splatCount)
        assertEquals(0, header.shDegree)
    }

    @Test
    fun `cactus SPZ has 452451 splats`() {
        val header = File(samples, "cactus.spz").inputStream().use { SplatFileHeader.readSpz(it) }
        assertEquals(3, header.version)
        assertEquals(452_451, header.splatCount)
        assertEquals(0, header.shDegree)
        assertEquals(12, header.fractionalBits)
    }

    @Test
    fun `every bundled sample is covered and the goat point cloud is gone`() {
        val names = samples.list()!!.filterNot { it.endsWith(".md") }.sorted()
        assertEquals(listOf("cactus-sh1.ply", "cactus.spz", "measuring-tape.ply"), names)
        assertTrue(File(samples, "ASSETS-LICENSES.md").isFile)
        assertTrue(!File(samples.parentFile, "models").exists())
    }

    @Test
    fun `synthetic fixture holds the values make_fixture wrote`() {
        val bytes = javaClass.getResourceAsStream("/splats/tiny-sh1.ply")!!.use { it.readBytes() }
        val header = SplatFileHeader.readPly(ByteArrayInputStream(bytes))
        assertEquals(4, header.splatCount)
        assertEquals(1, header.shDegree)
        val stride = header.properties.size
        assertEquals(bytes.size, header.bodyOffset + 4 * stride * 4)
        val body = ByteBuffer.wrap(bytes, header.bodyOffset, bytes.size - header.bodyOffset)
            .order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        fun v(i: Int, p: String) = body.get(i * stride + header.properties.indexOf(p))
        for (i in 0 until 4) {
            assertEquals(i.toFloat(), v(i, "x"), 0f)
            assertEquals(2f * i, v(i, "y"), 0f)
            assertEquals(-i.toFloat(), v(i, "z"), 0f)
            assertEquals(0.1f * i, v(i, "f_dc_0"), 1e-6f)
            assertEquals(-0.3f, v(i, "f_dc_2"), 1e-6f)
            for (k in 0 until 9) assertEquals(i + k / 10f, v(i, "f_rest_$k"), 1e-6f)
            assertEquals(i - 1.5f, v(i, "opacity"), 0f)
            assertEquals(-1f - i, v(i, "scale_0"), 0f)
            assertEquals(-3f, v(i, "scale_2"), 0f)
            assertEquals(if (i % 2 == 0) 1f else 0f, v(i, "rot_0"), 0f)
            assertEquals(if (i % 2 == 0) 0f else 1f, v(i, "rot_1"), 0f)
        }
    }

    @Test
    fun `a PLY header without end_header is rejected`() {
        assertThrows(IOException::class.java) {
            SplatFileHeader.readPly(ByteArrayInputStream("ply\nformat ascii 1.0\nelement vertex 1\n".toByteArray()))
        }
    }

    @Test
    fun `a gzip stream without the NGSP magic is rejected`() {
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(out).use { it.write(ByteArray(16)) }
        assertThrows(IOException::class.java) { SplatFileHeader.readSpz(ByteArrayInputStream(out.toByteArray())) }
    }
}
