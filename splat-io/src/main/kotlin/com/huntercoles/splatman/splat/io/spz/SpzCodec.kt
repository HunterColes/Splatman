package com.huntercoles.splatman.splat.io.spz

import com.huntercoles.splatman.splat.frames.FrameConversions
import com.huntercoles.splatman.splat.math.Vec3
import com.huntercoles.splatman.splat.model.SplatCloud
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipException
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Niantic SPZ (ADR 0007), following the MIT reference github.com/nianticlabs/spz (`load-spz.cc`) field by field.
 * A gzip stream holding a 16-byte [SpzHeader], then one section per field, each for all splats in order:
 *
 * - positions: 3 x 24-bit signed little-endian fixed point, `value / 2^fractionalBits` metres (written with 12 bits);
 * - alphas: u8, `sigmoid(opacityLogit) * 255`;
 * - colours: 3 x u8, `shDc * (0.15 * 255) + 127.5`;
 * - scales: 3 x u8, `(logScale + 10) * 16`;
 * - rotations, quaternion x, y, z, w: v2 stores x, y, z as u8 `q * 127.5 + 127.5` with `w >= 0` derived;
 *   v3 stores "smallest three": a u32 whose top 2 bits index the largest component and three 10-bit fields
 *   (sign bit, 9-bit magnitude of `|q| / sqrt(1/2)`) hold the others, first one highest;
 * - SH: 3K x u8 per splat, `(b - 128) / 128`, coefficient-major with the colour channel innermost; written with
 *   5 bits for degree 1 and 4 bits above (bucketed to 8 and 16 steps).
 *
 * The frame is F, the PLY file frame (RDF, +Y down; ADR 0003 rule 5): tools that convert PLY to SPZ store the
 * coordinates as they are (the bundled cactus.spz, #154). Positions, quaternions and SH signs go W <-> F through
 * [FrameConversions], per splat and in place, as the sections stream. Reads versions 2 and 3, writes version 3. The stream is decoded section by section through
 * one fixed-size buffer; no full decompressed copy is held.
 *
 * Deviation from the reference: alpha bytes 0 and 255 decode to finite logits (alpha 0.25/255 and 254.75/255,
 * which re-encode to the same bytes) rather than -inf and +inf; positions beyond the 24-bit range are clamped on
 * write rather than wrapped.
 */
object SpzCodec {
    /** `NGSP` read as a little-endian u32. */
    const val MAGIC = 0x5053474e
    const val WRITE_VERSION = 3
    val READ_VERSIONS = 2..3
    const val HEADER_BYTES = 16
    /** Fractional bits of positions on write (the reference default): 1/4096 m steps, range +-2048 m. */
    const val FRACTIONAL_BITS = 12
    /** DC colour quantisation scale of the reference. */
    const val COLOR_SCALE = 0.15f
    /** Header flag bit: the cloud was trained with antialiasing. */
    const val FLAG_ANTIALIASED = 0x1
    /** The reference's limit on points per file; a larger count is treated as a corrupt header. */
    const val MAX_POINTS = 10_000_000

    /** Bucket sizes of the SH quantisation on write: 5 bits for degree 1, 4 bits for degrees 2 and 3. */
    const val SH1_BUCKET = 1 shl (8 - 5)
    const val SH_REST_BUCKET = 1 shl (8 - 4)

    private const val CHUNK_BYTES = 64 * 1024
    private const val ROT_MAG_BITS = 9
    private const val ROT_MAG_MASK = (1 shl ROT_MAG_BITS) - 1
    private val SQRT1_2 = sqrt(0.5f)
    private const val FIXED_MAX = (1 shl 23) - 1
    private const val FIXED_MIN = -(1 shl 23)
    private const val ALPHA_EDGE = 0.25f / 255f

    private val ALPHA_LOGIT = FloatArray(256) {
        val a = (it / 255f).coerceIn(ALPHA_EDGE, 1f - ALPHA_EDGE)
        ln(a / (1f - a))
    }
    private val COLOUR = FloatArray(256) { (it / 255f - 0.5f) / COLOR_SCALE }
    private val LOG_SCALE = FloatArray(256) { it / 16f - 10f }
    private val SH = FloatArray(256) { (it - 128f) / 128f }

    // ---- read ----

    fun read(bytes: ByteArray): SplatCloud = read(ByteArrayInputStream(bytes))

    /**
     * Decodes a whole SPZ stream (gzip included, stored in frame F) into a [SplatCloud] in frame W. Does not close [input].
     * A header whose decoded arrays would exceed [maxBytes] (default: half the heap) is rejected with
     * [SpzFormatException] before anything is allocated.
     */
    fun read(input: InputStream, maxBytes: Long = defaultBudget()): SplatCloud {
        val z = gunzip(input)
        val h = headerFrom(z)
        val n = h.count
        val k = SplatCloud.restCoefficients(h.shDegree)
        val need = decodedBytes(n, k)
        if (need > maxBytes) {
            throw SpzFormatException(
                "SPZ count $n at SH degree ${h.shDegree} needs $need bytes, above the budget of $maxBytes",
            )
        }
        val positions = FloatArray(3 * n)
        val logScales = FloatArray(3 * n)
        val rotations = FloatArray(4 * n)
        val opacity = FloatArray(n)
        val shDc = FloatArray(3 * n)
        val shRest = FloatArray(n * 3 * k)
        val buf = ByteArray(CHUNK_BYTES)

        val posScale = Math.scalb(1f, -h.fractionalBits)
        section(z, buf, 9, n, "positions") { b, o, i ->
            for (a in 0 until 3) {
                val p = o + 3 * a
                var fixed = u8(b[p]) or (u8(b[p + 1]) shl 8) or (u8(b[p + 2]) shl 16)
                if (fixed and 0x800000 != 0) fixed = fixed or -0x1000000
                positions[3 * i + a] = fixed * posScale
            }
            val w = FrameConversions.fileToWorld(Vec3(positions[3 * i], positions[3 * i + 1], positions[3 * i + 2]))
            positions[3 * i] = w.x
            positions[3 * i + 1] = w.y
            positions[3 * i + 2] = w.z
        }
        section(z, buf, 1, n, "alphas") { b, o, i -> opacity[i] = ALPHA_LOGIT[u8(b[o])] }
        section(z, buf, 3, n, "colours") { b, o, i ->
            for (c in 0 until 3) shDc[3 * i + c] = COLOUR[u8(b[o + c])]
        }
        section(z, buf, 3, n, "scales") { b, o, i ->
            for (a in 0 until 3) logScales[3 * i + a] = LOG_SCALE[u8(b[o + a])]
        }
        val xyzw = FloatArray(4)
        if (h.version >= 3) {
            section(z, buf, 4, n, "rotations") { b, o, i ->
                unpackSmallestThree(b, o, xyzw)
                putRotation(rotations, i, xyzw)
            }
        } else {
            section(z, buf, 3, n, "rotations") { b, o, i ->
                var sum = 0f
                for (a in 0 until 3) {
                    val v = (u8(b[o + a]) - 127.5f) / 127.5f
                    xyzw[a] = v
                    sum += v * v
                }
                xyzw[3] = sqrt(maxOf(0f, 1f - sum))
                putRotation(rotations, i, xyzw)
            }
        }
        if (k > 0) {
            val signs = FrameConversions.shRestSigns(h.shDegree)
            section(z, buf, 3 * k, n, "sh") { b, o, i ->
                val base = i * 3 * k
                for (kk in 0 until k) for (c in 0 until 3) {
                    shRest[base + c * k + kk] = SH[u8(b[o + kk * 3 + c])] * signs[kk]
                }
            }
        }
        return SplatCloud(n, h.shDegree, positions, logScales, rotations, opacity, shDc, shRest)
    }

    /** Bytes of float arrays [read] allocates for [n] splats with [k] rest coefficients per channel. */
    internal fun decodedBytes(n: Int, k: Int): Long = 4L * n * (3 + 3 + 4 + 1 + 3 + 3L * k)

    private fun defaultBudget(): Long = Runtime.getRuntime().maxMemory() / 2

    /** Reads only the header of an SPZ stream (gzip included). Does not close [input]. */
    fun readHeader(input: InputStream): SpzHeader = headerFrom(gunzip(input))

    private fun gunzip(input: InputStream): InputStream = try {
        GZIPInputStream(input, CHUNK_BYTES)
    } catch (e: ZipException) {
        throw SpzFormatException("not an SPZ stream: not gzip (${e.message})", e)
    } catch (e: IOException) {
        throw SpzFormatException("not an SPZ stream: gzip header truncated (${e.message})", e)
    }

    private fun headerFrom(z: InputStream): SpzHeader {
        val bytes = ByteArray(HEADER_BYTES)
        val got = fill(z, bytes, 0, HEADER_BYTES, "header")
        if (got < 4) throw SpzFormatException("SPZ stream truncated in header: got $got of $HEADER_BYTES bytes")
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val magic = b.int
        if (magic != MAGIC) {
            throw SpzFormatException(
                "not an SPZ stream: magic 0x%08x, expected NGSP (0x%08x)".format(magic, MAGIC),
            )
        }
        if (got < HEADER_BYTES) {
            throw SpzFormatException("SPZ stream truncated in header: got $got of $HEADER_BYTES bytes")
        }
        val version = b.int
        if (version !in READ_VERSIONS) {
            throw SpzFormatException(
                "SPZ version ${version.toUInt()} is not supported (reads ${READ_VERSIONS.first} and ${READ_VERSIONS.last})",
            )
        }
        val count = b.int
        if (count < 0 || count > MAX_POINTS) {
            throw SpzFormatException("SPZ count ${count.toUInt()} is above the limit of $MAX_POINTS splats")
        }
        val degree = u8(b.get())
        if (degree > 3) throw SpzFormatException("SPZ SH degree $degree is not supported (0..3)")
        val fractionalBits = u8(b.get())
        val flags = u8(b.get())
        return SpzHeader(version, count, degree, fractionalBits, flags)
    }

    /**
     * Streams [n] records of [stride] bytes through [buf], calling [decode] with the buffer, the record's offset in
     * it and the splat index.
     */
    private inline fun section(
        z: InputStream,
        buf: ByteArray,
        stride: Int,
        n: Int,
        name: String,
        decode: (ByteArray, Int, Int) -> Unit,
    ) {
        val perChunk = buf.size / stride
        var i = 0
        while (i < n) {
            val m = minOf(perChunk, n - i)
            val want = m * stride
            val got = fill(z, buf, 0, want, name)
            if (got < want) {
                throw SpzFormatException("SPZ stream truncated in $name at splat ${i + got / stride} of $n")
            }
            for (j in 0 until m) decode(buf, j * stride, i + j)
            i += m
        }
    }

    /** Reads up to [len] bytes; returns how many arrived before the end of the stream. */
    private fun fill(z: InputStream, buf: ByteArray, off: Int, len: Int, name: String): Int {
        var got = 0
        try {
            while (got < len) {
                val r = z.read(buf, off + got, len - got)
                if (r < 0) break
                got += r
            }
        } catch (e: IOException) {
            throw SpzFormatException("SPZ stream truncated or corrupt in $name: ${e.message}", e)
        }
        return got
    }

    private fun unpackSmallestThree(b: ByteArray, o: Int, xyzw: FloatArray) {
        var comp = u8(b[o]) or (u8(b[o + 1]) shl 8) or (u8(b[o + 2]) shl 16) or (u8(b[o + 3]) shl 24)
        val largest = comp ushr 30
        var sum = 0f
        for (a in 3 downTo 0) {
            if (a == largest) continue
            val mag = comp and ROT_MAG_MASK
            val negative = (comp ushr ROT_MAG_BITS) and 1 == 1
            comp = comp ushr (ROT_MAG_BITS + 1)
            val v = SQRT1_2 * mag / ROT_MAG_MASK
            xyzw[a] = if (negative) -v else v
            sum += v * v
        }
        xyzw[largest] = sqrt(maxOf(0f, 1f - sum))
    }

    private fun putRotation(rotations: FloatArray, i: Int, xyzw: FloatArray) {
        val q = FrameConversions.fileToWorld(FrameConversions.quatFromXyzw(xyzw))
        rotations[4 * i] = q.w
        rotations[4 * i + 1] = q.x
        rotations[4 * i + 2] = q.y
        rotations[4 * i + 3] = q.z
    }

    // ---- write ----

    fun write(cloud: SplatCloud): ByteArray = ByteArrayOutputStream().also { write(cloud, it) }.toByteArray()

    /**
     * Encodes [cloud] (frame W) as an SPZ v3 gzip stream in frame F onto [output]; the gzip trailer is written, [output] is not
     * closed. Output is byte-identical across runs (gzip mtime 0).
     */
    fun write(cloud: SplatCloud, output: OutputStream) {
        val n = cloud.count
        val k = cloud.shRestPerChannel
        val keepOpen = object : FilterOutputStream(output) {
            override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
            override fun close() = flush()
        }
        GZIPOutputStream(keepOpen, CHUNK_BYTES).use { z ->
            val header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            header.putInt(MAGIC).putInt(WRITE_VERSION).putInt(n)
            header.put(cloud.shDegree.toByte()).put(FRACTIONAL_BITS.toByte()).put(0).put(0)
            z.write(header.array())
            val buf = ByteArray(CHUNK_BYTES)

            val posScale = (1 shl FRACTIONAL_BITS).toDouble()
            emit(z, buf, 9, n) { b, o, i ->
                val f = FrameConversions.worldToFile(
                    Vec3(cloud.positions[3 * i], cloud.positions[3 * i + 1], cloud.positions[3 * i + 2]),
                )
                for (a in 0 until 3) {
                    val v = when (a) { 0 -> f.x; 1 -> f.y; else -> f.z }
                    val fixed = Math.round(v * posScale)
                        .coerceIn(FIXED_MIN.toLong(), FIXED_MAX.toLong()).toInt()
                    b[o + 3 * a] = fixed.toByte()
                    b[o + 3 * a + 1] = (fixed shr 8).toByte()
                    b[o + 3 * a + 2] = (fixed shr 16).toByte()
                }
            }
            emit(z, buf, 1, n) { b, o, i -> b[o] = toU8(sigmoid(cloud.opacityLogits[i]) * 255f) }
            emit(z, buf, 3, n) { b, o, i ->
                for (c in 0 until 3) b[o + c] = toU8(cloud.shDc[3 * i + c] * (COLOR_SCALE * 255f) + 0.5f * 255f)
            }
            emit(z, buf, 3, n) { b, o, i ->
                for (a in 0 until 3) b[o + a] = toU8((cloud.logScales[3 * i + a] + 10f) * 16f)
            }
            emit(z, buf, 4, n) { b, o, i ->
                packSmallestThree(FrameConversions.quatToXyzw(FrameConversions.worldToFile(cloud.rotation(i))), b, o)
            }
            if (k > 0) {
                val signs = FrameConversions.shRestSigns(cloud.shDegree)
                emit(z, buf, 3 * k, n) { b, o, i ->
                    val base = i * 3 * k
                    for (kk in 0 until k) for (c in 0 until 3) {
                        val bucket = if (kk < 3) SH1_BUCKET else SH_REST_BUCKET
                        b[o + kk * 3 + c] = quantizeSh(cloud.shRest[base + c * k + kk] * signs[kk], bucket)
                    }
                }
            }
        }
    }

    private inline fun emit(
        z: OutputStream,
        buf: ByteArray,
        stride: Int,
        n: Int,
        encode: (ByteArray, Int, Int) -> Unit,
    ) {
        val perChunk = buf.size / stride
        var i = 0
        while (i < n) {
            val m = minOf(perChunk, n - i)
            for (j in 0 until m) encode(buf, j * stride, i + j)
            z.write(buf, 0, m * stride)
            i += m
        }
    }

    /** The reference's `packQuaternionSmallestThree` on a unit quaternion x, y, z, w. */
    private fun packSmallestThree(q: FloatArray, b: ByteArray, o: Int) {
        var largest = 0
        for (a in 1 until 4) if (abs(q[a]) > abs(q[largest])) largest = a
        val negate = q[largest] < 0f
        var comp = largest
        for (a in 0 until 4) {
            if (a == largest) continue
            val negBit = if ((q[a] < 0f) != negate) 1 else 0
            val mag = (ROT_MAG_MASK * (abs(q[a]) / SQRT1_2) + 0.5f).toInt().coerceIn(0, ROT_MAG_MASK)
            comp = (comp shl (ROT_MAG_BITS + 1)) or (negBit shl ROT_MAG_BITS) or mag
        }
        b[o] = comp.toByte()
        b[o + 1] = (comp shr 8).toByte()
        b[o + 2] = (comp shr 16).toByte()
        b[o + 3] = (comp ushr 24).toByte()
    }

    /** The reference's `quantizeSH`: to the u8 grid `x * 128 + 128`, then to the nearest multiple of [bucket]. */
    private fun quantizeSh(x: Float, bucket: Int): Byte {
        if (x.isNaN()) return 128.toByte()
        var q = (x.coerceIn(-2f, 2f) * 128f).roundToInt() + 128
        q = (q + bucket / 2) / bucket * bucket
        return q.coerceIn(0, 255).toByte()
    }

    private fun sigmoid(x: Float): Float = 1f / (1f + exp(-x))

    private fun u8(b: Byte): Int = b.toInt() and 0xFF

    private fun toU8(v: Float): Byte = (if (v.isNaN()) 0 else v.roundToInt().coerceIn(0, 255)).toByte()
}
