package com.huntercoles.splatman.splat.io.splat

import com.huntercoles.splatman.splat.frames.FrameConversions
import com.huntercoles.splatman.splat.model.SplatCloud
import com.huntercoles.splatman.splat.sh.ShEval
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * antimatter15 `.splat` (ADR 0007): 32 bytes per splat, little-endian, no header, frame F. Position 3 x f32,
 * scale 3 x f32 (`exp(logScale)`), colour r, g, b, a as u8, rotation w, x, y, z as u8 (`q * 128 + 128`).
 * SH0 only: a cloud's `shRest` is dropped on write. The W <-> F change is done by [FrameConversions] alone.
 */
object SplatCodec {
    const val BYTES_PER_SPLAT = 32

    private val C0 = ShEval.C0.toFloat()
    private const val ALPHA_MIN = 1f / 255f
    private const val ALPHA_MAX = 254f / 255f

    fun read(input: InputStream): SplatCloud = read(input.readBytes())

    fun read(bytes: ByteArray): SplatCloud {
        require(bytes.size % BYTES_PER_SPLAT == 0) {
            ".splat size must be a multiple of $BYTES_PER_SPLAT bytes, got ${bytes.size}"
        }
        val n = bytes.size / BYTES_PER_SPLAT
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val positions = FloatArray(3 * n)
        val logScales = FloatArray(3 * n)
        val rotations = FloatArray(4 * n)
        val opacity = FloatArray(n)
        val shDc = FloatArray(3 * n)
        for (i in 0 until n) {
            for (a in 0 until 3) positions[3 * i + a] = buf.float
            for (a in 0 until 3) logScales[3 * i + a] = ln(buf.float)
            for (c in 0 until 3) shDc[3 * i + c] = (u8(buf.get()) / 255f - 0.5f) / C0
            val alpha = (u8(buf.get()) / 255f).coerceIn(ALPHA_MIN, ALPHA_MAX)
            opacity[i] = ln(alpha / (1f - alpha))
            val q = FloatArray(4) { (u8(buf.get()) - 128f) / 128f }
            if (q.all { it == 0f }) q[0] = 1f // all bytes 128: no rotation stored, take identity
            q.copyInto(rotations, 4 * i)
        }
        val file = SplatCloud(n, 0, positions, logScales, rotations, opacity, shDc, FloatArray(0))
        return FrameConversions.fileToWorld(file)
    }

    fun write(cloud: SplatCloud, output: OutputStream) {
        output.write(write(cloud))
    }

    fun write(cloud: SplatCloud): ByteArray {
        val f = FrameConversions.worldToFile(cloud)
        val buf = ByteBuffer.allocate(f.count * BYTES_PER_SPLAT).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until f.count) {
            for (a in 0 until 3) buf.putFloat(f.positions[3 * i + a])
            for (a in 0 until 3) buf.putFloat(exp(f.logScales[3 * i + a]))
            for (c in 0 until 3) buf.put(toU8((0.5f + C0 * f.shDc[3 * i + c]) * 255f))
            buf.put(toU8(f.opacity(i) * 255f))
            val w = f.rotations[4 * i]
            val x = f.rotations[4 * i + 1]
            val y = f.rotations[4 * i + 2]
            val z = f.rotations[4 * i + 3]
            val len = sqrt(w * w + x * x + y * y + z * z)
            for (v in floatArrayOf(w, x, y, z)) buf.put(toU8(v / len * 128f + 128f))
        }
        return buf.array()
    }

    private fun u8(b: Byte): Int = b.toInt() and 0xFF

    private fun toU8(v: Float): Byte = (if (v.isNaN()) 0 else v.roundToInt().coerceIn(0, 255)).toByte()
}
