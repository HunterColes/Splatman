package com.huntercoles.splatman.splat.edit

import com.huntercoles.splatman.splat.model.SplatCloud

/**
 * A new cloud holding exactly the splats of [cloud] whose centres lie inside [box] ([CropBox.contains]), in their
 * input order, with every per-splat field and the SH degree carried in fresh arrays; [cloud] is untouched. Two
 * passes (count, then copy) with no per-splat allocation. Pure: run it off the main thread for large clouds.
 */
fun cropToBox(cloud: SplatCloud, box: CropBox): SplatCloud {
    val n = cloud.count
    val pos = cloud.positions
    var kept = 0
    for (i in 0 until n) {
        if (box.inside(pos[3 * i], pos[3 * i + 1], pos[3 * i + 2])) kept++
    }
    val rest = 3 * cloud.shRestPerChannel
    val positions = FloatArray(3 * kept)
    val logScales = FloatArray(3 * kept)
    val rotations = FloatArray(4 * kept)
    val opacityLogits = FloatArray(kept)
    val shDc = FloatArray(3 * kept)
    val shRest = FloatArray(rest * kept)
    var o = 0
    for (i in 0 until n) {
        if (!box.inside(pos[3 * i], pos[3 * i + 1], pos[3 * i + 2])) continue
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
