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
    
    // Viewer motion
    /** One TalkBack or keyboard orbit step (degrees). */
    const val ORBIT_STEP_DEG = 15f

    /** One TalkBack or keyboard zoom step (distance factor). */
    const val ZOOM_STEP = 1.25f

    /** Double-tap reset animation length (seconds; SplatMotion.DurationLong). */
    const val RESET_SECONDS = 0.5f

    /** Fling velocity decays as exp(-FLING_FRICTION * t); it stops below FLING_STOP (controller units/s). */
    const val FLING_FRICTION = 3f
    const val FLING_STOP = 0.05f

    /** Auto-rotate speed: a full turn in about 20 s. */
    const val AUTO_ROTATE_RAD_PER_S = 0.3f

    // Erase marked look (docs/design/screens/erase.md, "The marked look"; ADR 0014 rule 4)
    /** Tint mixed into a marked splat's colour, 0xRRGGBB (`ERASE_TINT = #FF3D71`). */
    const val ERASE_TINT = 0xFF3D71

    /** How much of [ERASE_TINT] replaces a marked splat's colour (`ERASE_TINT_MIX`). */
    const val ERASE_TINT_MIX = 0.6f

    /** A marked splat's alpha factor (`ERASE_ALPHA`). */
    const val ERASE_ALPHA = 0.5f
}
