package com.huntercoles.splatman.viewer.rendering.pack

import com.huntercoles.splatman.splat.model.SplatCloud
import com.huntercoles.splatman.splat.projection.Covariance
import com.huntercoles.splatman.splat.sh.ShEval
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.exp
import kotlin.random.Random

class SplatPackerTest {

    private fun randomCloud(n: Int, seed: Int = 7): SplatCloud {
        val r = Random(seed)
        fun f(lo: Float, hi: Float) = lo + r.nextFloat() * (hi - lo)
        return SplatCloud(
            count = n,
            shDegree = 0,
            positions = FloatArray(3 * n) { f(-50f, 50f) },
            // sigma 0.011 .. 1 m: covariance diagonals stay in f16's normal range.
            logScales = FloatArray(3 * n) { f(-4.5f, 0f) },
            rotations = FloatArray(4 * n) { f(-1f, 1f) },
            opacityLogits = FloatArray(n) { f(-8f, 8f) },
            // Wide enough that some colours clamp at 0 and 1.
            shDc = FloatArray(3 * n) { f(-3f, 3f) },
            shRest = FloatArray(0),
        )
    }

    private fun u8(v: Float) = v.coerceIn(0f, 1f)

    private fun assertCloseRel(expected: Float, actual: Float, what: String) {
        val tol = 1e-3f * maxOf(abs(expected), 1e-3f)
        assertTrue(abs(expected - actual) <= tol, "$what: expected $expected, got $actual")
    }

    @Test
    fun `decodes back to centre, covariance and colour`() {
        val cloud = randomCloud(5000)
        val packed = SplatPacker().pack(cloud)
        val d = packed.data
        val cov = FloatArray(6)
        for (i in 0 until cloud.count) {
            val o = i * SplatPacker.SPLAT_BYTES
            assertEquals(cloud.positions[3 * i], d.getFloat(o))
            assertEquals(cloud.positions[3 * i + 1], d.getFloat(o + 4))
            assertEquals(cloud.positions[3 * i + 2], d.getFloat(o + 8))

            val rgba = d.getInt(o + 12)
            for (c in 0 until 3) {
                val expected = u8(0.5f + ShEval.C0.toFloat() * cloud.shDc[3 * i + c])
                val got = ((rgba ushr (8 * c)) and 0xFF) / 255f
                assertTrue(abs(expected - got) <= 1f / 255f, "splat $i channel $c: $expected vs $got")
            }
            val alpha = 1f / (1f + exp(-cloud.opacityLogits[i]))
            assertTrue(abs(alpha - (rgba ushr 24) / 255f) <= 1f / 255f, "splat $i alpha")

            Covariance.world(cloud.logScales, 3 * i, cloud.rotations, 4 * i, cov, 0)
            val words = intArrayOf(d.getInt(o + 16), d.getInt(o + 20), d.getInt(o + 24))
            for (k in 0 until 6) {
                val w = words[k / 2]
                val half = if (k % 2 == 0) w and 0xFFFF else w ushr 16
                assertCloseRel(cov[k], Half.toFloat(half), "splat $i cov[$k]")
            }
            assertEquals(0, d.getInt(o + 28))
        }
    }

    @Test
    fun `known splat packs to known bytes`() {
        // Unit scale, identity rotation: Sigma = I. shDc 0 -> grey 0.5 -> 128; logit 0 -> alpha 0.5 -> 128.
        val cloud = SplatCloud(
            1, 0, floatArrayOf(1f, -2f, 3.5f), FloatArray(3), floatArrayOf(1f, 0f, 0f, 0f),
            floatArrayOf(0f), FloatArray(3), FloatArray(0),
        )
        val d = SplatPacker().pack(cloud).data
        assertEquals(1f, d.getFloat(0))
        assertEquals(-2f, d.getFloat(4))
        assertEquals(3.5f, d.getFloat(8))
        assertEquals(0x80808080.toInt(), d.getInt(12))
        assertEquals(0x00003C00, d.getInt(16)) // (xx = 1, xy = 0)
        assertEquals(0x3C000000, d.getInt(20)) // (xz = 0, yy = 1)
        assertEquals(0x3C000000, d.getInt(24)) // (yz = 0, zz = 1)
    }

    @Test
    fun `texture height for 0, 1, 2048 and 2049 splats`() {
        assertEquals(0, SplatPacker.textureHeight(0))
        assertEquals(1, SplatPacker.textureHeight(1))
        assertEquals(2, SplatPacker.textureHeight(2048))
        assertEquals(3, SplatPacker.textureHeight(2049))
        assertEquals(1, SplatPacker.textureHeight(1024))
        assertEquals(2, SplatPacker.textureHeight(1025))
        val packer = SplatPacker()
        for (n in intArrayOf(0, 1, 2048, 2049)) {
            val p = packer.pack(randomCloud(n))
            assertEquals(n, p.count)
            assertEquals(SplatPacker.WIDTH, p.width)
            assertEquals(SplatPacker.textureHeight(n), p.height)
            assertEquals(p.width * p.height * SplatPacker.TEXEL_BYTES, p.data.remaining())
        }
    }

    @Test
    fun `reuses one direct buffer and zeroes the stale tail`() {
        val packer = SplatPacker()
        val big = packer.pack(randomCloud(3000)).data
        assertTrue(big.isDirect)
        val small = packer.pack(randomCloud(1)).data
        assertSame(big, small)
        val bytes = small.remaining()
        for (o in SplatPacker.SPLAT_BYTES until bytes step 4) assertEquals(0, small.getInt(o), "byte $o")
        val bigger = packer.pack(randomCloud(10_000)).data
        assertNotSame(big, bigger)
        assertSame(bigger, packer.pack(randomCloud(10_000, seed = 3)).data)
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    fun `packs 1M splats in under 1_5 s`() {
        val cloud = randomCloud(1_000_000)
        val packer = SplatPacker()
        val start = System.nanoTime()
        val data: ByteBuffer = packer.pack(cloud).data
        val ms = (System.nanoTime() - start) / 1_000_000
        assertEquals(32_000_000, 1_000_000 * SplatPacker.SPLAT_BYTES)
        assertTrue(data.remaining() >= 32_000_000)
        assertTrue(ms < 1500, "packing 1M splats took $ms ms")
    }
}
