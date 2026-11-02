package com.huntercoles.splatman.viewer.rendering.gl

import com.huntercoles.splatman.splat.math.Vec3
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Where the orbit camera starts for a loaded cloud: [target] is the mean of the finite centres, [radius] the
 * distance from it that [COVERAGE] of them lie within (so a few stray floaters do not shrink the scene to a dot),
 * and [distance] puts a sphere of that radius inside the vertical field of view.
 */
data class SceneFraming(val target: Vec3, val radius: Float, val distance: Float) {
    companion object {
        const val COVERAGE = 0.9
        const val MIN_RADIUS = 1e-3f
        private const val SAMPLE = 8192

        fun of(positions: FloatArray, count: Int, fovYDegrees: Float): SceneFraming {
            var sx = 0.0
            var sy = 0.0
            var sz = 0.0
            var n = 0
            for (i in 0 until count) {
                val x = positions[3 * i]
                val y = positions[3 * i + 1]
                val z = positions[3 * i + 2]
                if (x.isFinite() && y.isFinite() && z.isFinite()) {
                    sx += x
                    sy += y
                    sz += z
                    n++
                }
            }
            val half = Math.toRadians(fovYDegrees / 2.0)
            if (n == 0) return SceneFraming(Vec3.ZERO, 1f, (1.0 / sin(half)).toFloat())
            val cx = sx / n
            val cy = sy / n
            val cz = sz / n
            val step = max(1, count / SAMPLE)
            val d = ArrayList<Double>(count / step + 1)
            var i = 0
            while (i < count) {
                val dx = positions[3 * i] - cx
                val dy = positions[3 * i + 1] - cy
                val dz = positions[3 * i + 2] - cz
                val r = sqrt(dx * dx + dy * dy + dz * dz)
                if (r.isFinite()) d.add(r)
                i += step
            }
            d.sort()
            val r = if (d.isEmpty()) 1.0 else d[((d.size - 1) * COVERAGE).toInt()]
            val radius = max(r.toFloat(), MIN_RADIUS)
            return SceneFraming(
                Vec3(cx.toFloat(), cy.toFloat(), cz.toFloat()),
                radius,
                (radius / sin(half)).toFloat(),
            )
        }
    }
}
