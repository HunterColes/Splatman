package com.huntercoles.splatman.capture.preview

import android.view.Surface

/**
 * Where the tracker draws the live camera: a window [surface] of [width] x [height] pixels, shown at display
 * [rotation] (`Surface.ROTATION_*`). Valid until the view's `surfaceDestroyed`.
 */
data class PreviewTarget(
    val surface: Surface,
    val width: Int,
    val height: Int,
    val rotation: Int,
)
