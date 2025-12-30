package com.huntercoles.splatman.library.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.huntercoles.splatman.core.design.SplatColors
import com.huntercoles.splatman.core.design.SplatDimens
import com.huntercoles.splatman.library.data.assets.AssetsModelManager
import com.huntercoles.splatman.library.data.loader.Model3DConverter
import com.huntercoles.splatman.library.domain.model.SplatScene
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.Date

/**
 * Grid view for internal (bundled) models from assets folder
 * 
 * To add models: place .ply, .stl, or .obj files in app/src/main/assets/models/
 */
@Composable
fun InternalModelsGrid(
    onModelClick: (SplatScene) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val assetsManager = remember { AssetsModelManager(context) }
    var availableFiles by remember { mutableStateOf<List<String>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    
    // List available model files in assets
    LaunchedEffect(Unit) {
        scope.launch {
            isLoading = true
            availableFiles = withContext(Dispatchers.IO) {
                try {
                    assetsManager.listAvailableModels()
                } catch (e: Exception) {
                    Timber.e(e, "Failed to list internal models")
                    emptyList()
                }
            }
            isLoading = false
        }
    }
    
    if (isLoading) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(color = SplatColors.SplatGold)
        }
        return
    }
    
    if (availableFiles.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(24.dp)
            ) {
                Text(
                    text = "📁 No Internal Models Found",
                    color = SplatColors.SplatGold,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "To add models:\n\n1. Place your .ply, .stl, or .obj files in:\n   app/src/main/assets/models/\n\n2. Rebuild the app\n\n3. Models will appear here",
                    color = SplatColors.SplatGold.copy(alpha = 0.7f),
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 20.sp
                )
            }
        }
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(SplatDimens.SpacingDefault),
        horizontalArrangement = Arrangement.spacedBy(SplatDimens.SpacingSmall),
        verticalArrangement = Arrangement.spacedBy(SplatDimens.SpacingSmall),
        modifier = modifier.fillMaxSize()
    ) {
        items(availableFiles) { fileName ->
            InternalModelCard(
                fileName = fileName,
                onClick = {
                    // Load model in background and convert to SplatScene
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            try {
                                assetsManager.loadModel(fileName)
                                    .onSuccess { model3D ->
                                        val scene = Model3DConverter.toSplatScene(model3D)
                                        withContext(Dispatchers.Main) {
                                            onModelClick(scene)
                                        }
                                    }
                                    .onFailure { error ->
                                        Timber.e(error, "Failed to load model: $fileName")
                                    }
                            } catch (e: Exception) {
                                Timber.e(e, "Error loading model: $fileName")
                            }
                        }
                    }
                }
            )
        }
    }
}

/**
 * Individual model card for internal models from assets
 */
@Composable
private fun InternalModelCard(
    fileName: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val displayName = fileName.removeSuffix(".ply").removeSuffix(".stl").removeSuffix(".obj")
    val extension = fileName.substringAfterLast(".")
    
    Card(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(SplatDimens.CornerSmall))
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = SplatColors.DarkPurple.copy(alpha = 0.8f)
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(SplatDimens.SpacingMedium),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // File type indicator
                Box(
                    modifier = Modifier
                        .size(SplatDimens.ModelGridItemSize)
                        .background(
                            SplatColors.MediumPurple.copy(alpha = 0.5f),
                            RoundedCornerShape(SplatDimens.CornerXSmall)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = extension.uppercase(),
                        color = SplatColors.SplatGold,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(SplatDimens.SpacingSmall))

                Text(
                    text = displayName,
                    color = SplatColors.SplatGold,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    maxLines = 2
                )

                Text(
                    text = "Internal",
                    color = SplatColors.SplatGold.copy(alpha = 0.5f),
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/**
 * Grid view for external (user-loaded) models
 */
@Composable
fun ExternalModelsGrid(
    scenes: List<SplatScene>,
    selectedScene: SplatScene?,
    onModelClick: (SplatScene) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(SplatDimens.SpacingDefault),
        horizontalArrangement = Arrangement.spacedBy(SplatDimens.SpacingSmall),
        verticalArrangement = Arrangement.spacedBy(SplatDimens.SpacingSmall),
        modifier = modifier.fillMaxSize()
    ) {
        items(scenes) { scene ->
            ModelCard(
                scene = scene,
                isSelected = scene.id == selectedScene?.id,
                onClick = { onModelClick(scene) }
            )
        }
    }
}

/**
 * Individual model card for the grid
 */
@Composable
private fun ModelCard(
    scene: SplatScene,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(SplatDimens.CornerSmall))
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                SplatColors.SplatGold.copy(alpha = 0.2f)
            else
                SplatColors.DarkPurple.copy(alpha = 0.8f)
        ),
        border = if (isSelected) androidx.compose.foundation.BorderStroke(
            SplatDimens.BorderMedium,
            SplatColors.SplatGold
        ) else null
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(SplatDimens.SpacingMedium),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // Placeholder for thumbnail (would show actual preview)
                Box(
                    modifier = Modifier
                        .size(SplatDimens.ModelGridItemSize)
                        .background(
                            SplatColors.MediumPurple.copy(alpha = 0.5f),
                            RoundedCornerShape(SplatDimens.CornerXSmall)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "3D",
                        color = SplatColors.SplatGold,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(SplatDimens.SpacingSmall))

                Text(
                    text = scene.name,
                    color = SplatColors.SplatGold,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    maxLines = 2
                )

                if (scene.gaussians.isNotEmpty()) {
                    Text(
                        text = "${scene.gaussians.size} splats",
                        color = SplatColors.SplatGold.copy(alpha = 0.7f),
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

/**
 * Create a sample scene for internal models
 * In a real app, these would load actual PLY data from assets
 */
private fun createSampleScene(name: String, description: String): SplatScene {
    return SplatScene(
        name = name,
        gaussians = emptyList(), // Would be loaded from bundled PLY file
        cameraIntrinsics = null, // Sample scenes don't have camera intrinsics
        boundingBox = com.huntercoles.splatman.library.domain.model.BoundingBox(
            min = floatArrayOf(-1f, -1f, -1f),
            max = floatArrayOf(1f, 1f, 1f)
        ),
        createdAt = Date(),
        modifiedAt = Date()
    )
}