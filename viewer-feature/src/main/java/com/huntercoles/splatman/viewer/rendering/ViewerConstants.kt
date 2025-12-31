package com.huntercoles.splatman.viewer.rendering

/**
 * Constants for 3D viewer camera controls and rendering
 */
object ViewerConstants {
    // Camera distance limits
    const val DEFAULT_DISTANCE = 5f
    const val MIN_DISTANCE = 0.5f
    const val MAX_DISTANCE = 1000f
    
    // Camera sensitivity
    const val ROTATION_SENSITIVITY = 0.1f
    const val PAN_SENSITIVITY = 0.01f
    
    // Camera field of view
    const val FOV_DEGREES = 60f
    const val NEAR_PLANE = 0.1f
    const val FAR_PLANE = 1000f
    
    // Rendering
    const val AXIS_LENGTH = 25f
    const val POINT_SIZE = 2.0f
    const val AXIS_ARROW_SIZE = 4f
}