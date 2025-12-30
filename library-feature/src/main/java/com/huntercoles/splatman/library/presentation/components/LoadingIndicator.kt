package com.huntercoles.splatman.library.presentation.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.huntercoles.splatman.core.design.SplatDimens
import androidx.compose.ui.unit.sp
import com.huntercoles.splatman.core.design.SplatColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Loading indicator with percentage for PLY file loading
 */
@Composable
fun LoadingIndicator(
    progress: Float, // 0.0 to 1.0
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.size(SplatDimens.LoadingIndicatorSize),
        contentAlignment = Alignment.Center
    ) {
        // Background circle
        Canvas(modifier = Modifier.size(SplatDimens.LoadingIndicatorArcSize)) {
            drawArc(
                color = SplatColors.DarkPurple.copy(alpha = 0.3f),
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                style = Stroke(width = 8f, cap = StrokeCap.Round)
            )
        }
        
        // Progress arc
        Canvas(modifier = Modifier.size(100.dp)) {
            drawArc(
                color = SplatColors.SplatGold,
                startAngle = -90f,
                sweepAngle = progress * 360f,
                useCenter = false,
                style = Stroke(width = 8f, cap = StrokeCap.Round)
            )
        }
        
        // Percentage text
        Text(
            text = "${(progress * 100).toInt()}%",
            color = SplatColors.SplatGold,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )
    }
}