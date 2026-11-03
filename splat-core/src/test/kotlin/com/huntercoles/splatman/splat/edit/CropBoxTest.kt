package com.huntercoles.splatman.splat.edit

import com.huntercoles.splatman.splat.math.Quat
import com.huntercoles.splatman.splat.math.Vec3
import com.huntercoles.splatman.splat.math.assertQuat
import com.huntercoles.splatman.splat.math.assertVec
import com.huntercoles.splatman.splat.model.SplatCloud
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.math.PI
import kotlin.random.Random

/** #182: the crop box (ADR 0011), its inclusion rule, face moves and start fit. */
class CropBoxTest {

    // --- contains: the inclusion rule the crop uses ---

    @Test
    fun `A1 box at the origin keeps centres inside and drops centres outside`() {
        val box = CropBox(Vec3.ZERO, Vec3(1f, 2f, 0.5f), Quat.IDENTITY)
        assertTrue(box.contains(Vec3.ZERO))
        assertTrue(box.contains(Vec3(0.9f, -1.9f, 0.4f)))
        assertFalse(box.contains(Vec3(1.1f, 0f, 0f)))
        assertFalse(box.contains(Vec3(0f, -2.1f, 0f)))
        assertFalse(box.contains(Vec3(0f, 0f, 0.6f)))
    }

    @Test
    fun `A1 box off the origin tests p minus centre`() {
        val box = CropBox(Vec3(10f, -5f, 2f), Vec3(1f, 1f, 1f), Quat.IDENTITY)
        assertTrue(box.contains(Vec3(10.5f, -5.5f, 2.5f)))
        assertFalse(box.contains(Vec3.ZERO))
    }

    @Test
    fun `A1 rotated box 45 degrees about Y holds a point only when rotated`() {
        // Thin slab: half extents x 1, y 1, z 0.1. Rotated +45 deg about Y, local x -> (s, 0, -s), local z -> (s, 0, s),
        // s = 1/sqrt 2. (0.6, 0, -0.6): local x 0.849, local z 0 -> inside rotated, z 0.6 > 0.1 outside unrotated.
        // (0.9, 0, 0): local x 0.636, local z 0.636 > 0.1 -> outside rotated, inside unrotated.
        val half = Vec3(1f, 1f, 0.1f)
        val rotated = CropBox(Vec3.ZERO, half, Quat.fromAxisAngle(Vec3.UNIT_Y, (PI / 4).toFloat()))
        val aligned = CropBox(Vec3.ZERO, half, Quat.IDENTITY)
        val onDiagonal = Vec3(0.6f, 0f, -0.6f)
        val onX = Vec3(0.9f, 0f, 0f)
        assertTrue(rotated.contains(onDiagonal))
        assertFalse(aligned.contains(onDiagonal))
        assertFalse(rotated.contains(onX))
        assertTrue(aligned.contains(onX))
    }

    @Test
    fun `A1 boundary is inclusive on every face and corner`() {
        val box = CropBox(Vec3.ZERO, Vec3(0.5f, 0.25f, 2f), Quat.IDENTITY)
        assertTrue(box.contains(Vec3(0.5f, 0f, 0f)))
        assertTrue(box.contains(Vec3(-0.5f, 0f, 0f)))
        assertTrue(box.contains(Vec3(0f, 0.25f, 0f)))
        assertTrue(box.contains(Vec3(0f, -0.25f, 0f)))
        assertTrue(box.contains(Vec3(0f, 0f, 2f)))
        assertTrue(box.contains(Vec3(0f, 0f, -2f)))
        assertTrue(box.contains(Vec3(0.5f, -0.25f, 2f)))
        assertFalse(box.contains(Vec3(Math.nextUp(0.5f), 0f, 0f)))
    }

    @Test
    fun `H1 NaN centres are not inside`() {
        val huge = CropBox(Vec3.ZERO, Vec3(1e30f, 1e30f, 1e30f), Quat.IDENTITY)
        assertFalse(huge.contains(Vec3(Float.NaN, 0f, 0f)))
        assertFalse(huge.contains(Vec3(0f, Float.NaN, 0f)))
        assertFalse(huge.contains(Vec3(0f, 0f, Float.NaN)))
        assertFalse(huge.contains(Vec3(Float.NaN, Float.NaN, Float.NaN)))
    }

    @Test
    fun `H2 a zero-length rotation quaternion is rejected at construction`() {
        assertThrows<IllegalArgumentException> { CropBox(Vec3.ZERO, Vec3(1f, 1f, 1f), Quat(0f, 0f, 0f, 0f)) }
    }

