package com.huntercoles.splatman.core.design

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Colour tokens (docs/design/tokens.md). Screens read roles from `MaterialTheme.colorScheme`; the values here are
 * only the tokens that sit outside the Material 3 scheme.
 */
object SplatColors {
    /** Behind any text or control drawn over the camera or the splat. */
    val OverlayScrim = Color.Black.copy(alpha = 0.55f)

    /** Warning banners over the camera. */
    val OverlayScrimStrong = Color.Black.copy(alpha = 0.75f)

    /** Text and icons on the scrims. */
    val OverlayOnScrim = Color(0xFFFFFFFF)

    /** Clear colour of the splat renderer. */
    val ViewerBackground = Color(0xFF0A0A0D)

    /** Capture button while recording; always paired with the stop shape, never colour alone. */
    val RecordRed = Color(0xFFFF5449)
}

/** The brand scheme, forced on Capture, Processing and Viewer. */
val SplatDarkColorScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFFC7B5FF),
    onPrimary = Color(0xFF2E1A6B),
    primaryContainer = Color(0xFF46329A),
    onPrimaryContainer = Color(0xFFE6DEFF),
    secondary = Color(0xFF7FD8C4),
    onSecondary = Color(0xFF00382F),
    tertiary = Color(0xFFFFC46B),
    onTertiary = Color(0xFF432C00),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0E0D12),
    onBackground = Color(0xFFE8E5F0),
    surface = Color(0xFF0E0D12),
    onSurface = Color(0xFFE8E5F0),
    surfaceContainerLow = Color(0xFF1B1A22),
    surfaceContainer = Color(0xFF211F29),
    surfaceContainerHigh = Color(0xFF2B2934),
    surfaceContainerHighest = Color(0xFF36333F),
    onSurfaceVariant = Color(0xFFC9C4D4),
    outline = Color(0xFF938E9E),
    outlineVariant = Color(0xFF48454F),
    scrim = Color(0xFF000000),
)

/** Used only where a screen follows the system (Library, Onboarding, Settings, About). */
val SplatLightColorScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF5B45B0),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE6DEFF),
    onPrimaryContainer = Color(0xFF1A0063),
    secondary = Color(0xFF006B5C),
    onSecondary = Color(0xFFFFFFFF),
    tertiary = Color(0xFF7F5700),
    onTertiary = Color(0xFFFFFFFF),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFCF8FF),
    onBackground = Color(0xFF1C1B21),
    surface = Color(0xFFFCF8FF),
    onSurface = Color(0xFF1C1B21),
    surfaceContainerLow = Color(0xFFF6F2FA),
    surfaceContainer = Color(0xFFF0ECF4),
    surfaceContainerHigh = Color(0xFFEAE6EF),
    surfaceContainerHighest = Color(0xFFE5E1E9),
    onSurfaceVariant = Color(0xFF48454F),
    outline = Color(0xFF79757F),
    outlineVariant = Color(0xFFCAC4D0),
    scrim = Color(0xFF000000),
)
