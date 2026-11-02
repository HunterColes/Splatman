package com.huntercoles.splatman.splat.io.splat

import com.huntercoles.splatman.splat.model.SplatCloud
import com.huntercoles.splatman.splat.sh.ShEval
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.random.Random

class SplatCodecTest {

    private val c0 = ShEval.C0.toFloat()

    private fun randomCloud(n: Int, rnd: Random = Random(7)): SplatCloud = SplatCloud(
        count = n,
        shDegree = 0,
        positions = FloatArray(3 * n) { rnd.nextFloat() * 10f - 5f },
        logScales = FloatArray(3 * n) { rnd.nextFloat() * 6f - 5f },
        rotations = FloatArray(4 * n) { rnd.nextFloat() * 2f - 1f },
        opacityLogits = FloatArray(n) { rnd.nextFloat() * 8f - 4f },
        shDc = FloatArray(3 * n) { rnd.nextFloat() * 3f - 1.5f },
        shRest = FloatArray(0),
    )

    @Test
    fun `round trip keeps scale exact, colour within 1 over 255, rotation within 1 over 128`() {
        val src = randomCloud(200)
        val out = ByteArrayOutputStream()
        SplatCodec.write(src, out)
        assertEquals(200 * 32, out.size())
        val back = SplatCodec.read(ByteArrayInputStream(out.toByteArray()))
        assertEquals(src.count, back.count)
        assertEquals(0, back.shDegree)
        for (i in 0 until src.count) {
            for (a in 0 until 3) {
                assertEquals(src.positions[3 * i + a], back.positions[3 * i + a], 0f)
                assertEquals(exp(src.logScales[3 * i + a]), exp(back.logScales[3 * i + a]), 0f)
                val colourIn = (0.5f + c0 * src.shDc[3 * i + a]).coerceIn(0f, 1f)
                val colourOut = 0.5f + c0 * back.shDc[3 * i + a]
                assertEquals(colourIn, colourOut, 1f / 255f)
            }
            val aIn = src.opacity(i).coerceIn(1f / 255f, 254f / 255f)
            assertEquals(aIn, back.opacity(i), 1f / 255f + 1e-6f)
            val qi = src.rotation(i)
            val qo = back.rotation(i)
            val dot = qi.w * qo.w + qi.x * qo.x + qi.y * qo.y + qi.z * qo.z
            val s = if (dot < 0f) -1f else 1f
            assertTrue(abs(qi.w - s * qo.w) <= 1f / 128f, "w $i")
            assertTrue(abs(qi.x - s * qo.x) <= 1f / 128f, "x $i")
            assertTrue(abs(qi.y - s * qo.y) <= 1f / 128f, "y $i")
            assertTrue(abs(qi.z - s * qo.z) <= 1f / 128f, "z $i")
        }
    }

    @Test
    fun `file bytes are in frame F, w x y z order`() {
        val r = 1f / sqrt(2f)
        val cloud = SplatCloud(
            1, 0, floatArrayOf(1f, 2f, 3f), floatArrayOf(0f, 0f, 0f), floatArrayOf(r, 0f, r, 0f),
            floatArrayOf(0f), floatArrayOf(0f, 0f, 0f), FloatArray(0),
        )
        val buf = ByteBuffer.wrap(SplatCodec.write(cloud)).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(1f, buf.float); assertEquals(-2f, buf.float); assertEquals(-3f, buf.float)
        assertEquals(1f, buf.float); assertEquals(1f, buf.float); assertEquals(1f, buf.float)
        val bytes = ByteArray(8).also { buf.get(it) }.map { it.toInt() and 0xFF }
        assertEquals(listOf(128, 128, 128, 128), bytes.subList(0, 4))
        assertEquals(listOf(219, 128, 37, 128), bytes.subList(4, 8))
    }

    @Test
    fun `size not a multiple of 32 is rejected`() {
        assertThrows<IllegalArgumentException> { SplatCodec.read(ByteArray(33)) }
        assertThrows<IllegalArgumentException> { SplatCodec.read(ByteArray(31)) }
    }

    @Test
    fun `alpha 0 and 255 import to finite logits`() {
        val bytes = ByteArray(64)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for ((i, alpha) in listOf(0, 255).withIndex()) {
            buf.position(i * 32)
            repeat(3) { buf.putFloat(0f) }
            repeat(3) { buf.putFloat(1f) }
            buf.put(0).put(0).put(0).put(alpha.toByte())
            buf.put(255.toByte()).put(128.toByte()).put(128.toByte()).put(128.toByte())
        }
        val cloud = SplatCodec.read(bytes)
        assertTrue(cloud.opacityLogits.all { it.isFinite() })
        assertTrue(cloud.opacityLogits[0] < 0f && cloud.opacityLogits[1] > 0f)
    }
}
