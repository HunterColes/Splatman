package com.huntercoles.splatman.viewer.rendering.pack

import com.huntercoles.splatman.splat.model.SplatCloud
import com.huntercoles.splatman.splat.projection.Covariance
import com.huntercoles.splatman.splat.sh.ShEval
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * A cloud packed for an `RGBA32UI` texture of [width] x [height] texels (ADR 0006). Splat `i` owns texels `2i` and
 * `2i + 1` (row-major, 16 bytes each, little-endian u32 components):
 *
 * - texel `2i`: centre x, y, z as f32 bits; colour RGBA8 (r in the low byte, alpha = sigmoid(opacityLogit)).
 * - texel `2i + 1`: the world covariance `xx, xy, xz, yy, yz, zz` as f16 pairs (first of each pair in the low 16
 *   bits): `(xx, xy)`, `(xz, yy)`, `(yz, zz)`, then 0.
 *
 * [data] holds exactly `width * height * 16` bytes (position 0, limit at the end), the unused tail of the last row
 * zeroed. It belongs to the [SplatPacker] that made it and is overwritten by that packer's next [SplatPacker.pack].
 */
class PackedSplats(val count: Int, val width: Int, val height: Int, val data: ByteBuffer)

/**
 * Activates and packs a [SplatCloud] into the GPU layout of [PackedSplats]. Pure Kotlin, no GL. Reuses one direct
 * little-endian buffer, grown only when a larger cloud arrives; not thread-safe.
 */
class SplatPacker {
    private var buffer: ByteBuffer = allocate(0)
    private val cov = FloatArray(6)

    fun pack(cloud: SplatCloud): PackedSplats {
        val n = cloud.count
        val height = textureHeight(n)
        val bytes = height * WIDTH * TEXEL_BYTES
        if (buffer.capacity() < bytes) buffer = allocate(bytes)
        val buf = buffer
        buf.clear()
        buf.limit(bytes)
        val pos = cloud.positions
        val dc = cloud.shDc
        val logits = cloud.opacityLogits
        for (i in 0 until n) {
            val o = i * SPLAT_BYTES
            buf.putFloat(o, pos[3 * i])
            buf.putFloat(o + 4, pos[3 * i + 1])
            buf.putFloat(o + 8, pos[3 * i + 2])
            val alpha = 1f / (1f + exp(-logits[i]))
            val rgba = colour(dc[3 * i]) or (colour(dc[3 * i + 1]) shl 8) or
                (colour(dc[3 * i + 2]) shl 16) or (unit8(alpha) shl 24)
            buf.putInt(o + 12, rgba)
            Covariance.world(cloud.logScales, 3 * i, cloud.rotations, 4 * i, cov, 0)
            buf.putInt(o + 16, halfPair(cov[0], cov[1]))
            buf.putInt(o + 20, halfPair(cov[2], cov[3]))
            buf.putInt(o + 24, halfPair(cov[4], cov[5]))
            buf.putInt(o + 28, 0)
        }
        var o = n * SPLAT_BYTES
        while (o < bytes) {
            buf.putLong(o, 0L)
            o += 8
        }
        return PackedSplats(n, WIDTH, height, buf)
    }

    companion object {
        /** Texture width in texels (ADR 0006). */
        const val WIDTH = 2048
        const val TEXELS_PER_SPLAT = 2
        const val TEXEL_BYTES = 16
        const val SPLAT_BYTES = TEXELS_PER_SPLAT * TEXEL_BYTES

        private const val C0 = ShEval.C0.toFloat()

        /** Rows needed for [count] splats: `ceil(2 * count / WIDTH)`, 0 for an empty cloud. */
        fun textureHeight(count: Int): Int {
            require(count >= 0) { "count must be >= 0, got $count" }
            return ((count.toLong() * TEXELS_PER_SPLAT + WIDTH - 1) / WIDTH).toInt()
        }

        private fun allocate(bytes: Int): ByteBuffer =
            ByteBuffer.allocateDirect(bytes).order(ByteOrder.LITTLE_ENDIAN)

        private fun colour(shDc: Float): Int = unit8(0.5f + C0 * shDc)

        private fun unit8(v: Float): Int = (v.coerceIn(0f, 1f) * 255f).roundToInt()

        private fun halfPair(lo: Float, hi: Float): Int = Half.fromFloat(lo) or (Half.fromFloat(hi) shl 16)
    }
}
