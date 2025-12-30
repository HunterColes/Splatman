package com.huntercoles.splatman.library

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.huntercoles.splatman.library.presentation.LibraryScreen

/**
 * Public API entry point for the library feature.
 * Use this to navigate to the library screen from other modules.
 */
@Composable
fun LibraryFeature(modifier: Modifier = Modifier) {
    LibraryScreen(modifier = modifier)
}
