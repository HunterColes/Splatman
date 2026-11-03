package com.huntercoles.splatman.splat.capture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** #213: pose-free keyframe selection from gyro rotation, image change, rate and sharpness. */
class PoseFreeKeyframeSelectorTest {

    private fun ms(t: Long): Long = t * 1_000_000L
    private fun deg(d: Double): Float = Math.toRadians(d).toFloat()

    /** A selector whose first keyframe was taken at t = 0 with sharpness 100 (flat history). */
    private fun started(policy: PoseFreeKeyframePolicy = PoseFreeKeyframePolicy()): PoseFreeKeyframeSelector {
        val s = PoseFreeKeyframeSelector(policy)
        assertTrue(s.offer(ms(0), 0f, 0f, 0f, 100f))
        return s
    }

    @Test
    fun `A3 default policy`() {
        val p = PoseFreeKeyframePolicy()
        assertEquals(3f, p.minRotationDeg)
        assertEquals(12f, p.minImageChange)
        assertEquals(100L, p.minIntervalMs.toLong())
        assertEquals(1.5f, p.maxRateRadS)
        assertEquals(30, p.sharpnessWindow)
        assertEquals(0.25f, p.sharpnessQuantile)
        assertEquals(300, p.maxKeyframes)
    }

    @Test
    fun `A3 the first acceptable frame is always taken`() {
        val s = PoseFreeKeyframeSelector(PoseFreeKeyframePolicy())
        assertTrue(s.offer(ms(5), 0f, 0f, 0f, 1f))
        assertEquals(1, s.keyframeCount)
    }

    @Test
    fun `A3 the first frame still obeys the rate rule`() {
        val s = PoseFreeKeyframeSelector(PoseFreeKeyframePolicy())
        assertFalse(s.offer(ms(0), 0f, 0f, 3f, 100f))
        assertEquals(0, s.keyframeCount)
        assertTrue(s.offer(ms(30), 0f, 0f, 0.1f, 100f))
    }

    @Test
    fun `A3 rotation alone just under 3 degrees is rejected, over is accepted`() {
        val s = started()
        assertFalse(s.offer(ms(200), deg(2.9), 0f, 0f, 100f))
        assertTrue(s.offer(ms(400), deg(3.01), 0f, 0f, 100f))
        assertTrue(s.offer(ms(600), deg(45.0), 0f, 0f, 100f))
        assertEquals(3, s.keyframeCount)
    }

    @Test
    fun `A3 image change alone just under 12 is rejected, at 12 accepted`() {
        val s = started()
        assertFalse(s.offer(ms(200), 0f, 11.9f, 0f, 100f))
        assertTrue(s.offer(ms(400), 0f, 12f, 0f, 100f))
        assertTrue(s.offer(ms(600), 0f, 80f, 0f, 100f))
        assertEquals(3, s.keyframeCount)
    }

    @Test
    fun `A3 neither rotation nor image change is no keyframe`() {
        val s = started()
        for (t in 1..20) assertFalse(s.offer(ms(t * 150L), deg(1.0), 5f, 0f, 100f), "frame $t")
        assertEquals(1, s.keyframeCount)
    }

    @Test
    fun `A3 min interval alone, measured from the last keyframe`() {
        val s = started()
        assertFalse(s.offer(ms(99), deg(30.0), 50f, 0f, 100f))
        assertTrue(s.offer(ms(100), deg(30.0), 50f, 0f, 100f))
        assertFalse(s.offer(ms(150), deg(30.0), 50f, 0f, 100f))
        assertFalse(s.offer(ms(199), deg(30.0), 50f, 0f, 100f))
        assertTrue(s.offer(ms(200), deg(30.0), 50f, 0f, 100f))
    }

    @Test
    fun `A3 rate alone at or over 1_5 rad per s is rejected, below accepted`() {
        val s = started()
        assertFalse(s.offer(ms(200), deg(30.0), 50f, 1.5f, 100f))
        assertFalse(s.offer(ms(300), deg(30.0), 50f, 4f, 100f))
        assertTrue(s.offer(ms(400), deg(30.0), 50f, 1.49f, 100f))
    }

    @Test
    fun `A3 sharpness just under the bottom quartile of the last 30 frames is rejected, at or over accepted`() {
        // As KeyframeSelector: 30 frames with sharpness 1..30, none turning after the first; threshold sorted[7] = 8.
        val probe = { sharpness: Float ->
            val s = PoseFreeKeyframeSelector(PoseFreeKeyframePolicy())
            s.offer(ms(0), 0f, 0f, 0f, 1f) // keyframe 1, window [1]
            for (i in 2..30) s.offer(ms(i * 200L), 0f, 0f, 0f, i.toFloat()) // no change: rejected, still pushed
            s.offer(ms(10_000), deg(30.0), 50f, 0f, sharpness)
        }
        assertFalse(probe(7.99f))
        assertTrue(probe(8f))
        assertTrue(probe(8.01f))
    }

    @Test
    fun `A3 the sharpness window keeps only the last 30 frames`() {
        val s = PoseFreeKeyframeSelector(PoseFreeKeyframePolicy())
        assertTrue(s.offer(ms(0), 0f, 0f, 0f, 1000f))
        for (i in 1..30) s.offer(ms(i * 200L), 0f, 0f, 0f, 1f) // the 1000 falls out
        assertTrue(s.offer(ms(10_000), deg(30.0), 50f, 0f, 1f))
    }

