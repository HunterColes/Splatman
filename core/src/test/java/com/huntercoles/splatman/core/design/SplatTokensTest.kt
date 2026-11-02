package com.huntercoles.splatman.core.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SplatTokensTest {

    private val dark = SplatDarkColorScheme
    private val light = SplatLightColorScheme

    private fun channel(c: Float): Double {
        val v = c.toDouble()
        return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    private fun luminance(c: Color): Double =
        0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)

    /** WCAG 2.x contrast ratio between two opaque colours. */
    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    private fun assertAA(name: String, fg: Color, bg: Color, min: Double = 4.5) {
        val ratio = contrast(fg, bg)
        assertTrue(ratio >= min, "$name contrast %.2f:1 is below %.1f:1".format(ratio, min))
    }

    @Test
    fun `contrast helper matches the WCAG reference points`() {
        assertEquals(21.0, contrast(Color.White, Color.Black), 0.01)
        assertEquals(1.0, contrast(Color.White, Color.White), 0.0001)
    }

    @Test
    fun `text pairs meet AA in both schemes`() {
        for ((scheme, s) in listOf("dark" to dark, "light" to light)) {
            assertAA("$scheme onSurface/background", s.onSurface, s.background)
            assertAA("$scheme onSurfaceVariant/surfaceContainer", s.onSurfaceVariant, s.surfaceContainer)
            assertAA("$scheme onPrimary/primary", s.onPrimary, s.primary)
            assertAA("$scheme onPrimaryContainer/primaryContainer", s.onPrimaryContainer, s.primaryContainer)
            assertAA("$scheme onSecondary/secondary", s.onSecondary, s.secondary)
            assertAA("$scheme onTertiary/tertiary", s.onTertiary, s.tertiary)
            assertAA("$scheme onError/error", s.onError, s.error)
            assertAA("$scheme onErrorContainer/errorContainer", s.onErrorContainer, s.errorContainer)
        }
        assertAA("dark primary/background", dark.primary, dark.background)
    }

    @Test
    fun `outline meets the UI 3 to 1 minimum on dark background`() {
        assertAA("dark outline/background", dark.outline, dark.background, min = 3.0)
    }

    @Test
    fun `overlay text on scrim over a white camera frame meets AA`() {
        val scrimOverWhite = SplatColors.OverlayScrim.compositeOver(Color.White)
        assertAA("OverlayOnScrim/OverlayScrim over white", SplatColors.OverlayOnScrim, scrimOverWhite)
        val strongOverWhite = SplatColors.OverlayScrimStrong.compositeOver(Color.White)
        assertAA("OverlayOnScrim/OverlayScrimStrong over white", SplatColors.OverlayOnScrim, strongOverWhite)
    }

    private fun hex(c: Color) = "#%06X".format(c.toArgb() and 0xFFFFFF)

    @Test
    fun `dark and light schemes match the tokens table`() {
        val table = listOf(
            Triple("primary", dark.primary to light.primary, "#C7B5FF" to "#5B45B0"),
            Triple("onPrimary", dark.onPrimary to light.onPrimary, "#2E1A6B" to "#FFFFFF"),
            Triple("primaryContainer", dark.primaryContainer to light.primaryContainer, "#46329A" to "#E6DEFF"),
            Triple("onPrimaryContainer", dark.onPrimaryContainer to light.onPrimaryContainer, "#E6DEFF" to "#1A0063"),
            Triple("secondary", dark.secondary to light.secondary, "#7FD8C4" to "#006B5C"),
            Triple("onSecondary", dark.onSecondary to light.onSecondary, "#00382F" to "#FFFFFF"),
            Triple("tertiary", dark.tertiary to light.tertiary, "#FFC46B" to "#7F5700"),
            Triple("onTertiary", dark.onTertiary to light.onTertiary, "#432C00" to "#FFFFFF"),
            Triple("error", dark.error to light.error, "#FFB4AB" to "#BA1A1A"),
            Triple("onError", dark.onError to light.onError, "#690005" to "#FFFFFF"),
            Triple("errorContainer", dark.errorContainer to light.errorContainer, "#93000A" to "#FFDAD6"),
            Triple("onErrorContainer", dark.onErrorContainer to light.onErrorContainer, "#FFDAD6" to "#410002"),
            Triple("background", dark.background to light.background, "#0E0D12" to "#FCF8FF"),
            Triple("surface", dark.surface to light.surface, "#0E0D12" to "#FCF8FF"),
            Triple("surfaceContainerLow", dark.surfaceContainerLow to light.surfaceContainerLow, "#1B1A22" to "#F6F2FA"),
            Triple("surfaceContainer", dark.surfaceContainer to light.surfaceContainer, "#211F29" to "#F0ECF4"),
            Triple("surfaceContainerHigh", dark.surfaceContainerHigh to light.surfaceContainerHigh, "#2B2934" to "#EAE6EF"),
            Triple(
                "surfaceContainerHighest",
                dark.surfaceContainerHighest to light.surfaceContainerHighest,
                "#36333F" to "#E5E1E9",
            ),
            Triple("onSurface", dark.onSurface to light.onSurface, "#E8E5F0" to "#1C1B21"),
            Triple("onSurfaceVariant", dark.onSurfaceVariant to light.onSurfaceVariant, "#C9C4D4" to "#48454F"),
            Triple("outline", dark.outline to light.outline, "#938E9E" to "#79757F"),
            Triple("outlineVariant", dark.outlineVariant to light.outlineVariant, "#48454F" to "#CAC4D0"),
            Triple("scrim", dark.scrim to light.scrim, "#000000" to "#000000"),
        )
        for ((role, actual, expected) in table) {
            assertEquals(expected.first, hex(actual.first), "dark $role")
            assertEquals(expected.second, hex(actual.second), "light $role")
        }
    }

    @Test
    fun `overlay tokens match the tokens table`() {
        assertEquals(0.55f, SplatColors.OverlayScrim.alpha, 0.01f)
        assertEquals("#000000", hex(SplatColors.OverlayScrim))
        assertEquals(0.75f, SplatColors.OverlayScrimStrong.alpha, 0.01f)
        assertEquals("#FFFFFF", hex(SplatColors.OverlayOnScrim))
        assertEquals("#0A0A0D", hex(SplatColors.ViewerBackground))
        assertEquals("#FF5449", hex(SplatColors.RecordRed))
    }

    @Test
    fun `theme picks dark when forced or when the system is dark`() {
        assertSame(dark, splatColorScheme(forceDark = true, systemDark = false))
        assertSame(dark, splatColorScheme(forceDark = false, systemDark = true))
        assertSame(light, splatColorScheme(forceDark = false, systemDark = false))
    }

    @Test
    fun `type scale and numeral match the tokens table`() {
        assertEquals(36.sp, Typography.displaySmall.fontSize)
        assertEquals(44.sp, Typography.displaySmall.lineHeight)
        assertEquals(22.sp, Typography.titleLarge.fontSize)
        assertEquals(FontWeight.Medium, Typography.titleMedium.fontWeight)
        assertEquals(11.sp, Typography.labelSmall.fontSize)
        assertEquals(16.sp, Typography.labelSmall.lineHeight)
        assertEquals(28.sp, Typography.numeral.fontSize)
        assertEquals(36.sp, Typography.numeral.lineHeight)
        assertEquals(FontWeight.Medium, Typography.numeral.fontWeight)
        assertEquals("tnum", Typography.numeral.fontFeatureSettings)
    }

    @Test
    fun `dimens match the tokens table`() {
        assertEquals(4.dp, SplatDimens.SpacingXSmall)
        assertEquals(48.dp, SplatDimens.SpacingXXLarge)
        assertEquals(48.dp, SplatDimens.TouchTarget)
        assertEquals(72.dp, SplatDimens.CaptureButton)
        assertEquals(112.dp, SplatDimens.CoverageRing)
        assertEquals(8.dp, SplatDimens.CoverageRingStroke)
        assertEquals(840.dp, SplatDimens.ContentMaxWidth)
        assertEquals(160.dp, SplatDimens.LibraryCellMin)
        assertEquals(28.dp, SplatDimens.CornerLarge)
    }

    @Test
    fun `motion durations drop to zero when animations are removed`() {
        assertEquals(300, SplatMotion.duration(SplatMotion.DurationMedium, animatorScale = 1f))
        assertEquals(150, SplatMotion.duration(SplatMotion.DurationShort, animatorScale = 0.5f))
        assertEquals(0, SplatMotion.duration(SplatMotion.DurationLong, animatorScale = 0f))
        assertEquals(1500L, SplatMotion.GuidanceMinShow)
        assertEquals(4000L, SplatMotion.GuidanceCooldown)
    }
}
