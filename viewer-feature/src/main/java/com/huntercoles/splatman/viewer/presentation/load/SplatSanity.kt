package com.huntercoles.splatman.viewer.presentation.load

import com.huntercoles.splatman.splat.model.SplatCloud
import kotlin.math.abs
import kotlin.math.ln

/**
 * Refuses a decoded cloud the viewer cannot frame or draw, before the camera ever sees it: no splats, a value that
 * is not finite (NaN or infinity), a centre outside [MAX_COORD] metres or a splat larger than that. Random bytes that
 * happen to have a `.splat` file's size decode to exactly such a cloud; framing it put the orbit camera's eye on its
 * target and crashed the GL thread in `Mat4.lookAt`. Inside the bound a float still resolves the camera's
 * `MIN_DISTANCE` (its ulp at 1e5 is under 0.01 m), so eye and target never collapse.
 */
object SplatSanity {
    const val MAX_COORD = 1e5f
    private val MAX_LOG_SCALE = ln(MAX_COORD)

    /** Throws [SplatFileException] with a message for the user when [cloud] cannot be shown. */
    fun requireUsable(cloud: SplatCloud) {
        if (cloud.count <= 0) throw SplatFileException("The file holds no splats.")
        val n = cloud.count
        for (i in 0 until 3 * n) {
            val p = cloud.positions[i]
            if (!p.isFinite()) throw broken("a position that is not a number")
            if (abs(p) > MAX_COORD) throw broken("a position over ${MAX_COORD.toInt() / 1000} km away")
            val s = cloud.logScales[i]
            if (!s.isFinite()) throw broken("a scale that is not a number")
            if (s > MAX_LOG_SCALE) throw broken("a splat over ${MAX_COORD.toInt() / 1000} km wide")
            if (!cloud.shDc[i].isFinite()) throw broken("a colour that is not a number")
        }
        for (i in 0 until 4 * n) {
            if (!cloud.rotations[i].isFinite()) throw broken("a rotation that is not a number")
        }
        for (i in 0 until n) {
            if (!cloud.opacityLogits[i].isFinite()) throw broken("an opacity that is not a number")
        }
        for (v in cloud.shRest) {
            if (!v.isFinite()) throw broken("a colour that is not a number")
        }
    }

    private fun broken(what: String) = SplatFileException("The file holds broken splats ($what).")
}
