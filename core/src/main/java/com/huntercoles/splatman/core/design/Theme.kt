package com.huntercoles.splatman.core.design

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/** Dark when forced or when the system is dark. No dynamic colour in v1. */
fun splatColorScheme(forceDark: Boolean, systemDark: Boolean): ColorScheme =
    if (forceDark || systemDark) SplatDarkColorScheme else SplatLightColorScheme

/**
 * The one Splatman theme. [forceDark] is for Capture, Processing and Viewer; everything else follows the system.
 */
@Composable
fun SplatTheme(
    forceDark: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = forceDark || isSystemInDarkTheme()
    val colorScheme = splatColorScheme(forceDark, dark)
    val view = LocalView.current

    if (!view.isInEditMode) {
        val window = (view.context as? Activity)?.window
        if (window != null) {
            SideEffect {
                @Suppress("DEPRECATION")
                window.statusBarColor = colorScheme.background.toArgb()
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !dark
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}
