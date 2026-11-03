package com.huntercoles.splatman.library.presentation

import android.graphics.Typeface
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Hardening (#67): the goldens' text is drawn with the fonts bundled in a test jar (Robolectric's
 * nativeruntime-dist-compat), copied to a temporary font directory, not with whatever fonts the machine has. Holds for
 * every module's goldens: they all run under the same Robolectric and graphics mode as this test.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [ScreenGolden.SDK], qualifiers = ScreenGolden.QUALIFIERS)
class GoldenFontsTest {

    @Test
    fun `the system font is Roboto from the test classpath, not from the machine`() {
        val families = Typeface::class.java.getDeclaredMethod("getSystemFontMap")
            .apply { isAccessible = true }
            .invoke(null) as Map<*, *>
        assertTrue("no sans-serif family in ${families.keys}", "sans-serif" in families)

        val fontDir = System.getProperty(FONT_DIR_PROPERTY)
        assertNotNull("Robolectric set no $FONT_DIR_PROPERTY: the fonts would come from elsewhere", fontDir)
        val dir = File(fontDir!!).canonicalFile
        HOST_FONT_DIRS.forEach { host -> assertFalse("$dir is the machine's $host", dir.path.startsWith(host)) }

        val bundled = javaClass.classLoader!!.getResource("fonts/$ROBOTO")
        assertNotNull("fonts/$ROBOTO is not on the test classpath", bundled)
        assertTrue("$bundled is not inside a jar", bundled!!.protocol == "jar")
        assertArrayEquals(
            "the font Robolectric draws with is not the bundled one",
            bundled.readBytes(),
            File(dir, ROBOTO).readBytes(),
        )
    }

    private companion object {
        const val FONT_DIR_PROPERTY = "robolectric.nativeruntime.fontdir"
        const val ROBOTO = "Roboto-Regular.ttf"
        val HOST_FONT_DIRS = listOf("/usr/share/fonts", "/usr/local/share/fonts", "/System/Library/Fonts", "/Library/Fonts")
    }
}
