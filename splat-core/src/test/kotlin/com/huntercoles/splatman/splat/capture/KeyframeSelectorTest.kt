package com.huntercoles.splatman.splat.capture

import com.huntercoles.splatman.splat.math.Quat
import com.huntercoles.splatman.splat.math.Vec3
import com.huntercoles.splatman.splat.model.CameraPose
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class KeyframeSelectorTest {

    private val origin = CameraPose(Quat(1f, 0f, 0f, 0f), Vec3(0f, 0f, 0f))
    private fun at(x: Float, y: Float = 0f, z: Float = 0f) = CameraPose(origin.rotation, Vec3(x, y, z))
    private fun turned(deg: Double, axis: Vec3 = Vec3(0f, 1f, 0f)) =
        CameraPose(Quat.fromAxisAngle(axis, Math.toRadians(deg).toFloat()), origin.translationM)

    /** A selector with its first keyframe at [origin] and a flat sharpness history. */
    private fun started(policy: KeyframePolicy = KeyframePolicy()): KeyframeSelector {
        val s = KeyframeSelector(policy)
        assertTrue(s.offer(origin, trackingOk = true, sharpness = 100f))
        return s
    }

    @Test
    fun `default policy is ADR 0004`() {
        val p = KeyframePolicy()
        assertEquals(0.05f, p.minTranslationM)
        assertEquals(10f, p.minRotationDeg)
        assertEquals(30, p.sharpnessWindow)
        assertEquals(0.25f, p.sharpnessQuantile)
        assertEquals(120, p.maxKeyframes)
    }

    @Test
    fun `the first tracked frame is a keyframe`() {
        val s = KeyframeSelector()
        assertTrue(s.offer(origin, true, 1f))
        assertEquals(1, s.keyframeCount)
    }

    @Test
    fun `translation just under 5 cm is rejected, just over is accepted`() {
        val s = started()
        assertFalse(s.offer(at(0.0499f), true, 100f))
        assertFalse(s.offer(at(0f, 0.0499f), true, 100f))
        assertTrue(s.offer(at(0.0501f), true, 100f))
        // measured from the last keyframe, not the origin
        assertFalse(s.offer(at(0.0501f + 0.0499f), true, 100f))
        assertTrue(s.offer(at(0.0501f, 0f, 0.0501f), true, 100f))
        assertEquals(3, s.keyframeCount)
    }

    @Test
    fun `rotation just under 10 degrees is rejected, just over is accepted`() {
        val s = started()
        assertFalse(s.offer(turned(9.9), true, 100f))
        assertFalse(s.offer(turned(9.9, Vec3(1f, 0f, 0f)), true, 100f))
        assertTrue(s.offer(turned(10.1), true, 100f))
        assertFalse(s.offer(turned(10.1 + 9.9), true, 100f))
        assertTrue(s.offer(turned(10.1 + 10.1), true, 100f))
    }

    @Test
    fun `the double cover does not count as a turn`() {
        val s = started()
        val q = turned(5.0).rotation
        assertFalse(s.offer(CameraPose(Quat(-q.w, -q.x, -q.y, -q.z), origin.translationM), true, 100f))
    }

    @Test
    fun `sharpness just under the bottom quartile of the last 30 frames is rejected, at or over it accepted`() {
        // 30 tracked frames with sharpness 1..30, none moving after the first: quartile value = sorted[7] = 8.
        // Every offer also pushes, so each probe builds its own window.
        val probe = { sharpness: Float ->
            val sel = KeyframeSelector()
            sel.offer(origin, true, 1f) // keyframe 1 (empty window), window = [1]
            for (i in 2..30) sel.offer(origin, true, i.toFloat()) // window = 1..30, no motion: rejected
            sel.offer(at(1f), true, sharpness)
        }
        assertFalse(probe(7.99f))
        assertTrue(probe(8f))
        assertTrue(probe(8.01f))
    }

    @Test
    fun `the window keeps only the last 30 frames`() {
        val s = KeyframeSelector()
        s.offer(origin, true, 1000f)
        for (i in 1..30) s.offer(origin, true, 1f) // the 1000 falls out
        assertTrue(s.offer(at(1f), true, 1f))
    }

    @Test
    fun `a blurry frame still enters the window`() {
        val s = started() // window [100]
        assertFalse(s.offer(at(1f), true, 50f)) // 50 < 100; window [100, 50], threshold sorted[0] = 50
        assertTrue(s.offer(at(1f), true, 50f))
    }

    @Test
    fun `tracking lost is rejected and its sharpness ignored`() {
        val s = started() // window [100]
        assertFalse(s.offer(at(1f), false, 1000f))
        assertFalse(s.offer(at(1f), false, 1f))
        assertTrue(s.offer(at(1f), true, 100f))
        assertFalse(KeyframeSelector().offer(origin, false, 100f))
    }

    @Test
    fun `no keyframe after the cap`() {
        val s = KeyframeSelector(KeyframePolicy(maxKeyframes = 120))
        for (i in 0 until 120) assertTrue(s.offer(at(i * 0.1f), true, 100f), "frame $i")
        assertEquals(120, s.keyframeCount)
        assertFalse(s.offer(at(120 * 0.1f), true, 100f))
        assertFalse(s.offer(at(500f), true, 1000f))
        assertEquals(120, s.keyframeCount)
    }

    @Test
    fun `reset starts a new capture`() {
        val s = KeyframeSelector(KeyframePolicy(maxKeyframes = 1))
        assertTrue(s.offer(origin, true, 100f))
        assertFalse(s.offer(at(1f), true, 100f))
        s.reset()
        assertEquals(0, s.keyframeCount)
        assertTrue(s.offer(origin, true, 1f))
    }

    @Test
    fun `policy rejects nonsense`() {
        assertThrows<IllegalArgumentException> { KeyframePolicy(minTranslationM = -1f) }
        assertThrows<IllegalArgumentException> { KeyframePolicy(sharpnessWindow = 0) }
        assertThrows<IllegalArgumentException> { KeyframePolicy(sharpnessQuantile = 1.5f) }
        assertThrows<IllegalArgumentException> { KeyframePolicy(maxKeyframes = 0) }
    }
}
