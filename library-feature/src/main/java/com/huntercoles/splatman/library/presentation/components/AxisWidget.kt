package com.huntercoles.splatman.library.presentation.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import com.huntercoles.splatman.core.design.SplatColors
import com.huntercoles.splatman.core.design.SplatDimens
import com.huntercoles.splatman.viewer.rendering.GaussianCameraController
import com.huntercoles.splatman.viewer.rendering.math.Vector3

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
 * @param gestureCounter Counter that increments on gestures to trigger updates
 * @param modifier Modifier
 */
@Composable
fun AxisWidget(
    cameraController: GaussianCameraController,
    gestureCounter: Int,
    modifier: Modifier = Modifier
) {
    // Use gestureCounter to trigger recomposition and get fresh rotation
    val rotation = remember(gestureCounter) { cameraController.getRotation() }

    Canvas(
        modifier = modifier
            .padding(SplatDimens.SpacingDefault)
            .size(90.dp) // 1.5x larger
    ) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val axisLength = 27f.dp.toPx() // 1.5x longer axes
        val strokeWidth = 2.5f.dp.toPx() // Slightly thicker lines

        // Transform world axes by camera rotation
        val xAxis = rotation.transform(Vector3.right())
        val yAxis = rotation.transform(Vector3.up())
        val zAxis = rotation.transform(Vector3.forward())

        // Project 3D axes to 2D screen space (orthographic projection)
        fun projectAxis(axis: Vector3): Offset {
            return Offset(
                center.x + axis.x * axisLength,
                center.y - axis.y * axisLength // Flip Y for screen coordinates
            )
        }

        val xEnd = projectAxis(xAxis)
        val yEnd = projectAxis(yAxis)
        val zEnd = projectAxis(zAxis)

        // Draw simple axis lines (Z first for depth ordering)
        // Z axis (blue)
        drawLine(
            color = SplatColors.AxisBlue,
            start = center,
            end = zEnd,
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round
        )

        // X axis (red)
        drawLine(
            color = SplatColors.AxisRed,
            start = center,
            end = xEnd,
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round
        )

        // Y axis (green)
        drawLine(
            color = SplatColors.AxisGreen,
            start = center,
            end = yEnd,
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round
        )
    }
}
