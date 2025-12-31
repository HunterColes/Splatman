package com.huntercoles.splatman.viewer

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.huntercoles.splatman.viewer.presentation.ViewerScreen

/**
 * Public API entry point for the viewer feature.
 * Use this to navigate to the viewer screen from other modules.
 */
@Composable
fun ViewerFeature(modifier: Modifier = Modifier) {
    ViewerScreen(modifier = modifier)
}