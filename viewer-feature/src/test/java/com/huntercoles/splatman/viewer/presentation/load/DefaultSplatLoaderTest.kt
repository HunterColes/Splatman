package com.huntercoles.splatman.viewer.presentation.load

import com.huntercoles.splatman.splat.io.SplatFormat
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.io.InputStream

class DefaultSplatLoaderTest {

    private class BytesOpener(private val files: Map<SplatSource, ByteArray>) : SplatOpener {
        val closed = mutableListOf<SplatSource>()
        override fun open(source: SplatSource): InputStream {
            val bytes = files[source] ?: throw java.io.FileNotFoundException(source.toString())
            return object : ByteArrayInputStream(bytes) {
                override fun close() {
                    closed += source
                    super.close()
                }
            }
        }
        override fun displayName(source: SplatSource) = "shown-${source.name}"
    }

    private val sample = SplatSource.Sample("samples/x.ply", "X")
    private val doc = SplatSource.Document("content://picked/y", "Y")

    @Test
    fun `a PLY sample is decoded, packed and framed`() = runTest {
        val cloud = TestClouds.cloud(10, shDegree = 1)
        val opener = BytesOpener(mapOf(sample to TestClouds.ply(cloud)))
        val d = StandardTestDispatcher(testScheduler)
        val scene = DefaultSplatLoader(opener, d, d).load(sample)
        assertEquals("shown-X", scene.name)
        assertEquals(SplatFormat.PLY, scene.format)
        assertEquals(10, scene.count)
        assertEquals(10, scene.render.packed.count)
        assertEquals(1, scene.render.packed.height)
        assertEquals(scene.framing.radius, scene.render.radius)
        assertTrue(scene.framing.distance > 0f)
        assertEquals(listOf(sample), opener.closed)
    }

    @Test
    fun `a picked splat document loads`() = runTest {
        val opener = BytesOpener(mapOf(doc to TestClouds.splat(TestClouds.cloud(7))))
        val d = StandardTestDispatcher(testScheduler)
        val scene = DefaultSplatLoader(opener, d, d).load(doc)
        assertEquals(SplatFormat.SPLAT, scene.format)
        assertEquals(7, scene.count)
    }

    @Test
    fun `an unreadable document throws and its stream is closed`() = runTest {
        val opener = BytesOpener(mapOf(doc to ByteArray(33)))
        val d = StandardTestDispatcher(testScheduler)
        assertThrows<SplatFileException> { DefaultSplatLoader(opener, d, d).load(doc) }
        assertEquals(listOf(doc), opener.closed)
    }
}
