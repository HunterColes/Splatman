package com.huntercoles.splatman.splat.frames

import com.huntercoles.splatman.splat.math.Vec3
import com.huntercoles.splatman.splat.model.SplatCloud
import com.huntercoles.splatman.splat.sh.ShEval
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/** ADR 0009: `ShEval` in W at `d` equals `ShEval` of the converted coefficients in F at `G d`. */
class ShFrameConsistencyTest {

    /** ShEval's layout for splat [i]: per channel the DC value followed by its `shRest` coefficients. */
    private fun shEvalCoeffs(c: SplatCloud, i: Int): FloatArray {
        val m = ShEval.coeffsPerChannel(c.shDegree)
        val out = FloatArray(3 * m)
        for (ch in 0 until 3) {
            out[ch * m] = c.shDc[3 * i + ch]
            for (k in 0 until m - 1) out[ch * m + 1 + k] = c.shRest[c.shRestIndex(i, ch, k)]
        }
        return out
    }

    @Test
    fun `colour is the same in W and in F for 100 seeded directions at every degree`() {
        for (degree in 0..3) {
            val rnd = Random(100 + degree)
            val world = FrameConversionsTest.seededCloud(rnd, count = 5, shDegree = degree)
            val file = FrameConversions.worldToFile(world)
            repeat(100) {
                val d = Vec3(rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f).normalized()
                val i = rnd.nextInt(world.count)
                val inW = ShEval.eval(degree, shEvalCoeffs(world, i), 0, d)
                val inF = ShEval.eval(degree, shEvalCoeffs(file, i), 0, FrameConversions.worldToFile(d))
                for (ch in 0 until 3) assertEquals(inW[ch], inF[ch], 1e-6f, "degree $degree channel $ch dir $d")
            }
        }
    }

    @Test
    fun `without the sign table the colours differ`() {
        // Guards the test itself: flipping the direction alone, with unconverted coefficients, must not match.
        val rnd = Random(9)
        val world = FrameConversionsTest.seededCloud(rnd, count = 1, shDegree = 3)
        val d = Vec3(0.3f, 0.5f, -0.8f).normalized()
        val inW = ShEval.eval(3, shEvalCoeffs(world, 0), 0, d)
        val wrong = ShEval.eval(3, shEvalCoeffs(world, 0), 0, FrameConversions.worldToFile(d))
        val maxDiff = (0 until 3).maxOf { kotlin.math.abs(inW[it] - wrong[it]) }
        assertTrue(maxDiff > 1e-3f, "expected a difference, got $maxDiff")
    }
}
