package com.huntercoles.splatman.splat.model

import com.huntercoles.splatman.splat.math.Quat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.math.exp
import kotlin.math.sqrt

class SplatCloudTest {

    private val tol = 1e-6f

    private fun cloud(
        count: Int = 2,
        shDegree: Int = 1,
        positions: Int = 3 * count,
        logScales: Int = 3 * count,
        rotations: Int = 4 * count,
        opacity: Int = count,
        shDc: Int = 3 * count,
        shRest: Int = count * 3 * ((shDegree + 1) * (shDegree + 1) - 1),
    ) = SplatCloud(
        count, shDegree, FloatArray(positions), FloatArray(logScales), FloatArray(rotations), FloatArray(opacity),
        FloatArray(shDc), FloatArray(shRest),
    )

    @Test
    fun `accepts arrays of the right length`() {
        for (degree in 0..3) assertEquals(2, cloud(shDegree = degree).count)
        assertEquals(0, cloud(count = 0).count)
    }

    @Test
    fun `rejects each array of the wrong length`() {
        assertThrows<IllegalArgumentException> { cloud(positions = 5) }
        assertThrows<IllegalArgumentException> { cloud(logScales = 7) }
        assertThrows<IllegalArgumentException> { cloud(rotations = 6) }
        assertThrows<IllegalArgumentException> { cloud(opacity = 3) }
        assertThrows<IllegalArgumentException> { cloud(shDc = 4) }
        assertThrows<IllegalArgumentException> { cloud(shRest = 17) }
        assertThrows<IllegalArgumentException> { cloud(shDegree = 2, shRest = 18) }
    }

    @Test
    fun `rejects shDegree outside 0 to 3`() {
        assertThrows<IllegalArgumentException> { cloud(shDegree = -1, shRest = 0) }
        assertThrows<IllegalArgumentException> { cloud(shDegree = 4, shRest = 2 * 3 * 24) }
    }

    @Test
    fun `rejects negative count`() {
        assertThrows<IllegalArgumentException> {
            SplatCloud(-1, 0, FloatArray(0), FloatArray(0), FloatArray(0), FloatArray(0), FloatArray(0), FloatArray(0))
        }
    }

    @Test
    fun `K is 0, 3, 8, 15 for degree 0 to 3`() {
        assertEquals(listOf(0, 3, 8, 15), (0..3).map { SplatCloud.restCoefficients(it) })
        assertEquals(listOf(0, 3, 8, 15), (0..3).map { cloud(shDegree = it).shRestPerChannel })
    }

    @Test
    fun `opacity is the sigmoid of the logit`() {
        val c = SplatCloud(
            3, 0, FloatArray(9), FloatArray(9), FloatArray(12), floatArrayOf(0f, 2f, -3f), FloatArray(9), FloatArray(0),
        )
        assertEquals(0.5f, c.opacity(0), tol)
        assertEquals((1.0 / (1.0 + exp(-2.0))).toFloat(), c.opacity(1), tol)
        assertEquals((1.0 / (1.0 + exp(3.0))).toFloat(), c.opacity(2), tol)
    }

    @Test
    fun `scale is exp of the log scale`() {
        val c = SplatCloud(
            1, 0, FloatArray(3), floatArrayOf(0f, -2f, 1f), FloatArray(4), FloatArray(1), FloatArray(3), FloatArray(0),
        )
        val s = c.scale(0)
        assertEquals(1f, s.x, tol)
        assertEquals(exp(-2.0).toFloat(), s.y, tol)
        assertEquals(exp(1.0).toFloat(), s.z, tol)
    }

    @Test
    fun `rotation is normalized and keeps w x y z order`() {
        val c = SplatCloud(
            2, 0, FloatArray(6), FloatArray(6), floatArrayOf(2f, 0f, 0f, 0f, 1f, 2f, 3f, 4f), FloatArray(2),
            FloatArray(6), FloatArray(0),
        )
        assertEquals(Quat(1f, 0f, 0f, 0f), c.rotation(0))
        val n = sqrt(30f)
        val q = c.rotation(1)
        assertEquals(1f / n, q.w, tol)
        assertEquals(2f / n, q.x, tol)
        assertEquals(3f / n, q.y, tol)
        assertEquals(4f / n, q.z, tol)
        assertEquals(1f, q.norm(), tol)
    }

    @Test
    fun `shRest index is i times 3K plus c times K plus k`() {
        val count = 3
        val degree = 2
        val k = 8
        val rest = FloatArray(count * 3 * k) { it.toFloat() }
        val c = SplatCloud(
            count, degree, FloatArray(9), FloatArray(9), FloatArray(12), FloatArray(3), FloatArray(9), rest,
        )
        for (i in 0 until count) for (ch in 0 until 3) for (j in 0 until k) {
            assertEquals(i * 3 * k + ch * k + j, c.shRestIndex(i, ch, j))
        }
        assertEquals(1 * 24 + 2 * 8 + 5f, rest[c.shRestIndex(1, 2, 5)])
    }

    @Test
    fun `arrays are not copied on read`() {
        val positions = floatArrayOf(1f, 2f, 3f)
        val c = SplatCloud(1, 0, positions, FloatArray(3), FloatArray(4), FloatArray(1), FloatArray(3), FloatArray(0))
        assertSame(positions, c.positions)
        assertEquals(2f, c.position(0).y)
    }
}
