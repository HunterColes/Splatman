package com.huntercoles.splatman.splat.io.ply

import com.huntercoles.splatman.splat.frames.FrameConversions
import com.huntercoles.splatman.splat.model.SplatCloud
import java.io.BufferedInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads a 3DGS PLY (INRIA layout, ADR 0007) into a [SplatCloud] in frame W; the file is in frame F and converted
 * through [FrameConversions]. Properties are looked up by name in any order, `float` or `double`; extra properties
 * are skipped; the SH degree comes from the number of `f_rest_*` fields (0, 9, 24, 45). ASCII and big-endian files
 * are rejected. The body is streamed in chunks, never read whole. The stream is not closed.
 */
object PlyReader {

    /** ADR 0002: SH rest is kept only while `count * 3 * K * 4` bytes stays under this. */
    const val SH_CAP_BYTES: Long = 64L * 1024 * 1024

    private const val CHUNK_BYTES = 1 shl 18
    private const val MAX_COUNT = Int.MAX_VALUE / 4
    private val NORMALS = setOf("nx", "ny", "nz")

    fun read(input: InputStream): SplatCloud = readWithReport(input).cloud

    /** Reads like [read] and reports what was dropped or skipped; [shCapBytes] is the ADR 0002 SH memory cap. */
    fun readWithReport(input: InputStream, shCapBytes: Long = SH_CAP_BYTES): PlyReadResult {
        val stream = BufferedInputStream(input, CHUNK_BYTES)
        val header = PlyHeader.parse(stream)
        val vertexIndex = header.elements.indexOfFirst { it.name == "vertex" }
        if (vertexIndex < 0) throw PlyFormatException("PLY has no vertex element")
        for (e in header.elements.subList(0, vertexIndex)) {
            if (e.count == 0L) continue
            if (e.hasList) {
                throw PlyFormatException("cannot skip element '${e.name}' with a list property before vertex")
            }
            skipFully(stream, e.count * e.stride, e.name)
        }
        val vertex = header.elements[vertexIndex]
        if (vertex.hasList) throw PlyFormatException("vertex list properties are not supported")
        if (vertex.count > MAX_COUNT) throw PlyFormatException("too many vertices: ${vertex.count} > $MAX_COUNT")
        val count = vertex.count.toInt()

        val byName = vertex.properties.associateBy { it.name }
        fun need(name: String): PlyProperty {
            val p = byName[name] ?: throw PlyFormatException("PLY vertex has no '$name' property")
            if (p.type != PlyType.FLOAT32 && p.type != PlyType.FLOAT64) {
                throw PlyFormatException("property '$name' must be float or double, is ${p.type}")
            }
            return p
        }
        val restInFile = vertex.properties.count { it.name.startsWith("f_rest_") }
        val fileDegree = when (restInFile) {
            0 -> 0
            9 -> 1
            24 -> 2
            45 -> 3
            else -> throw PlyFormatException(
                "PLY has $restInFile f_rest properties; expected 0, 9, 24 or 45 (SH degree 0 to 3)",
            )
        }
        val rest = Array(restInFile) { need("f_rest_$it") }
        val keepRest = restInFile == 0 || count.toLong() * restInFile * 4 < shCapBytes
        val degree = if (keepRest) fileDegree else 0
        val restKept = if (keepRest) restInFile else 0

        val pos = arrayOf(need("x"), need("y"), need("z"))
        val dc = arrayOf(need("f_dc_0"), need("f_dc_1"), need("f_dc_2"))
        val opacity = need("opacity")
        val scale = arrayOf(need("scale_0"), need("scale_1"), need("scale_2"))
        val rot = arrayOf(need("rot_0"), need("rot_1"), need("rot_2"), need("rot_3"))
        val used = (pos + dc + scale + rot + opacity + rest).map { it.name }.toSet()
        val ignored = vertex.properties.map { it.name }.filter { it !in used && it !in NORMALS }

        // The arrays grow as body bytes arrive: the header's count alone never sizes an allocation (hostile input).
        val positions = GrowingFloats(3)
        val logScales = GrowingFloats(3)
        val rotations = GrowingFloats(4)
        val opacityLogits = GrowingFloats(1)
        val shDc = GrowingFloats(3)
        val shRest = GrowingFloats(restKept)
        val all = arrayOf(positions, logScales, rotations, opacityLogits, shDc, shRest)

        val stride = vertex.stride
        val chunkVertices = maxOf(1, minOf(CHUNK_BYTES / stride, count))
        val buf = ByteArray(chunkVertices * stride)
        val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
        var i = 0
        while (i < count) {
            val n = minOf(chunkVertices, count - i)
            val want = n * stride
            val got = readFully(stream, buf, want)
            if (got < want) throw PlyFormatException("PLY body truncated at vertex ${i + got / stride} of $count")
            for (g in all) g.ensure(i + n, count)
            for (j in 0 until n) {
                val base = j * stride
                val v = i + j
                for (a in 0 until 3) {
                    positions.a[3 * v + a] = value(bb, base, pos[a])
                    logScales.a[3 * v + a] = value(bb, base, scale[a])
                    shDc.a[3 * v + a] = value(bb, base, dc[a])
                }
                for (a in 0 until 4) rotations.a[4 * v + a] = value(bb, base, rot[a])
                opacityLogits.a[v] = value(bb, base, opacity)
                for (r in 0 until restKept) shRest.a[v * restKept + r] = value(bb, base, rest[r])
            }
            i += n
        }
        val fileCloud = SplatCloud(
            count, degree, positions.exact(count), logScales.exact(count), rotations.exact(count),
            opacityLogits.exact(count), shDc.exact(count), shRest.exact(count),
        )
        return PlyReadResult(
            FrameConversions.fileToWorld(fileCloud),
            ReadReport(count = count, fileShDegree = fileDegree, shDegree = degree, ignoredProperties = ignored),
        )
    }

    private fun value(bb: ByteBuffer, base: Int, p: PlyProperty): Float =
        if (p.type == PlyType.FLOAT32) bb.getFloat(base + p.offset) else bb.getDouble(base + p.offset).toFloat()

    /** Reads up to [want] bytes into [buf]; returns how many arrived before the end of the stream. */
    private fun readFully(input: InputStream, buf: ByteArray, want: Int): Int {
        var got = 0
        while (got < want) {
            val r = input.read(buf, got, want - got)
            if (r < 0) break
            got += r
        }
        return got
    }

    private fun skipFully(input: InputStream, bytes: Long, element: String) {
        var left = bytes
        while (left > 0) {
            val s = input.skip(left)
            if (s > 0) {
                left -= s
            } else if (input.read() >= 0) {
                left--
            } else {
                throw PlyFormatException("PLY body truncated in element '$element'")
            }
        }
    }

    /** [width] floats per vertex; capacity doubles as vertices arrive and never passes the header's count. */
    private class GrowingFloats(private val width: Int) {
        var a = FloatArray(0)
            private set
        private var vertices = 0

        fun ensure(needed: Int, count: Int) {
            if (needed <= vertices) return
            vertices = minOf(maxOf(needed.toLong(), 2L * vertices), count.toLong()).toInt()
            a = a.copyOf(vertices * width)
        }

        fun exact(count: Int): FloatArray = if (a.size == count * width) a else a.copyOf(count * width)
    }
}
