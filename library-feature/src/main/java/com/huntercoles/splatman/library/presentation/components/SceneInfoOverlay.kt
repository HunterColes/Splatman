package com.huntercoles.splatman.library.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.huntercoles.splatman.core.design.SplatColors
import com.huntercoles.splatman.core.design.SplatDimens
import com.huntercoles.splatman.library.domain.model.SplatScene
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Info overlay showing scene metadata
 * 
 * Displays:
 * - Scene name
 * - Gaussian count
 * - File size
 * - Bounding box dimensions
 * - Creation/modification dates
 * 
 * @param scene Scene to display info for
 * @param onDismiss Callback when overlay is dismissed
 * @param modifier Modifier
 */
@Composable
fun SceneInfoOverlay(
    scene: SplatScene,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dateFormat = remember { SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()) }
    
    Column(
        modifier = modifier
            .width(SplatDimens.SceneInfoOverlayWidth)
            .clip(RoundedCornerShape(SplatDimens.CornerMedium))
            .background(SplatColors.DeepPurple.copy(alpha = 0.95f))
            .border(
                width = SplatDimens.BorderMedium,
                color = SplatColors.SplatGold,
                shape = RoundedCornerShape(SplatDimens.CornerMedium)
            )
            .clickable(onClick = onDismiss) // Tap anywhere to dismiss
            .padding(SplatDimens.SpacingDefault),
        verticalArrangement = Arrangement.spacedBy(SplatDimens.SpacingSmall)
    ) {
        // Header
        Text(
            text = scene.name,
            style = MaterialTheme.typography.titleMedium,
            color = SplatColors.SplatGold,
            fontWeight = FontWeight.Bold,
            maxLines = 2
        )
        
        Spacer(modifier = Modifier.height(SplatDimens.SpacingXSmall))
        
        // Stats
        InfoRow(label = "Gaussians", value = formatNumber(scene.gaussians.size))
        InfoRow(label = "File Size", value = "${String.format("%.1f", scene.sizeInMB)} MB")
        InfoRow(label = "SH Degree", value = scene.shDegree.toString())
        
        Spacer(modifier = Modifier.height(SplatDimens.SpacingXSmall))
        
        // Bounding box
        Text(
            text = "Bounding Box",
            style = MaterialTheme.typography.labelMedium,
            color = SplatColors.SplatGold.copy(alpha = 0.8f),
            fontWeight = FontWeight.Bold
        )
        val bbox = scene.boundingBox
        Text(
            text = "Min: [${formatFloat(bbox.min[0])}, ${formatFloat(bbox.min[1])}, ${formatFloat(bbox.min[2])}]",
            style = MaterialTheme.typography.bodySmall,
            color = SplatColors.TextSecondary,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = "Max: [${formatFloat(bbox.max[0])}, ${formatFloat(bbox.max[1])}, ${formatFloat(bbox.max[2])}]",
            style = MaterialTheme.typography.bodySmall,
            color = SplatColors.TextSecondary,
            fontFamily = FontFamily.Monospace
        )
        
        Spacer(modifier = Modifier.height(SplatDimens.SpacingXSmall))
        
        // Dates
        InfoRow(label = "Created", value = dateFormat.format(scene.createdAt))
        InfoRow(label = "Modified", value = dateFormat.format(scene.modifiedAt))
        
        // Mobile limits warning
        if (!scene.isWithinMobileLimits) {
            Spacer(modifier = Modifier.height(SplatDimens.SpacingXSmall))
            Text(
                text = "⚠️ Exceeds mobile limits",
                style = MaterialTheme.typography.bodySmall,
                color = SplatColors.ErrorRed,
                fontWeight = FontWeight.Bold
            )
        }
        
        Spacer(modifier = Modifier.height(SplatDimens.SpacingXSmall))
        
        // Dismiss hint
        Text(
            text = "Tap to close",
            style = MaterialTheme.typography.labelSmall,
            color = SplatColors.TextSecondary.copy(alpha = 0.6f),
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
    }
}

/**
 * Info row with label and value
 */
@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = SplatColors.TextSecondary.copy(alpha = 0.8f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = SplatColors.CardWhite,
            fontWeight = FontWeight.Medium
        )
    }
}

// Utility functions
private fun formatNumber(num: Int): String {
    return when {
        num >= 1_000_000 -> String.format("%.1fM", num / 1_000_000.0)
        num >= 1_000 -> String.format("%.1fk", num / 1_000.0)
        else -> num.toString()
    }
}

private fun formatFloat(value: Float): String {
    return String.format("%.2f", value)
}