    // --- moveFace ---

    private val start = CropBox(Vec3(1f, 2f, 3f), Vec3(0.5f, 0.75f, 1f), Quat.IDENTITY)

    /** Expected box after moving [face] by [delta], from face positions (lo, hi per axis), not from the code under test. */
    private fun expectedMove(box: CropBox, face: BoxFace, delta: Float): Pair<Vec3, Vec3> {
        val c = floatArrayOf(box.centre.x, box.centre.y, box.centre.z)
        val h = floatArrayOf(box.halfExtents.x, box.halfExtents.y, box.halfExtents.z)
        val axis = face.ordinal / 2
        val positive = face.ordinal % 2 == 1
        var lo = c[axis].toDouble() - h[axis]
        var hi = c[axis].toDouble() + h[axis]
        if (positive) hi += delta else lo -= delta
        if (hi - lo < 0.01) {
            if (positive) hi = lo + 0.01 else lo = hi - 0.01
        }
        c[axis] = ((lo + hi) / 2).toFloat()
        h[axis] = ((hi - lo) / 2).toFloat()
        return Vec3(c[0], c[1], c[2]) to Vec3(h[0], h[1], h[2])
    }

    @Test
    fun `face order is X_NEG X_POS Y_NEG Y_POS Z_NEG Z_POS`() {
        assertEquals(
            listOf("X_NEG", "X_POS", "Y_NEG", "Y_POS", "Z_NEG", "Z_POS"),
            BoxFace.entries.map { it.name },
        )
    }

    @Test
    fun `A3 moveFace out moves one face along its outward normal and keeps the opposite face`() {
        for (face in BoxFace.entries) {
            val moved = start.moveFace(face, 0.2f)
            val (centre, half) = expectedMove(start, face, 0.2f)
            assertVec(centre, moved.centre, 1e-5f)
            assertVec(half, moved.halfExtents, 1e-5f)
            assertQuat(Quat.IDENTITY, moved.rotation)
        }
    }

    @Test
    fun `A3 moveFace in with a negative delta shrinks from that face only`() {
        for (face in BoxFace.entries) {
            val moved = start.moveFace(face, -0.3f)
            val (centre, half) = expectedMove(start, face, -0.3f)
            assertVec(centre, moved.centre, 1e-5f)
            assertVec(half, moved.halfExtents, 1e-5f)
        }
    }

    @Test
    fun `A3 moveFace X_POS by hand`() {
        val moved = start.moveFace(BoxFace.X_POS, 0.2f)
        // x was [0.5, 1.5]; X_POS out by 0.2 -> [0.5, 1.7].
        assertVec(Vec3(1.1f, 2f, 3f), moved.centre, 1e-5f)
        assertVec(Vec3(0.6f, 0.75f, 1f), moved.halfExtents, 1e-5f)
    }

    @Test
    fun `A3 moveFace clamps the extent to 1 cm with the opposite face fixed`() {
        for (face in BoxFace.entries) {
            val moved = start.moveFace(face, -50f)
            val (centre, half) = expectedMove(start, face, -50f)
            assertVec(centre, moved.centre, 1e-5f)
            assertVec(half, moved.halfExtents, 1e-5f)
        }
        // By hand: x was [0.5, 1.5]; X_NEG in by 50 -> [1.49, 1.5].
        val xNeg = start.moveFace(BoxFace.X_NEG, -50f)
        assertEquals(1.495f, xNeg.centre.x, 1e-5f)
        assertEquals(0.005f, xNeg.halfExtents.x, 1e-6f)
    }

    @Test
    fun `A3 moveFace leaves the original box untouched`() {
        start.moveFace(BoxFace.Y_POS, 1f)
        assertVec(Vec3(1f, 2f, 3f), start.centre, 0f)
        assertVec(Vec3(0.5f, 0.75f, 1f), start.halfExtents, 0f)
    }

    // --- fitCentral ---

    /**
     * 101 centres per axis, value i * step + offset for i in 0..100, except i in 0..4 and 96..100 are far outliers; each
     * axis is shuffled on its own (seeded). The 5th and 95th percentiles are the values at i = 5 and 95 under every
     * usual definition (linear, nearest rank, lower, higher) since 0.05 * 100 and 0.95 * 100 are whole.
     */
    private fun outlierCloud(): SplatCloud {
        val n = 101
        fun axis(offset: Float, step: Float, seed: Int): FloatArray {
            val v = FloatArray(n) { i ->
                when {
                    i < 5 -> -500f
                    i > 95 -> 500f
                    else -> offset + i * step
                }
            }
            val rnd = Random(seed)
            for (i in n - 1 downTo 1) {
                val j = rnd.nextInt(i + 1)
                val t = v[i]; v[i] = v[j]; v[j] = t
            }
            return v
        }
        val x = axis(0f, 0.01f, 42)
        val y = axis(2f, 0.02f, 43)
        val z = axis(-1f, 0.005f, 44)
        return cloudAt(FloatArray(3 * n) { j -> if (j % 3 == 0) x[j / 3] else if (j % 3 == 1) y[j / 3] else z[j / 3] })
    }

