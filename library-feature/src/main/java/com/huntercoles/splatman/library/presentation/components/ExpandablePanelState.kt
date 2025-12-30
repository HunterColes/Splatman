package com.huntercoles.splatman.library.presentation.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.huntercoles.splatman.core.design.SplatDimens
import kotlinx.coroutines.launch

/**
 * State management for expandable bottom panel
 * 
 * Handles drag gestures, expansion/collapse animations, and state transitions.
 * Separated from UI for testability.
 * 
 * @param collapsedHeight Height when panel is collapsed
 * @param expandedHeight Height when panel is fully expanded
 */
@Stable
class ExpandablePanelState(
    private val collapsedHeight: Dp = SplatDimens.ExpandablePanelCollapsedHeight,
    private val expandedHeight: Dp = SplatDimens.ExpandablePanelExpandedHeight
) {
    // Current height of the panel (animated)
    private val _heightPx = Animatable(collapsedHeight.value)
    val currentHeight: Dp
        get() = _heightPx.value.dp
    
    // Is panel currently expanded?
    var isExpanded by mutableStateOf(false)
        private set
    
    // Is animation in progress?
    var isAnimating by mutableStateOf(false)
        private set
    
    /**
     * Expand the panel
     */
    suspend fun expand() {
        if (isExpanded) return
        
        isAnimating = true
        isExpanded = true
        
        _heightPx.animateTo(
            targetValue = expandedHeight.value,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessLow
            )
        )
        
        isAnimating = false
    }
    
    /**
     * Collapse the panel
     */
    suspend fun collapse() {
        if (!isExpanded) return
        
        isAnimating = true
        isExpanded = false
        
        _heightPx.animateTo(
            targetValue = collapsedHeight.value,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessLow
            )
        )
        
        isAnimating = false
    }
    
    /**
     * Toggle between expanded and collapsed
     */
    suspend fun toggle() {
        if (isExpanded) {
            collapse()
        } else {
            expand()
        }
    }
    
    /**
     * Handle drag gesture
     * @param dragAmount Vertical drag distance (negative = up, positive = down)
     * @return True if drag was consumed
     */
    suspend fun onDrag(dragAmount: Float): Boolean {
        val newHeight = (_heightPx.value - dragAmount).coerceIn(
            collapsedHeight.value,
            expandedHeight.value
        )
        
        _heightPx.snapTo(newHeight)
        return true
    }
    
    /**
     * Handle drag end (snap to nearest state)
     */
    suspend fun onDragEnd(velocity: Float) {
        val currentHeightValue = _heightPx.value
        val threshold = (collapsedHeight.value + expandedHeight.value) / 2f
        
        // Determine target state based on current position and velocity
        val shouldExpand = when {
            // Strong upward fling
            velocity < -500f -> true
            // Strong downward fling
            velocity > 500f -> false
            // Based on position
            else -> currentHeightValue > threshold
        }
        
        if (shouldExpand) {
            expand()
        } else {
            collapse()
        }
    }
    
    /**
     * Get expansion progress [0.0 - 1.0]
     */
    fun getExpansionProgress(): Float {
        val range = expandedHeight.value - collapsedHeight.value
        return ((_heightPx.value - collapsedHeight.value) / range).coerceIn(0f, 1f)
    }
}

/**
 * Remember expandable panel state across recompositions
 */
@Composable
fun rememberExpandablePanelState(
    collapsedHeight: Dp = SplatDimens.ExpandablePanelCollapsedHeight,
    expandedHeight: Dp = SplatDimens.ExpandablePanelExpandedHeight
): ExpandablePanelState {
    return remember {
        ExpandablePanelState(collapsedHeight, expandedHeight)
    }
}
