package com.huntercoles.splatman.core.design

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Spacing, shape and size tokens (docs/design/tokens.md), on a 4 dp grid. */
object SplatDimens {
    // Spacing
    val SpacingXSmall: Dp = 4.dp
    val SpacingSmall: Dp = 8.dp
    val SpacingMedium: Dp = 12.dp
    val SpacingDefault: Dp = 16.dp
    val SpacingLarge: Dp = 24.dp
    val SpacingXLarge: Dp = 32.dp
    val SpacingXXLarge: Dp = 48.dp

    // Sizes
    /** Minimum size of anything tappable. */
    val TouchTarget: Dp = 48.dp

    /** The record / finish button. */
    val CaptureButton: Dp = 72.dp

    /** Coverage ring diameter on phones. */
    val CoverageRing: Dp = 112.dp

    /** Coverage ring diameter at 600 dp and up. */
    val CoverageRingWide: Dp = 136.dp

    val CoverageRingStroke: Dp = 8.dp

    /** Max width of library, settings and sheets on wide screens. */
    val ContentMaxWidth: Dp = 840.dp

    /** Adaptive grid minimum cell width in the library. */
    val LibraryCellMin: Dp = 160.dp

    // Shape (corner radius); full rounding is CircleShape
    val CornerXSmall: Dp = 4.dp
    val CornerSmall: Dp = 8.dp
    val CornerMedium: Dp = 12.dp
    val CornerDefault: Dp = 16.dp
    val CornerLarge: Dp = 28.dp
}
