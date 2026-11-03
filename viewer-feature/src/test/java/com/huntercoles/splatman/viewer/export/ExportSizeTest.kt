package com.huntercoles.splatman.viewer.export

import com.huntercoles.splatman.splat.io.ply.PlyWriter
import com.huntercoles.splatman.splat.io.splat.SplatCodec
import com.huntercoles.splatman.viewer.presentation.load.TestClouds
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import com.huntercoles.splatman.core.preferences.ExportFormat as SettingsFormat

/** #20: the size estimate per format, the low-storage check and filesystem-safe, unique file names. */
class ExportSizeTest {

    private fun plyBytes(n: Int, degree: Int): Long =
        ByteArrayOutputStream().also { PlyWriter.write(TestClouds.cloud(n, degree), it) }.size().toLong()

    @Test
    fun `the PLY estimate is the PLY writer's exact size at every SH degree`() {
        for (degree in 0..3) {
            val entry = ExportEntry("a", 37, degree)
            assertEquals(plyBytes(37, degree), ExportSize.of(ExportFormat.PLY, listOf(entry)), "degree $degree")
        }
    }

    @Test
    fun `the splat estimate is 32 bytes a splat whatever the SH degree`() {
        for (degree in 0..3) {
            val cloud = TestClouds.cloud(37, degree)
            val expected = SplatCodec.write(cloud).size.toLong()
            assertEquals(37L * 32, expected)
            assertEquals(expected, ExportSize.of(ExportFormat.SPLAT, listOf(ExportEntry("a", 37, degree))))
        }
    }

    @Test
    fun `a million-splat degree-3 scan is about 248 MB as PLY and 32 MB as splat`() {
        val entry = listOf(ExportEntry("big", 1_000_000, 3))
        assertEquals(248L, ExportSize.of(ExportFormat.PLY, entry) / 1_000_000)
        assertEquals(32_000_000L, ExportSize.of(ExportFormat.SPLAT, entry))
    }

    @Test
    fun `several scans sum, nothing is zero, and a format without a codec is zero`() {
        val entries = listOf(ExportEntry("a", 10, 0), ExportEntry("b", 20, 3))
        assertEquals(plyBytes(10, 0) + plyBytes(20, 3), ExportSize.of(ExportFormat.PLY, entries))
        assertEquals(30L * 32, ExportSize.of(ExportFormat.SPLAT, entries))
        assertEquals(0L, ExportSize.of(ExportFormat.PLY, emptyList()))
        assertEquals(0L, ExportSize.of(ExportFormat.SPZ, entries))
    }

    @Test
    fun `storage is low when the export and the headroom do not fit`() {
        val mb = 1L shl 20
        assertFalse(ExportStorage.isLow(needed = 38 * mb, free = 38 * mb + ExportStorage.HEADROOM))
        assertTrue(ExportStorage.isLow(needed = 38 * mb, free = 38 * mb + ExportStorage.HEADROOM - 1))
        assertTrue(ExportStorage.isLow(needed = 38 * mb, free = 10 * mb))
        assertTrue(ExportStorage.isLow(needed = 0, free = 0))
        assertFalse(ExportStorage.isLow(needed = 38 * mb, free = 64L * 1024 * mb))
        assertFalse(ExportStorage.isLow(needed = Long.MAX_VALUE / 2, free = null), "unknown free space is not low")
    }

    @Test
    fun `repeated scan names get numbered, ignoring case, after sanitising`() {
        assertEquals(
            listOf("Desk plant.ply", "Desk plant (2).ply", "desk PLANT (3).ply", "Cactus.ply", "a_b.ply", "a_b (2).ply"),
            ExportFileName.unique(
                listOf("Desk plant", "Desk plant", "desk PLANT", "Cactus", "a/b", "a:b"),
                ExportFormat.PLY,
            ),
        )
        assertEquals(listOf("splat.splat", "splat (2).splat"), ExportFileName.unique(listOf("", ".."), ExportFormat.SPLAT))
        assertEquals(emptyList<String>(), ExportFileName.unique(emptyList(), ExportFormat.PLY))
    }

    @Test
    fun `every settings format maps to an offered export format`() {
        assertEquals(ExportFormat.PLY, ExportFormat.of(SettingsFormat.Ply))
        assertEquals(ExportFormat.SPLAT, ExportFormat.of(SettingsFormat.Splat))
        SettingsFormat.entries.forEach { assertTrue(ExportCodecs.of(ExportFormat.of(it)) != null) }
    }
}
