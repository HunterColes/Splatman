package com.huntercoles.splatman.splat.edit

import com.huntercoles.splatman.splat.model.SplatCloud

/**
 * A new cloud holding exactly the splats of [cloud] that [mask] leaves unmarked (state 0), in their input order,
 * with every per-splat field and the SH degree carried in fresh arrays; [cloud] is untouched (ADR 0014 rules 3, 7).
 * Mirrors [cropToBox]: two passes (count, then copy), no per-splat allocation. A full mask gives count 0; the
 * caller refuses that. Pure: run it off the main thread for large clouds.
 */
fun keepUnmarked(cloud: SplatCloud, mask: EraseMask): SplatCloud {
    require(mask.count == cloud.count) { "mask of ${mask.count} for a cloud of ${cloud.count}" }
    val n = cloud.count
    var kept = 0
    for (i in 0 until n) if (!mask.isMarked(i)) kept++
    val rest = 3 * cloud.shRestPerChannel
    val pos = cloud.positions
    val positions = FloatArray(3 * kept)
    val logScales = FloatArray(3 * kept)
    val rotations = FloatArray(4 * kept)
    val opacityLogits = FloatArray(kept)
    val shDc = FloatArray(3 * kept)
    val shRest = FloatArray(rest * kept)
    var o = 0
    for (i in 0 until n) {
        if (mask.isMarked(i)) continue
        System.arraycopy(pos, 3 * i, positions, 3 * o, 3)
        System.arraycopy(cloud.logScales, 3 * i, logScales, 3 * o, 3)
        System.arraycopy(cloud.rotations, 4 * i, rotations, 4 * o, 4)
        opacityLogits[o] = cloud.opacityLogits[i]
        System.arraycopy(cloud.shDc, 3 * i, shDc, 3 * o, 3)
        if (rest > 0) System.arraycopy(cloud.shRest, rest * i, shRest, rest * o, rest)
        o++
    }
    return SplatCloud(kept, cloud.shDegree, positions, logScales, rotations, opacityLogits, shDc, shRest)
}
