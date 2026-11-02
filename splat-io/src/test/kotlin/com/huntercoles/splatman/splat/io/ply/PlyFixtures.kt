package com.huntercoles.splatman.splat.io.ply

import com.huntercoles.splatman.splat.model.SplatCloud
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

/** Test helpers: seeded clouds and a hand-rolled PLY builder independent of [PlyWriter]. */
internal object PlyFixtures {

    fun seededCloud(count: Int, shDegree: Int, seed: Int): SplatCloud {
        val rnd = Random(seed)
        fun f(n: Int, lo: Float, hi: Float) = FloatArray(n) { lo + (hi - lo) * rnd.nextFloat() }
        val k = SplatCloud.restCoefficients(shDegree)
        val rotations = f(4 * count, -1f, 1f)
        for (i in 0 until count) rotations[4 * i] += 2f // never a zero quaternion
        return SplatCloud(
            count = count,
            shDegree = shDegree,
            positions = f(3 * count, -10f, 10f),
            logScales = f(3 * count, -6f, 1f),
            rotations = rotations,
            opacityLogits = f(count, -5f, 5f),
            shDc = f(3 * count, -2f, 2f),
            shRest = f(count * 3 * k, -0.5f, 0.5f),
        )
    }

    fun write(cloud: SplatCloud): ByteArray = ByteArrayOutputStream().also { PlyWriter.write(cloud, it) }.toByteArray()

    fun read(bytes: ByteArray): SplatCloud = PlyReader.read(ByteArrayInputStream(bytes))

    /** An input stream that hands out at most [step] bytes per read, to exercise partial reads. */
    fun trickle(bytes: ByteArray, step: Int = 7): InputStream = object : InputStream() {
        private var pos = 0
        override fun read(): Int = if (pos < bytes.size) bytes[pos++].toInt() and 0xff else -1
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (pos >= bytes.size) return -1
            val n = minOf(len, step, bytes.size - pos)
            System.arraycopy(bytes, pos, b, off, n)
            pos += n
            return n
        }
    }

    /** A PLY vertex property: [type] is a PLY type name (`float`, `double`, `uchar`, `int`, ...). */
    data class Prop(val type: String, val name: String)

    /** Builds a little-endian PLY with [props] in the given order and one row of values per vertex. */
    fun build(
        props: List<Prop>,
        rows: List<DoubleArray>,
        format: String = "binary_little_endian",
        count: Int = rows.size,
    ): ByteArray {
        val header = buildString {
            append("ply\nformat $format 1.0\ncomment built by a test\nelement vertex $count\n")
            for (p in props) append("property ${p.type} ${p.name}\n")
            append("end_header\n")
        }
        val out = ByteArrayOutputStream()
        out.write(header.toByteArray(Charsets.US_ASCII))
        for (row in rows) {
            require(row.size == props.size)
            for ((p, v) in props.zip(row.toList())) {
                val bb = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                when (p.type) {
                    "float" -> bb.putFloat(v.toFloat())
                    "double" -> bb.putDouble(v)
                    "uchar" -> bb.put(v.toInt().toByte())
                    "int" -> bb.putInt(v.toInt())
                    else -> error("type ${p.type}")
                }
                out.write(bb.array(), 0, bb.position())
            }
        }
        return out.toByteArray()
    }

    /** The ADR 0007 property names for [shDegree], in the writer's order, without normals. */
    fun layoutNames(shDegree: Int): List<String> =
        listOf("x", "y", "z", "f_dc_0", "f_dc_1", "f_dc_2") +
            List(3 * SplatCloud.restCoefficients(shDegree)) { "f_rest_$it" } +
            listOf("opacity", "scale_0", "scale_1", "scale_2", "rot_0", "rot_1", "rot_2", "rot_3")
}
