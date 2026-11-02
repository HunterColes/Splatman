package com.huntercoles.splatman.viewer.presentation.composable

import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MonetizationOn
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.huntercoles.splatman.core.design.SplatDialog
import com.huntercoles.splatman.core.design.SplatDimens
import com.huntercoles.splatman.core.navigation.NavigationCommand
import com.huntercoles.splatman.core.navigation.NavigationDestination
import com.huntercoles.splatman.core.navigation.NavigationManager
import com.huntercoles.splatman.core.preferences.AudioPreferences
import com.huntercoles.splatman.viewer.presentation.ToolsViewModel

data class ToolItem(
    val title: String,
    val icon: ImageVector,
    val destination: NavigationDestination?,
    val isVolumeControl: Boolean = false
)

@Composable
fun ToolsHomeScreen(
    navigationManager: NavigationManager
) {
    val toolsViewModel: ToolsViewModel = hiltViewModel()
    val audioPreferences = toolsViewModel.audioPreferences
    var showVolumeDialog by remember { mutableStateOf(false) }
    
    val tools = listOf(
        ToolItem(
            title = "Settings",
            icon = Icons.Default.Settings,
            destination = null,
            isVolumeControl = true
        )
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(SplatDimens.SpacingDefault),
        verticalArrangement = Arrangement.spacedBy(SplatDimens.SpacingDefault)
    ) {
        Text(
            text = "🛠️ Tools",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(SplatDimens.SpacingSmall),
            horizontalArrangement = Arrangement.spacedBy(SplatDimens.SpacingDefault),
            verticalArrangement = Arrangement.spacedBy(SplatDimens.SpacingDefault)
        ) {
            items(tools) { tool ->
                ToolCard(
                    tool = tool,
                    onClick = {
                        if (tool.isVolumeControl) {
                            showVolumeDialog = true
                        } else {
                            tool.destination?.let { destination ->
                                navigationManager.navigate(object : NavigationCommand {
                                    override val destination = destination
                                })
                            }
                        }
                    }
                )
            }
        }
    }
    
    // Volume Control Dialog
    if (showVolumeDialog) {
        VolumeControlDialog(
            audioPreferences = audioPreferences,
            onDismiss = { showVolumeDialog = false }
        )
    }
}

@Composable
fun ToolCard(
    tool: ToolItem,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(140.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 4.dp
        ),
        shape = RoundedCornerShape(SplatDimens.CornerMedium)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(SplatDimens.SpacingDefault),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = tool.icon,
                contentDescription = tool.title,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(64.dp)
            )
            
            Spacer(modifier = Modifier.height(SplatDimens.SpacingSmall))
            
            Text(
                text = tool.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun VolumeControlDialog(
    audioPreferences: AudioPreferences,
    onDismiss: () -> Unit
) {
    val volume by audioPreferences.volume.collectAsStateWithLifecycle(initialValue = 1f)
    val isMuted by audioPreferences.isMuted.collectAsStateWithLifecycle(initialValue = false)
    
    SplatDialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Volume icon button
                IconButton(
                    onClick = { audioPreferences.toggleMute() }
                ) {
                    Icon(
                        imageVector = if (isMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                        contentDescription = if (isMuted) "Unmute" else "Mute",
                        tint = if (isMuted) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f) else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp)
                    )
                }
                
                // Volume slider with dotted line behind it
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    // Dotted line behind slider
                    val dottedLineColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .drawBehind {
                                val pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
                                drawLine(
                                    color = dottedLineColor,
                                    start = Offset(0f, size.height / 2),
                                    end = Offset(size.width, size.height / 2),
                                    strokeWidth = 2f,
                                    pathEffect = pathEffect
                                )
                            }
                    )
                    
                    // Slider
                    Slider(
                        value = volume,
                        onValueChange = { audioPreferences.setVolume(it) },
                        valueRange = 0f..1f,
                        enabled = !isMuted,
                        colors = SliderDefaults.colors(
                            thumbColor = if (isMuted) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f) else MaterialTheme.colorScheme.primary,
                            activeTrackColor = if (isMuted) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f) else MaterialTheme.colorScheme.primary,
                            inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainer,
                            disabledThumbColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                            disabledActiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                            disabledInactiveTrackColor = MaterialTheme.colorScheme.surfaceContainer
                        )
                    )
                }
            }
        }
    }
}
