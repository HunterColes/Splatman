package com.huntercoles.splatman.capture.recording

import com.huntercoles.splatman.capture.presentation.capture.GuidanceLimits
import com.huntercoles.splatman.capture.presentation.capture.GuidanceWarning
import com.huntercoles.splatman.splat.capture.PoseFreeKeyframePolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PoseFreeGuidanceTest {
    private val maxRate = PoseFreeKeyframePolicy().maxRateRadS
    private val slow = maxRate * 0.2f
    private val sharp = 100f
    private val floor = 10f
    private val bright = 128f

    @Test
    fun `A4 fast rate gives FAST`() {
        assertEquals(setOf(GuidanceWarning.FAST), PoseFreeGuidance.warnings(maxRate * 2f, sharp, floor, bright))
    }

    @Test
    fun `A5 slow and sharp gives none`() {
        assertEquals(emptySet<GuidanceWarning>(), PoseFreeGuidance.warnings(slow, sharp, floor, bright))
        assertEquals(emptySet<GuidanceWarning>(), PoseFreeGuidance.warnings(slow, sharp, floor, null))
    }

    @Test
    fun `A6 dark gives DARK`() {
        val dark = GuidanceLimits.MIN_MEAN_LUMA - 10f
        assertEquals(setOf(GuidanceWarning.DARK), PoseFreeGuidance.warnings(slow, sharp, floor, dark))
        assertEquals(
            setOf(GuidanceWarning.DARK, GuidanceWarning.FAST),
            PoseFreeGuidance.warnings(maxRate * 2f, sharp, floor, dark),
        )
    }

    @Test
    fun `A7 never LOST, CLOSE or FAR`() {
        val allowed = setOf(GuidanceWarning.FAST, GuidanceWarning.DARK)
        for (rate in listOf(0f, slow, maxRate * 2f, 100f)) {
            for (s in listOf(0f, 5f, sharp)) {
                for (luma in listOf(null, 0f, bright)) {
                    val w = PoseFreeGuidance.warnings(rate, s, floor, luma)
                    assertTrue(allowed.containsAll(w), "rate $rate sharpness $s luma $luma gave $w")
                }
            }
        }
    }

    @Test
    fun `B1 under the sharpness floor two frames running gives FAST, one frame does not`() {
        val t = PoseFreeGuidanceTracker()
        assertEquals(emptySet<GuidanceWarning>(), t.update(slow, 5f, floor, bright))
        assertEquals(setOf(GuidanceWarning.FAST), t.update(slow, 5f, floor, bright))
        assertEquals(emptySet<GuidanceWarning>(), t.update(slow, sharp, floor, bright))
        assertEquals(emptySet<GuidanceWarning>(), t.update(slow, 5f, floor, bright))
        t.reset()
        assertEquals(emptySet<GuidanceWarning>(), t.update(slow, 5f, floor, bright))
    }

    @Test
    fun `B2 the stateless call warns on blur only when the frame before was blurred too`() {
        assertEquals(emptySet<GuidanceWarning>(), PoseFreeGuidance.warnings(slow, 5f, floor, bright))
        assertEquals(
            setOf(GuidanceWarning.FAST),
            PoseFreeGuidance.warnings(slow, 5f, floor, bright, previousBlurred = true),
        )
    }

    @Test
    fun `B3 NaN rate, sharpness or floor raises nothing`() {
        val t = PoseFreeGuidanceTracker()
        repeat(2) { assertEquals(emptySet<GuidanceWarning>(), t.update(Float.NaN, Float.NaN, floor, bright)) }
        repeat(2) { assertEquals(emptySet<GuidanceWarning>(), t.update(slow, 5f, Float.NaN, bright)) }
    }
}
