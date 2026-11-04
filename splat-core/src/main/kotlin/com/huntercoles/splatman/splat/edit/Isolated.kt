@file:Suppress("MatchingDeclarationName") // the card names the file after markIsolated, not IsolatedRule

package com.huntercoles.splatman.splat.edit

import com.huntercoles.splatman.splat.model.SplatCloud
import kotlin.math.floor
import kotlin.math.max

/** Constants of "Remove isolated splats" (ADR 0014 rule 8). */
object IsolatedRule {
    /** The cell is the longest extent of `CropBox.fitCentral` divided by this. */
    const val CELLS_PER_EXTENT = 64

    /** Smallest cell edge, metres. */
    const val MIN_CELL = 0.001f

    /** A splat whose 27 surrounding cells hold fewer centres than this (itself included) is isolated. */
    const val MIN_NEIGHBOURS = 4

    /** Most dense-grid cells per axis (128^3 ints = 8 MB). */
    const val GRID_MAX = 128
}

/**
 * Marks every isolated splat of [cloud] in [mask] as one new stroke (so one Undo clears the pass) and returns how
 * many it newly marked. Splats with a non-finite centre are left alone. Two O(n) passes: count centres per cell
 * (a dense IntArray over the fitCentral box grown by half its extent each side, a hash for cells outside), then
 * sum the 27 cells around each splat.
 */
fun markIsolated(cloud: SplatCloud, mask: EraseMask): Int {
    require(mask.count == cloud.count) { "mask has ${mask.count} splats, cloud has ${cloud.count}" }
    mask.beginStroke()
    val n = cloud.count
    val p = cloud.positions
    fun finite(i: Int) = p[3 * i].isFinite() && p[3 * i + 1].isFinite() && p[3 * i + 2].isFinite()
    if ((0 until n).none { finite(it) }) return 0
    val box = CropBox.fitCentral(cloud)
    val h = box.halfExtents
    val c = box.centre
    val cell = max(2f * max(h.x, max(h.y, h.z)) / IsolatedRule.CELLS_PER_EXTENT, IsolatedRule.MIN_CELL)
    val inv = 1f / cell

    fun cellOf(v: Float): Int = floor(v.toDouble() * inv).coerceIn(-1e6, 1e6).toInt()

    // The box grown by half its extent each side spans centre +- 2 * half.
    val ox = cellOf(c.x - 2f * h.x)
    val oy = cellOf(c.y - 2f * h.y)
    val oz = cellOf(c.z - 2f * h.z)
    val gx = (cellOf(c.x + 2f * h.x) - ox + 1).coerceIn(1, IsolatedRule.GRID_MAX)
    val gy = (cellOf(c.y + 2f * h.y) - oy + 1).coerceIn(1, IsolatedRule.GRID_MAX)
    val gz = (cellOf(c.z + 2f * h.z) - oz + 1).coerceIn(1, IsolatedRule.GRID_MAX)
    val grid = IntArray(gx * gy * gz)
    val outside = HashMap<Long, Int>()

    fun key(cx: Int, cy: Int, cz: Int): Long =
        (cx.toLong() and 0x1FFFFF) or ((cy.toLong() and 0x1FFFFF) shl 21) or ((cz.toLong() and 0x1FFFFF) shl 42)

    fun index(cx: Int, cy: Int, cz: Int): Int {
        val x = cx - ox
        val y = cy - oy
        val z = cz - oz
        return if (x in 0 until gx && y in 0 until gy && z in 0 until gz) (z * gy + y) * gx + x else -1
    }

    fun countAt(cx: Int, cy: Int, cz: Int): Int {
        val g = index(cx, cy, cz)
        return if (g >= 0) grid[g] else outside[key(cx, cy, cz)] ?: 0
    }

    for (i in 0 until n) {
        if (!finite(i)) continue
        val cx = cellOf(p[3 * i])
        val cy = cellOf(p[3 * i + 1])
        val cz = cellOf(p[3 * i + 2])
        val g = index(cx, cy, cz)
        if (g >= 0) grid[g]++ else outside.merge(key(cx, cy, cz), 1, Int::plus)
    }

    var newly = 0
    for (i in 0 until n) {
        if (!finite(i)) continue
        val cx = cellOf(p[3 * i])
        val cy = cellOf(p[3 * i + 1])
        val cz = cellOf(p[3 * i + 2])
        var sum = 0
        var dz = -1
        while (dz <= 1 && sum < IsolatedRule.MIN_NEIGHBOURS) {
            for (dy in -1..1) for (dx in -1..1) sum += countAt(cx + dx, cy + dy, cz + dz)
            dz++
        }
        if (sum < IsolatedRule.MIN_NEIGHBOURS && mask.markState(i)) newly++
    }
    mask.commit(newly)
    return newly
}
