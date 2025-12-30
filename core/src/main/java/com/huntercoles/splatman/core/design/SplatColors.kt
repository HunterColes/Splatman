package com.huntercoles.splatman.core.design

import androidx.compose.ui.graphics.Color

/**
 * Splatman-themed color palette for consistent UI across the app
 * Dark purple theme for 3D Gaussian splatting scanner
 */
object SplatColors {
    // Dark purple shades for Splatman theme
    val DeepPurple = Color(0xFF1A0A2E)       // Deep purple (darkest)
    val DarkPurple = Color(0xFF2D1B4E)       // Dark purple
    val MediumPurple = Color(0xFF4A148C)     // Medium purple
    val LightPurple = Color(0xFF6A1B9A)      // Light purple
    val AccentPurple = Color(0xFF9C27B0)     // Bright purple accent
    
    // Splat gold accent colors
    val SplatGold = Color(0xFFFFD700)        // Primary gold
    val DarkGold = Color(0xFFB8860B)         // Darker gold for hover states
    val LightGold = Color(0xFFFFF8DC)        // Light gold for subtle accents
    
    // Text and UI colors
    val CardWhite = Color(0xFFF5F5F5)        // Card white
    val TextSecondary = Color(0xFFE0E0E0)    // Secondary text
    val ErrorRed = Color(0xFFDC143C)         // Error color
    val SuccessGreen = Color(0xFF32CD32)     // Success color
    
    // Black background
    val SplatBlack = Color(0xFF0B0B0B)       // Deep black for main background
    
    // Background variants
    val BackgroundPrimary = DeepPurple       // Main background
    val BackgroundSecondary = DarkPurple     // Cards and sections
    val BackgroundTertiary = MediumPurple    // Elevated elements
    
    // Surface variants for cards
    val SurfacePrimary = DarkPurple
    val SurfaceSecondary = LightPurple
    val SurfaceTertiary = MediumPurple

    // Axis widget colors (RGB for X/Y/Z axes)
    val AxisRed = Color(0xFFFF4444)         // X axis (right)
    val AxisGreen = Color(0xFF44FF44)       // Y axis (up)  
    val AxisBlue = Color(0xFF4169E1)        // Z axis (forward) - Royal blue

    // Standardized alpha values used across the UI
    // Use these constants instead of magic number alpha literals for consistency
    const val SplatPausedAlpha = 0.7f
    const val SplatPausedBackgroundAlpha = 0.3f
}
