package com.huntercoles.splatman.core.design

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val SplatDarkColorScheme = darkColorScheme(
    primary = SplatColors.DarkPurple,
    secondary = SplatColors.MediumPurple,
    tertiary = SplatColors.AccentPurple,
    background = SplatColors.SplatBlack,
    surface = SplatColors.DarkPurple,
    onPrimary = SplatColors.CardWhite,
    onSecondary = SplatColors.CardWhite,
    onTertiary = SplatColors.DeepPurple,
    onBackground = SplatColors.CardWhite,
    onSurface = SplatColors.CardWhite,
)

private val SplatLightColorScheme = lightColorScheme(
    primary = SplatColors.MediumPurple,
    secondary = SplatColors.LightPurple,
    tertiary = SplatColors.AccentPurple,
    background = SplatColors.CardWhite,
    surface = SplatColors.LightPurple,
    onPrimary = SplatColors.CardWhite,
    onSecondary = SplatColors.CardWhite,
    onTertiary = SplatColors.DeepPurple,
    onBackground = SplatColors.DeepPurple,
    onSurface = SplatColors.DeepPurple,
)

@Composable
fun SplatTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = pickColorScheme(darkTheme)
    val view = LocalView.current

    if (!view.isInEditMode) {
        val currentWindow = (view.context as? Activity)?.window
            ?: error("Not in an activity - unable to get Window reference")

        SideEffect {
            currentWindow.statusBarColor = colorScheme.primary.toArgb()
            WindowCompat.getInsetsController(currentWindow, view).isAppearanceLightStatusBars = darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}

@Composable
fun pickColorScheme(darkTheme: Boolean): ColorScheme = when {
    darkTheme -> SplatDarkColorScheme
    else -> SplatLightColorScheme
}

/**
 * Splatman Material 3 theme.
 * Simple, clean theme for the 3D Gaussian splat scanner.
 */
@Composable
fun SplatmanTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        darkTheme -> darkColorScheme()
        else -> lightColorScheme()
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        val currentWindow = (view.context as? Activity)?.window
            ?: error("Not in an activity - unable to get Window reference")

        SideEffect {
            currentWindow.statusBarColor = colorScheme.primary.toArgb()
            WindowCompat.getInsetsController(currentWindow, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}
