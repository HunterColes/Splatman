package com.huntercoles.splatman.splat.capture

import com.huntercoles.splatman.splat.math.Quat
import com.huntercoles.splatman.splat.math.Vec3
import com.huntercoles.splatman.splat.math.assertVec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.min

/** #213: gyro integration for pose-free keyframes. Rates are rad/s in device axes, timestamps in ns. */
class GyroIntegratorTest {

    private val halfDegree = Math.toRadians(0.5).toFloat()
    private fun ms(t: Double): Long = (t * 1_000_000.0).toLong()

    /** Angle of [q] in 0..pi, insensitive to the double cover and to a slightly unnormalised quaternion. */
    private fun angleOf(q: Quat): Float = (2.0 * acos(min(1.0, abs(q.w.toDouble()) / q.norm()))).toFloat()

    /** Samples [rate] (rad/s) every [stepMs] from [fromMs] to [toMs] inclusive. */
    private fun GyroIntegrator.feed(fromMs: Double, toMs: Double, stepMs: Double, rate: Vec3) {
        var t = fromMs
        while (t <= toMs + 1e-9) {
            add(ms(t), rate.x, rate.y, rate.z)
            t += stepMs
        }
    }

    @Test
    fun `A2 constant rate about one axis gives the expected angle within half a degree`() {
        val g = GyroIntegrator()
        g.feed(0.0, 1000.0, 10.0, Vec3(0f, 0f, 1f)) // 1 rad/s about z for 1 s
        val q = g.orientation()
        assertEquals(1f, angleOf(q), halfDegree)
        assertEquals(1f, g.rotationSinceMarkRad(), halfDegree)
        // the turn is about z: z stays put, x stays in the xy plane
        assertVec(Vec3.UNIT_Z, q.normalized().rotate(Vec3.UNIT_Z), 1e-3f)
        assertEquals(0f, q.normalized().rotate(Vec3.UNIT_X).z, 1e-3f)
    }

    @Test
    fun `A2 uneven sample spacing integrates by elapsed time`() {
        val g = GyroIntegrator()
        val steps = doubleArrayOf(2.0, 7.0, 13.0, 4.5, 31.0, 1.0, 9.0, 60.0)
        var t = 0.0
        g.add(0L, 0.5f, 0f, 0f)
        var i = 0
        while (t < 2000.0) {
            t += steps[i++ % steps.size]
            g.add(ms(t), 0.5f, 0f, 0f)
        }
        val expected = (0.5 * t / 1000.0).toFloat() // 0.5 rad/s for t ms
        assertEquals(expected, angleOf(g.orientation()), halfDegree)
        assertEquals(expected, g.rotationSinceMarkRad(), halfDegree)
    }

    @Test
    fun `A2 the first sample is the reference orientation`() {
        val g = GyroIntegrator()
        g.add(ms(0.0), 3f, -2f, 1f)
        assertEquals(0f, angleOf(g.orientation()), 1e-6f)
        assertEquals(0f, g.rotationSinceMarkRad(), 1e-6f)
    }

    @Test
    fun `A2 device-axis rates compose in the device frame`() {
        // 90 degrees about z, then 90 degrees about the device's own (already turned) x: q = qz * qx.
        val g = GyroIntegrator()
        val rate = (PI / 2).toFloat() // rad/s, 1 s each
        g.feed(0.0, 1000.0, 5.0, Vec3(0f, 0f, rate))
        g.feed(1005.0, 2000.0, 5.0, Vec3(rate, 0f, 0f))
        val q = g.orientation().normalized()
        val expected = Quat.fromAxisAngle(Vec3.UNIT_Z, rate) * Quat.fromAxisAngle(Vec3.UNIT_X, rate)
        assertVec(expected.rotate(Vec3.UNIT_X), q.rotate(Vec3.UNIT_X), 0.02f)
        assertVec(expected.rotate(Vec3.UNIT_Y), q.rotate(Vec3.UNIT_Y), 0.02f)
    }

    @Test
    fun `A2 mark restarts the rotation since mark but not the orientation`() {
        val g = GyroIntegrator()
        g.feed(0.0, 500.0, 5.0, Vec3(0f, 1f, 0f)) // 0.5 rad
        assertEquals(0.5f, g.rotationSinceMarkRad(), halfDegree)
        g.mark()
        assertEquals(0f, g.rotationSinceMarkRad(), 1e-6f)
        g.feed(505.0, 600.0, 5.0, Vec3(0f, 1f, 0f)) // 0.1 rad more
        assertEquals(0.1f, g.rotationSinceMarkRad(), halfDegree)
        assertEquals(0.6f, angleOf(g.orientation()), halfDegree)
        // turning back toward the mark shrinks it: it is an angle, not a path length
        g.feed(605.0, 700.0, 5.0, Vec3(0f, -1f, 0f))
        assertEquals(0f, g.rotationSinceMarkRad(), halfDegree)
    }

