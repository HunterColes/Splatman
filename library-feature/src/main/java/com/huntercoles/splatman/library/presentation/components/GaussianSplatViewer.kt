package com.huntercoles.splatman.library.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import com.huntercoles.splatman.core.design.SplatColors
import com.huntercoles.splatman.core.design.SplatDimens
import com.huntercoles.splatman.library.domain.model.SplatScene

/**
 * Gaussian Splat 3D Viewer placeholder
 * 
 * TODO: Integrate Filament renderer with:
 * - Point-based rendering (100k-200k Gaussians)
 * - Arcball camera controls (rotate, zoom, pan)
 * - Real-time rasterization at 30-60fps
 * 
 * For now: Shows scene name and Gaussian count
 * 
 * @param scene Scene to render
 * @param modifier Modifier
 */
@Composable
fun GaussianSplatViewer(
    scene: SplatScene?,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        if (scene != null) {
            // Placeholder: Will be replaced with Filament AndroidView
            ScenePlaceholder(scene)
        } else {
            Text(
                text = "Select a scene from the panel below",
                style = MaterialTheme.typography.bodyLarge,
                color = SplatColors.TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(SplatDimens.SpacingLarge)
            )
        }
    }
}

/**
 * Temporary placeholder showing scene info
 * Will be replaced with actual Filament rendering
 */
@Composable
private fun ScenePlaceholder(scene: SplatScene) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "${scene.name}\n\n${scene.gaussians.size} Gaussians\n\n3D rendering coming soon...",
            style = MaterialTheme.typography.titleLarge,
            color = SplatColors.SplatGold,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(SplatDimens.SpacingLarge)
        )
    }
}
