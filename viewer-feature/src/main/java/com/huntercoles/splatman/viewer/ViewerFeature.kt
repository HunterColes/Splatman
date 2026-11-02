package com.huntercoles.splatman.viewer

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.huntercoles.splatman.viewer.presentation.ViewerScreen

/**
 * Public API entry point for the viewer feature: the scan named by the route's `scanId`. [onBack] leaves the
 * viewer (Back, "Back to library", and after the scan is deleted).
 */
@Composable
fun ViewerFeature(onBack: () -> Unit, modifier: Modifier = Modifier) {
    ViewerScreen(onBack = onBack, modifier = modifier)
}