    @Test
    fun `A2 rate is the magnitude of the last 100 ms mean`() {
        val g = GyroIntegrator()
        g.feed(0.0, 500.0, 5.0, Vec3(1.2f, 0f, 1.6f)) // |w| = 2 rad/s
        assertEquals(2f, g.rateRadS(), 1e-3f)
        g.feed(505.0, 800.0, 5.0, Vec3.ZERO) // held still for 300 ms
        assertEquals(0f, g.rateRadS(), 1e-3f)
    }

    @Test
    fun `A2 reset starts again from the next sample`() {
        val g = GyroIntegrator()
        g.feed(0.0, 500.0, 5.0, Vec3(1f, 0f, 0f))
        g.reset()
        assertEquals(0f, angleOf(g.orientation()), 1e-6f)
        assertEquals(0f, g.rotationSinceMarkRad(), 1e-6f)
        assertEquals(0f, g.rateRadS(), 1e-6f)
        // an earlier timestamp after reset is a new capture, not out of order
        g.feed(0.0, 200.0, 5.0, Vec3(1f, 0f, 0f))
        assertEquals(0.2f, angleOf(g.orientation()), halfDegree)
    }

    @Test
    fun `H1 out-of-order and duplicate timestamps are skipped, not integrated backwards`() {
        val g = GyroIntegrator()
        g.feed(0.0, 500.0, 10.0, Vec3(0f, 0f, 1f))
        g.add(ms(500.0), 0f, 0f, 100f) // duplicate
        g.add(ms(495.0), 0f, 0f, 100f) // older
        g.add(ms(250.0), 0f, 0f, -100f) // much older
        g.feed(510.0, 1000.0, 10.0, Vec3(0f, 0f, 1f))
        assertEquals(1f, angleOf(g.orientation()), halfDegree)
        assertEquals(1f, g.rateRadS(), 1e-3f)
    }

    @Test
    fun `H2 a gap over 100 ms is not integrated as one step`() {
        val g = GyroIntegrator()
        g.feed(0.0, 500.0, 10.0, Vec3(0f, 1f, 0f)) // 0.5 rad
        g.feed(800.0, 1300.0, 10.0, Vec3(0f, 1f, 0f)) // 300 ms gap, then 0.5 rad
        assertEquals(1f, angleOf(g.orientation()), halfDegree)
        assertEquals(1f, g.rotationSinceMarkRad(), halfDegree)
    }

    @Test
    fun `H2 a step of exactly 100 ms is still integrated`() {
        val g = GyroIntegrator()
        g.feed(0.0, 1000.0, 100.0, Vec3(0f, 1f, 0f))
        assertEquals(1f, angleOf(g.orientation()), halfDegree)
    }

    @Test
    fun `H3 a NaN sample is ignored`() {
        val g = GyroIntegrator()
        g.feed(0.0, 500.0, 10.0, Vec3(1f, 0f, 0f))
        g.add(ms(505.0), Float.NaN, 0f, 0f)
        g.add(ms(507.0), 0f, Float.NaN, 0f)
        g.add(ms(508.0), 0f, 0f, Float.POSITIVE_INFINITY)
        g.feed(510.0, 1000.0, 10.0, Vec3(1f, 0f, 0f))
        val q = g.orientation()
        assertTrue(q.w.isFinite() && q.x.isFinite() && q.y.isFinite() && q.z.isFinite(), "orientation $q")
        assertEquals(1f, angleOf(q), halfDegree)
        assertEquals(1f, g.rotationSinceMarkRad(), halfDegree)
        assertEquals(1f, g.rateRadS(), 1e-3f)
    }

    @Test
    fun `H4 integrating samples allocates nothing`() {
        val g = GyroIntegrator()
        var t = 0L
        repeat(2000) {
            g.add(t, 0.1f, 0.2f, 0.3f)
            g.rotationSinceMarkRad()
            g.rateRadS()
            t += 5_000_000L
        }
        val bean = java.lang.management.ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val id = Thread.currentThread().id
        val before = bean.getThreadAllocatedBytes(id)
        repeat(10_000) {
            g.add(t, 0.1f, 0.2f, 0.3f)
            g.rotationSinceMarkRad()
            g.rateRadS()
            t += 5_000_000L
        }
        val allocated = bean.getThreadAllocatedBytes(id) - before
        assertTrue(allocated < 16_000, "10000 samples allocated $allocated bytes")
    }
}
