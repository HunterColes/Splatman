package com.huntercoles.splatman.splat.edit

import com.huntercoles.splatman.splat.model.SplatCloud

/**
 * A cloud with the given centres (x, y, z per splat) and distinct, index-derived values in every other field, so a
 * test can tell which input splat each output slot came from.
 */
fun cloudAt(centres: FloatArray, shDegree: Int = 0): SplatCloud {
    require(centres.size % 3 == 0)
    val n = centres.size / 3
    val k = SplatCloud.restCoefficients(shDegree)
    return SplatCloud(
        count = n,
        shDegree = shDegree,
        positions = centres.copyOf(),
        logScales = FloatArray(3 * n) { j -> -1f - j * 0.01f },
        rotations = FloatArray(4 * n) { j -> 0.5f + j * 0.001f },
        opacityLogits = FloatArray(n) { j -> j * 0.25f - 3f },
        shDc = FloatArray(3 * n) { j -> j * 0.03f },
        shRest = FloatArray(n * 3 * k) { j -> j * 0.0007f - 1f },
    )
}
