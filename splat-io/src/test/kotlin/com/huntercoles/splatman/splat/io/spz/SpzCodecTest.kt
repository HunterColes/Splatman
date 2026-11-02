package com.huntercoles.splatman.splat.io.spz

import com.huntercoles.splatman.splat.io.FormatDetector
import com.huntercoles.splatman.splat.io.SplatFormat
import com.huntercoles.splatman.splat.io.ply.PlyReader
import com.huntercoles.splatman.splat.model.SplatCloud
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

class SpzCodecTest {

    companion object {
        /** Positions: 24-bit fixed point with 12 fractional bits, rounded: half a 1/4096 m step. */
        const val POSITION_TOL = 0.5f / 4096f + 1e-6f
        /** Opacity (activated): u8 of sigmoid * 255, rounded: half of 1/255. */
        const val OPACITY_TOL = 0.5f / 255f + 1e-6f
        /** DC colour: u8 of `dc * 0.15 * 255 + 127.5`, rounded: half of 1 / (0.15 * 255). */
        const val COLOUR_TOL = 0.5f / (0.15f * 255f) + 1e-5f
        /** Log-scale: u8 of `(s + 10) * 16`, rounded: half of 1/16. */
        const val LOG_SCALE_TOL = 0.5f / 16f + 1e-6f
        /** v3 rotation: each small component has 9 bits over [0, sqrt(1/2)]. */
        val ROTATION_STEP = sqrt(0.5f) / 511f
        /**
         * Small components are off by at most half a step; the largest is rebuilt from them, so it is off by at most
         * sum(|q_i| / q_max) * step / 2 <= 1.5 steps.
         */
        val ROTATION_TOL = 1.5f * ROTATION_STEP + 1e-5f
        /** SH degree 1: rounded to 1/128, then to a multiple of 8 (5 bits): 4.5 / 128. */
        const val SH1_TOL = (SpzCodec.SH1_BUCKET / 2 + 0.5f) / 128f + 1e-6f
        /** SH degrees 2 and 3: rounded to 1/128, then to a multiple of 16 (4 bits): 8.5 / 128. */
        const val SH_REST_TOL = (SpzCodec.SH_REST_BUCKET / 2 + 0.5f) / 128f + 1e-6f
    }

    private fun randomCloud(n: Int, degree: Int, rnd: Random = Random(11)): SplatCloud {
        val k = SplatCloud.restCoefficients(degree)
        return SplatCloud(
            count = n,
            shDegree = degree,
            positions = FloatArray(3 * n) { rnd.nextFloat() * 200f - 100f },
            logScales = FloatArray(3 * n) { rnd.nextFloat() * 14f - 9f },
            rotations = FloatArray(4 * n) { rnd.nextFloat() * 2f - 1f },
            opacityLogits = FloatArray(n) { rnd.nextFloat() * 12f - 6f },
            shDc = FloatArray(3 * n) { rnd.nextFloat() * 6f - 3f },
            shRest = FloatArray(n * 3 * k) { rnd.nextFloat() * 1.8f - 0.9f },
        )
    }

    private fun gunzip(bytes: ByteArray): ByteArray = GZIPInputStream(ByteArrayInputStream(bytes)).readBytes()

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    private fun header(version: Int, count: Int, degree: Int, fractionalBits: Int, magic: Int = SpzCodec.MAGIC) =
        ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(magic).putInt(version).putInt(count)
            .put(degree.toByte()).put(fractionalBits.toByte()).put(0).put(0).array()

