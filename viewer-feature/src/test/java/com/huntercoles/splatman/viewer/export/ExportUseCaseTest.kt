package com.huntercoles.splatman.viewer.export

import com.huntercoles.splatman.splat.io.ply.PlyWriter
import com.huntercoles.splatman.splat.io.splat.SplatCodec
import com.huntercoles.splatman.viewer.presentation.load.TestClouds
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

class ExportUseCaseTest {

    private val dispatcher = StandardTestDispatcher()
    private val useCase = ExportUseCase(dispatcher)
    private val cloud = TestClouds.cloud(5000, shDegree = 1)

    /** Accepts [limit] bytes, then fails like a full disk. */
    private class FailingStream(private val limit: Int) : OutputStream() {
        var written = 0
        var closed = false
        override fun write(b: Int) {
            if (written >= limit) throw IOException("No space left on device")
            written++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (written + len > limit) throw IOException("No space left on device")
            written += len
        }

        override fun close() {
            closed = true
        }
    }

    @Test
    fun `PLY is the default and first, and SPZ is not offered without a codec`() {
        assertEquals(ExportFormat.PLY, ExportCodecs.DEFAULT)
        assertEquals(listOf(ExportFormat.PLY, ExportFormat.SPLAT), useCase.formats)
        assertFalse(ExportFormat.SPZ in useCase.formats)
        assertEquals("application/octet-stream", ExportCodecs.MIME)
    }

    @Test
    fun `PLY export writes exactly the PLY writer's bytes and reports progress up to 1`() = runTest(dispatcher) {
        val out = ByteArrayOutputStream()
        val progress = mutableListOf<Float>()
        val result = useCase.export(cloud, ExportFormat.PLY, { out }, { progress += it })
        assertEquals(ExportResult.Done, result)
        val expected = ByteArrayOutputStream().also { PlyWriter.write(cloud, it) }.toByteArray()
        assertArrayEquals(expected, out.toByteArray())
        assertTrue(progress.size > 2, "progress steps: $progress")
        assertEquals(progress.sorted(), progress)
        assertEquals(1f, progress.last())
    }

    @Test
    fun `each codec's expected size is its real output size`() = runTest(dispatcher) {
        for (codec in ExportCodecs.AVAILABLE) {
            val out = ByteArrayOutputStream()
            useCase.export(cloud, codec.format, { out })
            assertEquals(out.size().toLong(), codec.expectedBytes(cloud.count, cloud.shDegree), "${codec.format}")
        }
    }

    @Test
    fun `splat export writes exactly the splat codec's bytes`() = runTest(dispatcher) {
        val out = ByteArrayOutputStream()
        val result = useCase.export(cloud, ExportFormat.SPLAT, { out })
        assertEquals(ExportResult.Done, result)
        assertArrayEquals(SplatCodec.write(cloud), out.toByteArray())
    }

    @Test
    fun `a write failure becomes an error result and the stream is still closed`() = runTest(dispatcher) {
        for (format in useCase.formats) {
            val stream = FailingStream(limit = 1000)
            val result = useCase.export(cloud, format, { stream })
            assertEquals(ExportResult.Failed(ExportUseCase.WRITE_ERROR), result, "$format")
            assertTrue(stream.closed, "$format stream closed")
        }
    }

    @Test
    fun `a destination that cannot be opened becomes an error result`() = runTest(dispatcher) {
        val result = useCase.export(cloud, ExportFormat.PLY, { throw java.io.FileNotFoundException("gone") })
        assertEquals(ExportResult.Failed(ExportUseCase.WRITE_ERROR), result)
        val denied = useCase.export(cloud, ExportFormat.PLY, { throw SecurityException("no grant") })
        assertEquals(ExportResult.Failed(ExportUseCase.DENIED_ERROR), denied)
    }

    @Test
    fun `asking for SPZ without its codec fails cleanly`() = runTest(dispatcher) {
        val out = ByteArrayOutputStream()
        val result = useCase.export(cloud, ExportFormat.SPZ, { out })
        assertInstanceOf(ExportResult.Failed::class.java, result)
        assertEquals(0, out.size())
    }

    @Test
    fun `file names are the scan name plus the format's extension`() {
        assertEquals("Measuring tape.ply", ExportFileName.of("Measuring tape", ExportFormat.PLY))
        assertEquals("Cactus.splat", ExportFileName.of("Cactus", ExportFormat.SPLAT))
        assertEquals("garden.splat", ExportFileName.of("garden.ply", ExportFormat.SPLAT))
        assertEquals("room.spz", ExportFileName.of("room.SPLAT", ExportFormat.SPZ))
    }

    @Test
    fun `file names are sanitised`() {
        assertEquals("_.._etc_passwd.ply", ExportFileName.of("/../etc/passwd", ExportFormat.PLY))
        assertEquals("secret.ply", ExportFileName.of("...secret", ExportFormat.PLY))
        assertEquals("a_b_c_d_.ply", ExportFileName.of("a:b*c?d\u0000", ExportFormat.PLY))
        assertEquals("Café 2.ply", ExportFileName.of("  Café 2  ", ExportFormat.PLY))
        assertEquals("splat.ply", ExportFileName.of("", ExportFormat.PLY))
        assertEquals("splat.ply", ExportFileName.of(" .. ", ExportFormat.PLY))
        assertEquals("splat.splat", ExportFileName.of("x.splat".removePrefix("x"), ExportFormat.SPLAT))
        val long = ExportFileName.of("n".repeat(500), ExportFormat.PLY)
        assertEquals(ExportFileName.MAX_STEM + ".ply".length, long.length)
        for (name in listOf("a/b", "..", "\\x", "con\nsole")) {
            val safe = ExportFileName.of(name, ExportFormat.PLY)
            assertFalse(safe.contains('/') || safe.contains('\\') || safe.contains('\n'), safe)
            assertFalse(safe.startsWith("."), safe)
        }
    }
}
