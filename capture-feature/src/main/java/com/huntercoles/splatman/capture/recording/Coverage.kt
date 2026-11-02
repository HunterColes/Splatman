package com.huntercoles.splatman.capture.recording

import com.huntercoles.splatman.splat.math.Vec3
import com.huntercoles.splatman.splat.model.CameraPose
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * How far round the scan has gone: the share of [SECTORS] equal heading sectors (about world +Y) that some keyframe
 * looked along. A camera walking once round an object fills all of them. Views within about 17 degrees of straight
 * up or down have no useful heading and are not counted. Not thread-safe.
 */
class Coverage {
    private var mask = 0

    /** Covered share, 0..1. */
    val fraction: Float get() = Integer.bitCount(mask).toFloat() / SECTORS

    /** Counts the heading of a keyframe taken at [pose] (`T_W_Cgl`). */
    fun add(pose: CameraPose) {
        // The camera looks down its own -Z (Cgl, ADR 0003).
        val forward = pose.rotation.normalized().rotate(LOOK)
        if (sqrt(forward.x * forward.x + forward.z * forward.z) < MIN_HORIZONTAL) return
        val heading = atan2(forward.x, forward.z).toDouble() + PI
        val sector = floor(heading / (2 * PI) * SECTORS).toInt().coerceIn(0, SECTORS - 1)
        mask = mask or (1 shl sector)
    }

    /** Forgets every heading. */
    fun reset() {
        mask = 0
    }

    companion object {
        /** Heading sectors of 30 degrees. */
        const val SECTORS = 12
        private const val MIN_HORIZONTAL = 0.3f
        private val LOOK = Vec3(0f, 0f, -1f)
    }
}
