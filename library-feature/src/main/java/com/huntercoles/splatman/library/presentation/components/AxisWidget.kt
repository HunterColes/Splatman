package com.huntercoles.splatman.library.presentation.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.huntercoles.splatman.core.design.SplatColors
import com.huntercoles.splatman.core.design.SplatDimens
import com.huntercoles.splatman.viewer.rendering.ViewerConstants
import com.huntercoles.splatman.viewer.rendering.GaussianCameraController
import com.huntercoles.splatman.viewer.rendering.math.Vector3
import kotlinx.coroutines.delay

/**
 * 3D Axis widget overlay (top-left corner)
 * 
 * Shows X/Y/Z axes that rotate with camera orientation:
 * - Red: X axis (right)
 * - Green: Y axis (up)
 * - Blue: Z axis (forward)
 * 
 * Updates in real-time as user rotates the scene
 * 
 * @param cameraController Camera controller to read orientation from
 * @param modifier Modifier
 */
@Composable
fun AxisWidget(
    cameraController: GaussianCameraController,
    modifier: Modifier = Modifier
) {
    // Force recomposition for axis rotation updates
    var tick by remember { mutableStateOf(0) }
    
    LaunchedEffect(Unit) {
        while (true) {
            delay(16) // ~60fps update
            tick++
        }
    }
    
    Box(
        modifier = modifier
            .padding(SplatDimens.SpacingDefault)
            .size(SplatDimens.AxisWidgetSize)
    ) {
        Box(
            modifier = Modifier
                .size(SplatDimens.AxisWidgetSize)
                .background(
                    color = SplatColors.DeepPurple.copy(alpha = 0.8f),
                    shape = RoundedCornerShape(SplatDimens.CornerSmall)
                )
                .border(
                    width = SplatDimens.BorderThin,
                    color = SplatColors.SplatGold.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(SplatDimens.CornerSmall)
                ),
            contentAlignment = Alignment.Center
        ) {
            Canvas(
                modifier = Modifier.size(SplatDimens.AxisWidgetSize - SplatDimens.AxisWidgetPadding)
            ) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val axisLength = ViewerConstants.AXIS_LENGTH.dp.toPx()
                
                // Get camera rotation and transform axis vectors
                val rotation = cameraController.getRotation()
                
                // Base axis vectors
                val xAxis = rotation.transform(Vector3.right())
                val yAxis = rotation.transform(Vector3.up())
                val zAxis = rotation.transform(Vector3.forward())
                
                // Project to 2D (simple orthographic projection)
                fun projectAxis(axis: Vector3): Offset {
                    // Simple 2D projection: use X and Y, ignore Z (depth)
                    return Offset(
                        center.x + axis.x * axisLength,
                        center.y - axis.y * axisLength // Flip Y for screen coords
                    )
                }
                
                val xEnd = projectAxis(xAxis)
                val yEnd = projectAxis(yAxis)
                val zEnd = projectAxis(zAxis)
                
                // Draw axes (Z first for depth ordering)
                // Z axis (blue)
                drawLine(
                    color = SplatColors.AxisBlue,
                    start = center,
                    end = zEnd,
                    strokeWidth = 3.dp.toPx(),
                    cap = StrokeCap.Round
                )
                drawArrowHead(zEnd, center, SplatColors.AxisBlue)
                
                // X axis (red)
                drawLine(
                    color = SplatColors.AxisRed,
                    start = center,
                    end = xEnd,
                    strokeWidth = 3.dp.toPx(),
                    cap = StrokeCap.Round
                )
                drawArrowHead(xEnd, center, SplatColors.AxisRed)
                
                // Y axis (green)
                drawLine(
                    color = SplatColors.AxisGreen,
                    start = center,
                    end = yEnd,
                    strokeWidth = 3.dp.toPx(),
                    cap = StrokeCap.Round
                )
                drawArrowHead(yEnd, center, SplatColors.AxisGreen)
            }
            
            // Labels
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = SplatDimens.AxisWidgetPadding),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "XYZ",
                    fontSize = 8.sp,
                    color = SplatColors.TextSecondary,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/**
 * Draw arrow head at end of axis line
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawArrowHead(
    end: Offset,
    start: Offset,
    color: Color
) {
    val arrowSize = ViewerConstants.AXIS_ARROW_SIZE.dp.toPx()
    val angle = kotlin.math.atan2((end.y - start.y).toDouble(), (end.x - start.x).toDouble())
    
    val path = Path().apply {
        moveTo(end.x, end.y)
        lineTo(
            (end.x - arrowSize * kotlin.math.cos(angle - Math.PI / 6)).toFloat(),
            (end.y - arrowSize * kotlin.math.sin(angle - Math.PI / 6)).toFloat()
        )
        moveTo(end.x, end.y)
        lineTo(
            (end.x - arrowSize * kotlin.math.cos(angle + Math.PI / 6)).toFloat(),
            (end.y - arrowSize * kotlin.math.sin(angle + Math.PI / 6)).toFloat()
        )
    }
    
    drawPath(
        path = path,
        color = color,
        style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
    )
}
