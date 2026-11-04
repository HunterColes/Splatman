package com.huntercoles.splatman.splat.edit

/**
 * Marks in [mask] every splat in [range] whose centre is under the brush (ADR 0014 rule 1) and returns how
 * many were newly marked. A centre is projected with [viewProj] (16 floats, column-major, clip = viewProj * (p, 1))
 * to surface pixels of a [widthPx] x [heightPx] surface (x right, y down, the touch frame); it is hit when clip
 * w > 0 and its distance to the nearest segment of the polyline [points] (x,y pairs in surface pixels) is at most
 * [radiusPx], inclusive. One point is a circle. Every depth is hit. Splats already marked keep their state.
 *
 * One pass over [positions], no allocation per splat. The caller may split `0 until count` into disjoint
 * [range] chunks and run them in parallel on one mask: each chunk writes only its own bytes and adds its
 * count to the mask once at the end.
 */
fun markUnderBrush(
    positions: FloatArray,
    viewProj: FloatArray,
    widthPx: Int,
    heightPx: Int,
    points: FloatArray,
    radiusPx: Float,
    mask: EraseMask,
    range: IntRange = 0 until mask.count,
): Int {
    require(viewProj.size >= 16) { "viewProj needs 16 floats" }
    require(range.isEmpty() || (range.first >= 0 && range.last < mask.count)) { "bad range $range of ${mask.count}" }
    val nPoints = points.size / 2
    if (nPoints == 0 || radiusPx < 0f) return 0
    val m0 = viewProj[0]
    val m1 = viewProj[1]
    val m3 = viewProj[3]
    val m4 = viewProj[4]
    val m5 = viewProj[5]
    val m7 = viewProj[7]
    val m8 = viewProj[8]
    val m9 = viewProj[9]
    val m11 = viewProj[11]
    val m12 = viewProj[12]
    val m13 = viewProj[13]
    val m15 = viewProj[15]
    val halfW = widthPx * 0.5f
    val halfH = heightPx * 0.5f
    val r2 = radiusPx * radiusPx
    var newly = 0
    for (i in range) {
        val x = positions[3 * i]
        val y = positions[3 * i + 1]
        val z = positions[3 * i + 2]
        val cw = m3 * x + m7 * y + m11 * z + m15
        if (!(cw > 0f)) continue
        val cx = m0 * x + m4 * y + m8 * z + m12
        val cy = m1 * x + m5 * y + m9 * z + m13
        val px = (cx / cw + 1f) * halfW
        val py = (1f - cy / cw) * halfH
        if (underPolyline(px, py, points, nPoints, r2) && mask.markState(i)) newly++
    }
    mask.commit(newly)
    return newly
}

/** True when (px, py) lies within sqrt([r2]) of a segment of the polyline (a lone point is a circle). */
private fun underPolyline(px: Float, py: Float, points: FloatArray, nPoints: Int, r2: Float): Boolean {
    if (nPoints == 1) {
        val dx = px - points[0]
        val dy = py - points[1]
        return dx * dx + dy * dy <= r2
    }
    for (s in 0 until nPoints - 1) {
        val ax = points[2 * s]
        val ay = points[2 * s + 1]
        val ex = points[2 * s + 2] - ax
        val ey = points[2 * s + 3] - ay
        val qx = px - ax
        val qy = py - ay
        val len2 = ex * ex + ey * ey
        var t = if (len2 > 0f) (qx * ex + qy * ey) / len2 else 0f
        if (t < 0f) t = 0f else if (t > 1f) t = 1f
        val dx = qx - t * ex
        val dy = qy - t * ey
        if (dx * dx + dy * dy <= r2) return true
    }
    return false
}