    @Test
    fun `A3 no keyframe after the cap`() {
        val s = PoseFreeKeyframeSelector(PoseFreeKeyframePolicy(maxKeyframes = 5))
        for (i in 0 until 5) assertTrue(s.offer(ms(i * 200L), deg(30.0), 50f, 0f, 100f), "frame $i")
        assertEquals(5, s.keyframeCount)
        assertFalse(s.offer(ms(2000), deg(30.0), 50f, 0f, 100f))
        assertFalse(s.offer(ms(5000), deg(90.0), 200f, 0f, 1000f))
        assertEquals(5, s.keyframeCount)
    }

    @Test
    fun `A3 the default cap is 300`() {
        val s = PoseFreeKeyframeSelector(PoseFreeKeyframePolicy())
        for (i in 0 until 400) s.offer(ms(i * 200L), deg(30.0), 50f, 0f, 100f)
        assertEquals(300, s.keyframeCount)
    }

    @Test
    fun `A3 reset starts a new capture`() {
        val s = PoseFreeKeyframeSelector(PoseFreeKeyframePolicy(maxKeyframes = 1))
        assertTrue(s.offer(ms(1000), 0f, 0f, 0f, 100f))
        assertFalse(s.offer(ms(2000), deg(30.0), 50f, 0f, 100f))
        s.reset()
        assertEquals(0, s.keyframeCount)
        // an earlier timestamp after reset is fine: the interval rule starts again
        assertTrue(s.offer(ms(0), 0f, 0f, 0f, 1f))
    }

    @Test
    fun `A3 policy rejects nonsense`() {
        assertThrows<IllegalArgumentException> { PoseFreeKeyframePolicy(minRotationDeg = -1f) }
        assertThrows<IllegalArgumentException> { PoseFreeKeyframePolicy(minRotationDeg = Float.NaN) }
        assertThrows<IllegalArgumentException> { PoseFreeKeyframePolicy(minImageChange = -1f) }
        assertThrows<IllegalArgumentException> { PoseFreeKeyframePolicy(minIntervalMs = -1) }
        assertThrows<IllegalArgumentException> { PoseFreeKeyframePolicy(maxRateRadS = 0f) }
        assertThrows<IllegalArgumentException> { PoseFreeKeyframePolicy(sharpnessWindow = 0) }
        assertThrows<IllegalArgumentException> { PoseFreeKeyframePolicy(sharpnessQuantile = 1.5f) }
        assertThrows<IllegalArgumentException> { PoseFreeKeyframePolicy(maxKeyframes = 0) }
    }

    @Test
    fun `H4 the selector allocates nothing per frame`() {
        val s = PoseFreeKeyframeSelector(PoseFreeKeyframePolicy(maxKeyframes = 50))
        var t = 0L
        repeat(2000) {
            s.offer(t, deg((it % 7).toDouble()), (it % 20).toFloat(), 0.2f, (it % 13).toFloat())
            t += 33_000_000L
        }
        val bean = java.lang.management.ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val id = Thread.currentThread().id
        val before = bean.getThreadAllocatedBytes(id)
        repeat(10_000) {
            s.offer(t, deg((it % 7).toDouble()), (it % 20).toFloat(), 0.2f, (it % 13).toFloat())
            t += 33_000_000L
        }
        val allocated = bean.getThreadAllocatedBytes(id) - before
        assertTrue(allocated < 16_000, "10000 offers allocated $allocated bytes")
    }

    /**
     * #340: a 30 s walk round an object at 30 fps turning 20 deg/s, sharpness 500 to 1300 as on the Pixel 8 Pro, the
     * image change growing with the turn; the selector sees every [everyNthFrame]th frame. Returns the keyframes kept.
     */
    private fun walk(everyNthFrame: Int): Int {
        val s = PoseFreeKeyframeSelector(PoseFreeKeyframePolicy())
        val random = java.util.Random(340)
        val degPerFrame = 20.0 / 30.0
        var sinceKeyDeg = 0.0
        for (i in 0 until 30 * 30) {
            sinceKeyDeg += degPerFrame
            val sharpness = 500f + 800f * random.nextFloat()
            val rate = deg(20.0 + 4.0 * (random.nextDouble() - 0.5))
            if (i % everyNthFrame != 0) continue
            val change = minOf(255.0, 1.5 * sinceKeyDeg).toFloat()
            if (s.offer(ms(i * 1000L / 30), deg(sinceKeyDeg), change, rate, sharpness)) sinceKeyDeg = 0.0
        }
        return s.keyframeCount
    }

    @Test
    fun `340 a 30 s walk at 30 fps keeps 100 to 300 keyframes`() {
        val kept = walk(everyNthFrame = 1)
        assertTrue(kept in 100..300, "kept $kept")
    }

    @Test
    fun `340 the same walk seen once per 0_6 s as by the Pixel's worker keeps under 50`() {
        val kept = walk(everyNthFrame = 18)
        assertTrue(kept < 50, "kept $kept")
    }
}