    private fun sample(name: String): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile
        return File(requireNotNull(dir) { "repo root not found" }, "app/src/main/assets/samples/$name")
    }

    private fun assertRotationClose(expected: FloatArray, actual: FloatArray, tol: Float, what: String) {
        val dot = (0 until 4).sumOf { (expected[it] * actual[it]).toDouble() }
        val sign = if (dot < 0) -1f else 1f // q and -q are the same rotation
        for (a in 0 until 4) {
            assertEquals(expected[a], sign * actual[a], tol, "$what component $a")
        }
    }

    private fun unit(rotations: FloatArray, i: Int): FloatArray {
        val q = rotations.copyOfRange(4 * i, 4 * i + 4)
        val len = sqrt(q.sumOf { (it * it).toDouble() }).toFloat()
        return FloatArray(4) { q[it] / len }
    }

    @Test
    fun `round trip keeps every field within its quantisation step`() {
        for (degree in 0..3) {
            val src = randomCloud(300, degree, Random(degree))
            val back = SpzCodec.read(SpzCodec.write(src))
            assertEquals(src.count, back.count)
            assertEquals(degree, back.shDegree)
            val k = src.shRestPerChannel
            for (i in 0 until src.count) {
                for (a in 0 until 3) {
                    assertEquals(src.positions[3 * i + a], back.positions[3 * i + a], POSITION_TOL, "position $i")
                    assertEquals(src.logScales[3 * i + a], back.logScales[3 * i + a], LOG_SCALE_TOL, "scale $i")
                    assertEquals(src.shDc[3 * i + a], back.shDc[3 * i + a], COLOUR_TOL, "colour $i")
                }
                assertEquals(src.opacity(i), back.opacity(i), OPACITY_TOL, "opacity $i")
                assertRotationClose(unit(src.rotations, i), back.rotations.copyOfRange(4 * i, 4 * i + 4),
                    ROTATION_TOL, "rotation $i")
                for (c in 0 until 3) for (kk in 0 until k) {
                    val idx = src.shRestIndex(i, c, kk)
                    val tol = if (kk < 3) SH1_TOL else SH_REST_TOL
                    assertEquals(src.shRest[idx], back.shRest[idx], tol, "sh $i c$c k$kk at degree $degree")
                }
            }
        }
    }

    @Test
    fun `header fields are NGSP, version 3, count, degree, 12 fractional bits, no flags`() {
        val cloud = randomCloud(37, 2)
        val bytes = SpzCodec.write(cloud)
        assertEquals(SpzHeader(3, 37, 2, 12, 0), SpzCodec.readHeader(ByteArrayInputStream(bytes)))
        val raw = gunzip(bytes)
        assertArrayEquals("NGSP".toByteArray(Charsets.US_ASCII), raw.copyOf(4))
        // 16 header bytes + 9 position + 1 alpha + 3 colour + 3 scale + 4 rotation + 3 * 8 SH bytes per splat
        assertEquals(16 + 37 * (9 + 1 + 3 + 3 + 4 + 24), raw.size)
        assertEquals(SplatFormat.SPZ, FormatDetector.detect(bytes.copyOf(minOf(512, bytes.size)), bytes.size.toLong()))
    }

    @Test
    fun `writer output is byte-identical across runs`() {
        val cloud = randomCloud(50, 1)
        assertArrayEquals(SpzCodec.write(cloud), SpzCodec.write(cloud))
    }

    @Test
    fun `SH is written coefficient-major with the colour channel innermost`() {
        val k = 3
        val shRest = FloatArray(3 * k)
        shRest[1 * k + 2] = 0.5f // channel g, coefficient 2
        val cloud = SplatCloud(1, 1, FloatArray(3), FloatArray(3), floatArrayOf(1f, 0f, 0f, 0f), FloatArray(1),
            FloatArray(3), shRest)
        val raw = gunzip(SpzCodec.write(cloud))
        val sh = raw.copyOfRange(raw.size - 9, raw.size).map { it.toInt() and 0xFF }
        // packed index k * 3 + c = 7 holds 0.5 * 128 + 128 = 192, every other byte 128 (zero)
        assertEquals(List(9) { if (it == 7) 192 else 128 }, sh)
    }

    @Test
    fun `out of range values clamp instead of wrapping`() {
        val cloud = SplatCloud(1, 0, floatArrayOf(1e9f, -1e9f, 0f), floatArrayOf(-50f, 50f, 0f),
            floatArrayOf(0f, 0f, 0f, -2f), floatArrayOf(80f), floatArrayOf(-9f, 9f, 0f), FloatArray(0))
        val back = SpzCodec.read(SpzCodec.write(cloud))
        assertEquals(8388607f / 4096f, back.positions[0])
        // W y = -1e9 is F y = +1e9, clamped to the 24-bit maximum, then read back as -max
        assertEquals(-8388607f / 4096f, back.positions[1])
        assertEquals(-10f, back.logScales[0])
        assertEquals(255f / 16f - 10f, back.logScales[1])
        assertTrue(back.opacityLogits[0].isFinite() && back.opacity(0) > 0.999f)
        assertEquals(-0.5f / 0.15f, back.shDc[0], 1e-5f)
        assertEquals(0.5f / 0.15f, back.shDc[1], 1e-5f)
        assertRotationClose(floatArrayOf(0f, 0f, 0f, 1f), back.rotations.copyOfRange(0, 4), ROTATION_TOL, "rotation")
    }

    @Test
    fun `golden cactus spz decodes to its known count and first splat`() {
        // Stored in F (#154): W is (x, -y, -z) of the stored position and (w, x, -y, -z) of the stored rotation.
        // app/src/main/assets/samples/cactus.spz (#5): SPZ v3, 452,451 splats, SH 0, 12 fractional bits.
        // First splat's raw bytes: position 54 ff ff | dd e7 ff | be fa ff, alpha 52, colour 147 150 145,
        // scale 51 3 75, rotation 00 66 31 d2 (u32 0xd2316600: largest index 3 = w, then x, y, z fields).
        val file = sample("cactus.spz")
        assertEquals(SpzHeader(3, 452_451, 0, 12, 0), file.inputStream().use { SpzCodec.readHeader(it) })
        val cloud = file.inputStream().buffered().use { SpzCodec.read(it) }
        assertEquals(452_451, cloud.count)
        assertEquals(0, cloud.shDegree)
        // 0xffff54 = -172, 0xffe7dd = -6179, 0xfffabe = -1346; / 4096 exactly, then F -> W
        assertArrayEquals(floatArrayOf(-172f / 4096f, 6179f / 4096f, 1346f / 4096f), cloud.positions.copyOf(3))
        assertEquals(ln((52f / 255f) / (1f - 52f / 255f)), cloud.opacityLogits[0], 1e-5f)
        assertArrayEquals(
            floatArrayOf((147f / 255f - 0.5f) / 0.15f, (150f / 255f - 0.5f) / 0.15f, (145f / 255f - 0.5f) / 0.15f),
            cloud.shDc.copyOf(3), 1e-5f,
        )
        assertArrayEquals(floatArrayOf(51f / 16f - 10f, 3f / 16f - 10f, 75f / 16f - 10f), cloud.logScales.copyOf(3))
        // 0xd2316600: top 2 bits 3 (w largest), then 10-bit fields [sign | 9-bit magnitude] x, y, z from the top:
        // x = 291 (0x123), y = 89 (0x059), z = 512 (0x200: sign set, magnitude 0); w = sqrt(1 - x^2 - y^2)
        val x = sqrt(0.5f) * 291f / 511f
        val y = sqrt(0.5f) * 89f / 511f
        val w = sqrt(1f - x * x - y * y)
        assertArrayEquals(floatArrayOf(w, x, -y, 0f), cloud.rotations.copyOf(4), 1e-6f)
        assertTrue(cloud.positions.all { it.isFinite() } && cloud.opacityLogits.all { it.isFinite() })
        assertEquals(SplatFormat.SPZ, FormatDetector.detect(file.inputStream().use { it.readNBytes(512) }, file.length()))
    }

    @Test
    fun `cactus spz opens in the same frame as the cactus PLY`() {
        // #154: both samples are the same scan; the PLY is read F -> W, so the SPZ must land on the same W
        // coordinates. Stored in RUB the SPZ's y would be mirrored (2nd/98th percentile -0.44/1.88 against
        // 1.86/-0.42 here) and the cactus would show upside down.
        val spz = sample("cactus.spz").inputStream().buffered().use { SpzCodec.read(it) }
        val ply = sample("cactus-sh1.ply").inputStream().buffered().use { PlyReader.readWithReport(it) }.cloud
        for (axis in 0 until 3) {
            val a = FloatArray(spz.count) { spz.positions[3 * it + axis] }.sorted()
            val b = FloatArray(ply.count) { ply.positions[3 * it + axis] }.sorted()
            for (pct in listOf(2, 50, 98)) {
                val pa = a[a.size * pct / 100]
                val pb = b[b.size * pct / 100]
                assertEquals(pb, pa, 0.1f, "axis $axis percentile $pct")
            }
        }
    }

    @Test
    fun `writer stores positions, rotations and SH in F`() {
        val sh = FloatArray(9)
        for (c in 0 until 3) for (kk in 0 until 3) sh[c * 3 + kk] = 0.5f
        val cloud = SplatCloud(1, 1, floatArrayOf(1f, 2f, 3f), FloatArray(3), floatArrayOf(0.5f, 0.5f, 0.5f, 0.5f),
            FloatArray(1), FloatArray(3), sh)
        val raw = gunzip(SpzCodec.write(cloud))
        val o = SpzCodec.HEADER_BYTES
        fun fixed(a: Int): Int {
            val p = o + 3 * a
            var v = (raw[p].toInt() and 0xFF) or ((raw[p + 1].toInt() and 0xFF) shl 8) or
                ((raw[p + 2].toInt() and 0xFF) shl 16)
            if (v and 0x800000 != 0) v = v or -0x1000000
            return v
        }
        assertEquals(listOf(4096, -2 * 4096, -3 * 4096), (0 until 3).map { fixed(it) })
        // rotation W (w, x, y, z) = (.5, .5, .5, .5) is F (.5, .5, -.5, -.5)
        val back = SpzCodec.read(SpzCodec.write(cloud))
        assertRotationClose(floatArrayOf(0.5f, 0.5f, 0.5f, 0.5f), back.rotations.copyOf(4), ROTATION_TOL, "rotation")
        val rot = o + 9 + 1 + 3 + 3
        val comp = (raw[rot].toInt() and 0xFF) or ((raw[rot + 1].toInt() and 0xFF) shl 8) or
            ((raw[rot + 2].toInt() and 0xFF) shl 16) or ((raw[rot + 3].toInt() and 0xFF) shl 24)
        // all four components tie at 0.5; the largest index is 0 (x) and the y, z fields carry the sign bit
        assertEquals(0, comp ushr 30)
        val signs = listOf(20, 10, 0).map { (comp ushr (it + 9)) and 1 } // y, z, w fields from the top
        assertEquals(listOf(1, 1, 0), signs)
        // SH degree 1 W -> F signs -, -, +: stored 0.5 * 128 + 128 = 192 or -0.5 -> 64, coefficient-major
        val shBytes = raw.copyOfRange(raw.size - 9, raw.size).map { it.toInt() and 0xFF }
        assertEquals(listOf(64, 64, 64, 64, 64, 64, 192, 192, 192), shBytes)
    }

    @Test
    fun `golden v2 bytes decode to their known values`() {
        // Two splats, SH degree 1, 8 fractional bits, laid out as the reference's v2 writer does.
        val body = ByteArrayOutputStream()
        body.write(header(version = 2, count = 2, degree = 1, fractionalBits = 8))
        // positions: 0x000100 = 1.0, 0xffff80 = -0.5, 0x000000 = 0 | 0x7fffff, 0x800000, 0x000001
        body.write(byteArrayOf(0x00, 0x01, 0x00, 0x80.toByte(), 0xff.toByte(), 0xff.toByte(), 0, 0, 0))
        body.write(byteArrayOf(0xff.toByte(), 0xff.toByte(), 0x7f, 0, 0, 0x80.toByte(), 1, 0, 0))
        body.write(byteArrayOf(0, 255.toByte())) // alphas
        body.write(byteArrayOf(128.toByte(), 0, 255.toByte(), 64, 64, 64)) // colours
        body.write(byteArrayOf(160.toByte(), 0, 255.toByte(), 1, 2, 3)) // scales
        // rotations x, y, z as u8 (q * 127.5 + 127.5); w = sqrt(1 - |xyz|^2) >= 0
        body.write(byteArrayOf(128.toByte(), 128.toByte(), 128.toByte(), 255.toByte(), 128.toByte(), 128.toByte()))
        // SH degree 1: 9 bytes per splat, [coeff][channel]; splat 0 all 0 (-1.0), splat 1 one 192 at k=1, c=2
        body.write(ByteArray(9))
        body.write(ByteArray(9) { if (it == 1 * 3 + 2) 192.toByte() else 128.toByte() })
        val cloud = SpzCodec.read(gzip(body.toByteArray()))

        assertEquals(2, cloud.count)
        // stored in F; W = (x, -y, -z)
        assertArrayEquals(floatArrayOf(1f, 0.5f, -0f, 8388607f / 256f, 8388608f / 256f, -1f / 256f), cloud.positions)
        assertTrue(cloud.opacity(0) < 0.001f && cloud.opacity(1) > 0.999f)
        assertEquals((128f / 255f - 0.5f) / 0.15f, cloud.shDc[0], 1e-6f)
        assertEquals(-0.5f / 0.15f, cloud.shDc[1], 1e-6f)
        assertArrayEquals(floatArrayOf(0f, -10f, 255f / 16f - 10f, 1f / 16f - 10f, 2f / 16f - 10f, 3f / 16f - 10f),
            cloud.logScales)
        val s = 0.5f / 127.5f
        assertArrayEquals(floatArrayOf(sqrt(1f - 3 * s * s), s, -s, -s), cloud.rotations.copyOf(4), 1e-6f)
        assertArrayEquals(floatArrayOf(0f, 1f, -s, -s), cloud.rotations.copyOfRange(4, 8), 1e-3f)
        // stored -1.0 everywhere; the degree-1 F -> W signs are -, -, + (ADR 0003 rule 5)
        for (c in 0 until 3) {
            assertArrayEquals(floatArrayOf(1f, 1f, -1f), FloatArray(3) { cloud.shRest[cloud.shRestIndex(0, c, it)] })
        }
        assertEquals(-0.5f, cloud.shRest[cloud.shRestIndex(1, 2, 1)])
        assertEquals(8, (9 until 18).count { cloud.shRest[it] == 0f })
    }

    @Test
    fun `a forged count of 10M splats at degree 3 is a format error, not OOM`() {
        val bytes = gzip(header(3, 10_000_000, 3, 12) + ByteArray(8))
        assertTrue(bytes.size < 200)
        val e = assertThrows<SpzFormatException> { SpzCodec.read(ByteArrayInputStream(bytes), maxBytes = 64L shl 20) }
        assertTrue(e.message!!.contains("budget"), e.message)
        assertThrows<SpzFormatException> { SpzCodec.read(bytes) }
    }

    @Test
    fun `wrong magic is a clear error`() {
        val bytes = gzip(header(3, 0, 0, 12, magic = 0x31474e50) + ByteArray(8))
        val e = assertThrows<SpzFormatException> { SpzCodec.read(bytes) }
        assertTrue(e.message!!.contains("magic"), e.message)
    }

    @Test
    fun `a stream that is not gzip is a clear error`() {
        val e = assertThrows<SpzFormatException> { SpzCodec.read(header(3, 0, 0, 12)) }
        assertTrue(e.message!!.contains("not gzip"), e.message)
    }

    @Test
    fun `unknown versions are a clear error`() {
        for (version in listOf(1, 4, 99)) {
            val e = assertThrows<SpzFormatException> { SpzCodec.read(gzip(header(version, 1, 0, 12))) }
            assertTrue(e.message!!.contains("version $version"), e.message)
        }
    }

    @Test
    fun `SH degree above 3 is a clear error`() {
        val e = assertThrows<SpzFormatException> { SpzCodec.read(gzip(header(3, 1, 4, 12))) }
        assertTrue(e.message!!.contains("SH degree 4"), e.message)
    }

    @Test
    fun `truncated payload is a clear error naming the section`() {
        val raw = gunzip(SpzCodec.write(randomCloud(100, 0)))
        // header + positions (900) + alphas (100) + 40 colour bytes: cut inside the colours, at splat 13
        val cut = gzip(raw.copyOf(16 + 900 + 100 + 40))
        val e = assertThrows<SpzFormatException> { SpzCodec.read(cut) }
        assertEquals("SPZ stream truncated in colours at splat 13 of 100", e.message)
    }

    @Test
    fun `truncated gzip stream is a clear error`() {
        val bytes = SpzCodec.write(randomCloud(2000, 1))
        val e = assertThrows<SpzFormatException> { SpzCodec.read(bytes.copyOf(bytes.size / 2)) }
        assertTrue(e.message!!.contains("truncated"), e.message)
        val h = assertThrows<SpzFormatException> { SpzCodec.read(gzip("NGSP".toByteArray())) }
        assertTrue(h.message!!.contains("truncated in header"), h.message)
    }

    @Test
    fun `an empty cloud round-trips`() {
        val empty = SplatCloud(0, 3, FloatArray(0), FloatArray(0), FloatArray(0), FloatArray(0), FloatArray(0),
            FloatArray(0))
        val back = SpzCodec.read(SpzCodec.write(empty))
        assertEquals(0, back.count)
        assertEquals(3, back.shDegree)
        assertFalse(back.positions.isNotEmpty())
    }

    @Test
    fun `a 1M-splat SPZ decodes in under 3 s`() {
        val n = 1_000_000
        val bytes = SpzCodec.write(randomCloud(n, 0, Random(3)))
        SpzCodec.read(SpzCodec.write(randomCloud(20_000, 0))) // warm the JIT on the same code path
        val start = System.nanoTime()
        val cloud = SpzCodec.read(ByteArrayInputStream(bytes))
        val ms = (System.nanoTime() - start) / 1_000_000
        assertEquals(n, cloud.count)
        assertTrue(ms < 3000, "1M splats took $ms ms")
        assertTrue(abs(cloud.positions[3 * n - 1]) <= 100f + POSITION_TOL)
    }
}