    private fun assertBox(lo: Vec3, hi: Vec3, box: CropBox, tol: Float = 1e-5f) {
        assertVec((lo + hi) * 0.5f, box.centre, tol)
        assertVec((hi - lo) * 0.5f, box.halfExtents, tol)
    }

    @Test
    fun `A2 fitCentral spans the 5th to 95th percentile per axis with outliers excluded`() {
        val box = CropBox.fitCentral(outlierCloud())
        // x [0.05, 0.95], y [2.10, 3.90], z [-0.975, -0.525].
        assertBox(Vec3(0.05f, 2.1f, -0.975f), Vec3(0.95f, 3.9f, -0.525f), box)
        assertQuat(Quat.IDENTITY, box.rotation)
    }

    @Test
    fun `A2 fitCentral fraction parameter sets the central share`() {
        val box = CropBox.fitCentral(outlierCloud(), fraction = 0.5f)
        // 25th..75th: x [0.25, 0.75], y [2.5, 3.5], z [-0.875, -0.625].
        assertBox(Vec3(0.25f, 2.5f, -0.875f), Vec3(0.75f, 3.5f, -0.625f), box)
    }

    @Test
    fun `A2 fitCentral is deterministic and independent of splat order`() {
        val cloud = outlierCloud()
        val a = CropBox.fitCentral(cloud)
        val b = CropBox.fitCentral(cloud)
        assertEquals(a.centre, b.centre)
        assertEquals(a.halfExtents, b.halfExtents)
        assertEquals(a.rotation, b.rotation)
        val n = cloud.count
        val reversed = cloudAt(FloatArray(3 * n) { j -> cloud.positions[3 * (n - 1 - j / 3) + j % 3] })
        val c = CropBox.fitCentral(reversed)
        assertEquals(a.centre, c.centre)
        assertEquals(a.halfExtents, c.halfExtents)
    }

    @Test
    fun `A2 fitCentral of a single splat is a 1 cm or larger box around it`() {
        val box = CropBox.fitCentral(cloudAt(floatArrayOf(1f, 2f, 3f)))
        assertVec(Vec3(1f, 2f, 3f), box.centre, 1e-6f)
        assertNonDegenerate(box)
        assertTrue(box.contains(Vec3(1f, 2f, 3f)))
        assertQuat(Quat.IDENTITY, box.rotation)
    }

    @Test
    fun `A2 fitCentral of all-equal centres is a 1 cm or larger box around them`() {
        val box = CropBox.fitCentral(cloudAt(FloatArray(30) { j -> floatArrayOf(-1f, 0.5f, 4f)[j % 3] }))
        assertVec(Vec3(-1f, 0.5f, 4f), box.centre, 1e-6f)
        assertNonDegenerate(box)
        assertTrue(box.contains(Vec3(-1f, 0.5f, 4f)))
    }

    @Test
    fun `A2 fitCentral of a flat cloud keeps the flat axis at least 1 cm`() {
        // Every centre on the plane y = 0.3: x and z spread, y degenerate.
        val n = 101
        val box = CropBox.fitCentral(cloudAt(FloatArray(3 * n) { j -> if (j % 3 == 1) 0.3f else (j / 3) * 0.01f }))
        assertNonDegenerate(box)
        assertEquals(0.3f, box.centre.y, 1e-6f)
    }

    @Test
    fun `A2 fitCentral rejects an empty cloud`() {
        assertThrows<IllegalArgumentException> { CropBox.fitCentral(cloudAt(FloatArray(0))) }
    }

    private fun assertNonDegenerate(box: CropBox) {
        // >= 1 cm full extent per axis; 1e-7 for float rounding of 0.005.
        assertTrue(box.halfExtents.x >= 0.005f - 1e-7f, "x half extent ${box.halfExtents.x}")
        assertTrue(box.halfExtents.y >= 0.005f - 1e-7f, "y half extent ${box.halfExtents.y}")
        assertTrue(box.halfExtents.z >= 0.005f - 1e-7f, "z half extent ${box.halfExtents.z}")
    }
}
