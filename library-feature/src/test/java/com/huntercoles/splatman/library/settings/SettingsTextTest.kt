package com.huntercoles.splatman.library.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File
import java.util.Locale

class SettingsTextTest {

    private fun bytes(value: Long) = formatBytes(value, Locale.US)

    @Test
    fun `storage sizes read in decimal units`() {
        assertEquals("0 B", bytes(0))
        assertEquals("999 B", bytes(999))
        assertEquals("1.0 KB", bytes(1_000))
        assertEquals("340 MB", bytes(340_000_000))
        assertEquals("1.2 GB", bytes(1_200_000_000))
        assertEquals("12 GB", bytes(12_400_000_000))
        assertEquals("2.5 TB", bytes(2_500_000_000_000))
    }

    @Test
    fun `a size that rounds up to the next unit rolls over`() {
        assertEquals("1.0 MB", bytes(999_600))
        assertEquals("999 KB", bytes(999_000))
    }

    @Test
    fun `sizes follow the locale's decimal mark`() {
        assertEquals("1,2 GB", formatBytes(1_200_000_000, Locale.GERMANY))
    }

    @Test
    fun `licences parse tab-separated rows sorted by module`() {
        val text = "b:lib\t2.0\tApache-2.0\thttps://apache.org/LICENSE\n\nmalformed\na:lib\t1.0\tMIT\t\n"
        assertEquals(
            listOf(
                Licence("a:lib", "1.0", "MIT", null),
                Licence("b:lib", "2.0", "Apache-2.0", "https://apache.org/LICENSE"),
            ),
            parseLicences(text),
        )
    }

    @Test
    fun `the bundled samples' licence note yields one credit per sample`() {
        val note = File("../app/src/main/assets/samples/ASSETS-LICENSES.md").readText()
        val credits = parseSampleCredits(note)

        assertEquals(listOf("cactus-sh1.ply", "measuring-tape.ply", "cactus.spz"), credits.map { it.file })
        assertEquals("https://www.innerscene.com/samples/gsplat-measuring-tape.ply", credits[1].source)
        assertEquals("CC0 1.0", credits[1].licence)
        assertEquals("https://note.com/steam_studio/n/ne9736d94f162", credits[0].source)
    }
}
