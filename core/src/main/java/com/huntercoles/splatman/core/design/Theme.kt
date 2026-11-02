package com.huntercoles.splatman.core.design

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/** Dark when forced or when the system is dark. No dynamic colour in v1. */
fun splatColorScheme(forceDark: Boolean, systemDark: Boolean): ColorScheme =
    if (forceDark || systemDark) SplatDarkColorScheme else SplatLightColorScheme

/**
 * The one Splatman theme. [forceDark] is for Capture, Processing and Viewer; everything else follows [darkTheme]
 * (the system, or the Theme setting). The app is edge-to-edge: with [systemBars] the theme sets the bar icons light
 * or dark to match; the app shell passes false and sets them for the current route itself.
 */
@Composable
fun SplatTheme(
    forceDark: Boolean = false,
    darkTheme: Boolean = isSystemInDarkTheme(),
    systemBars: Boolean = true,
    content: @Composable () -> Unit,
) {
    val dark = forceDark || darkTheme
    val colorScheme = splatColorScheme(forceDark, dark)
    val view = LocalView.current

    if (systemBars && !view.isInEditMode) {
        val window = (view.context as? Activity)?.window
        if (window != null) {
            SideEffect {
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}
