package com.huntercoles.splatman.viewer.rendering.gl

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * When a new back-to-front order is worth computing (ADR 0006): the view direction turned by more than
 * [ANGLE_DEGREES] or the eye moved by more than [MOVE_FRACTION] of the scene radius since the last sort.
 * Views are column-major world-to-camera matrices (GL camera, looking down -Z). Pure state.
 */
class ResortPolicy(private val sceneRadius: Float = 1f) {
    private var sorted = false
    private val forward = FloatArray(3)
    private val eye = FloatArray(3)

    /** True when [view] needs a fresh sort; the caller then sorts and calls [markSorted]. */
    fun needsSort(view: FloatArray): Boolean {
        if (!sorted) return true
        val f = forwardOf(view)
        val dot = (f[0] * forward[0] + f[1] * forward[1] + f[2] * forward[2]).toDouble()
        if (dot < COS_ANGLE) return true
        val e = eyeOf(view)
        val dx = e[0] - eye[0]
        val dy = e[1] - eye[1]
        val dz = e[2] - eye[2]
        return sqrt(dx * dx + dy * dy + dz * dz) > MOVE_FRACTION * sceneRadius
    }

    fun markSorted(view: FloatArray) {
        forwardOf(view).copyInto(forward)
        eyeOf(view).copyInto(eye)
        sorted = true
    }

    companion object {
        const val ANGLE_DEGREES = 0.5
        const val MOVE_FRACTION = 1e-3f
        private val COS_ANGLE = cos(ANGLE_DEGREES * PI / 180.0)

        /** The camera's viewing direction in world: minus the third row of the rotation (unit for a rigid view). */
        fun forwardOf(view: FloatArray): FloatArray = floatArrayOf(-view[2], -view[6], -view[10])

        /** The eye in world: `-R^T t` of a rigid view. */
        fun eyeOf(view: FloatArray): FloatArray {
            val tx = view[12]
            val ty = view[13]
            val tz = view[14]
            return floatArrayOf(
                -(view[0] * tx + view[1] * ty + view[2] * tz),
                -(view[4] * tx + view[5] * ty + view[6] * tz),
                -(view[8] * tx + view[9] * ty + view[10] * tz),
            )
        }

        /** View-space depth of each centre (distance in front of the camera, larger = farther) into [out]. */
        fun depths(view: FloatArray, positions: FloatArray, count: Int, out: FloatArray) {
            val r0 = view[2]
            val r1 = view[6]
            val r2 = view[10]
            val t = view[14]
            for (i in 0 until count) {
                out[i] = -(r0 * positions[3 * i] + r1 * positions[3 * i + 1] + r2 * positions[3 * i + 2] + t)
            }
        }
    }
}
