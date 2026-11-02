package com.huntercoles.splatman.library.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.huntercoles.splatman.core.design.SplatDimens
import com.huntercoles.splatman.library.domain.model.SplatScene
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/**
 * Expandable bottom panel for scene list
 * 
 * Features:
 * - Drag to expand/collapse
 * - Tap header to toggle
 * - Smooth spring animations
 * - SplatGold accents for visibility
 * - Scrollable scene list
 * 
 * @param scenes List of all scenes
 * @param selectedScene Currently selected scene (highlighted)
 * @param onSceneClick Callback when scene is clicked
 * @param modifier Modifier
 */
@Composable
fun SplatExpandablePanel(
    scenes: List<SplatScene>,
    selectedScene: SplatScene?,
    onSceneClick: (SplatScene) -> Unit,
    modifier: Modifier = Modifier,
    panelState: ExpandablePanelState = rememberExpandablePanelState()
) {
    val coroutineScope = rememberCoroutineScope()
    
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(panelState.currentHeight)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(
                width = 4.dp,
                color = MaterialTheme.colorScheme.primary,
                shape = RoundedCornerShape(topStart = SplatDimens.CornerLarge, topEnd = SplatDimens.CornerLarge)
            )
            .clip(RoundedCornerShape(topStart = SplatDimens.CornerLarge, topEnd = SplatDimens.CornerLarge))
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Drag handle + header
            PanelHeader(
                sceneCount = scenes.size,
                isExpanded = panelState.isExpanded,
                onToggle = {
                    coroutineScope.launch {
                        panelState.toggle()
                    }
                },
                onDrag = { dragAmount ->
                    coroutineScope.launch {
                        panelState.onDrag(dragAmount)
                    }
                },
                onDragEnd = { velocity ->
                    coroutineScope.launch {
                        panelState.onDragEnd(velocity)
                    }
                }
            )
            
            // Scene list
            if (scenes.isNotEmpty()) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(SplatDimens.SpacingMedium)
                ) {
                    items(scenes) { scene ->
                        SceneListItem(
                            scene = scene,
                            isSelected = scene.id == selectedScene?.id,
                            onClick = { onSceneClick(scene) }
                        )
                    }
                }
            } else {
                EmptySceneList()
            }
        }
    }
}

/**
 * Panel header with drag handle and toggle button
 */
@Composable
private fun PanelHeader(
    sceneCount: Int,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: (Float) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onToggle)
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragEnd = { onDragEnd(0f) },
                    onVerticalDrag = { _, dragAmount ->
                        onDrag(dragAmount)
                    }
                )
            }
            .padding(SplatDimens.SpacingDefault),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Drag handle indicator
        Box(
            modifier = Modifier
                .width(48.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.primary)
        )
        
        Spacer(modifier = Modifier.height(SplatDimens.SpacingSmall))
        
        // Header row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Scene Library ($sceneCount)",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
            
            Icon(
                imageVector = if (isExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                contentDescription = if (isExpanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/**
 * Individual scene list item
 */
@Composable
private fun SceneListItem(
    scene: SplatScene,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()) }
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = SplatDimens.SpacingSmall)
            .background(
                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                shape = RoundedCornerShape(SplatDimens.CornerSmall)
            )
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(SplatDimens.CornerSmall)
            )
            .clickable(onClick = onClick)
            .padding(SplatDimens.SpacingMedium),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = scene.name,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            
            Text(
                text = "${scene.gaussians.size} Gaussians • ${dateFormat.format(scene.createdAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

/**
 * Empty state when no scenes exist
 */
@Composable
private fun EmptySceneList() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(SplatDimens.SpacingLarge),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "No scenes captured yet",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
