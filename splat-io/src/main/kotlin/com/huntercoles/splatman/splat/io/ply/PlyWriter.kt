package com.huntercoles.splatman.splat.io.ply

import com.huntercoles.splatman.splat.frames.FrameConversions
import com.huntercoles.splatman.splat.model.SplatCloud
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Writes a [SplatCloud] (frame W) as a 3DGS PLY (INRIA layout, ADR 0007) in frame F, converted through
 * [FrameConversions]. The header is fixed text with no comments and normals are written as 0, so the same cloud
 * always gives the same bytes. The stream is flushed, not closed.
 */
object PlyWriter {

    private const val CHUNK_BYTES = 1 shl 18

    /** The exact header ADR 0007 names for [count] splats at [shDegree], ending in `end_header\n`. */
    fun header(count: Int, shDegree: Int): String {
        val restFields = 3 * SplatCloud.restCoefficients(shDegree)
        val names = listOf("x", "y", "z", "nx", "ny", "nz", "f_dc_0", "f_dc_1", "f_dc_2") +
            List(restFields) { "f_rest_$it" } +
            listOf("opacity", "scale_0", "scale_1", "scale_2", "rot_0", "rot_1", "rot_2", "rot_3")
        return buildString {
            append("ply\n")
            append("format binary_little_endian 1.0\n")
            append("element vertex ").append(count).append('\n')
            for (n in names) append("property float ").append(n).append('\n')
            append("end_header\n")
        }
    }

    fun write(cloud: SplatCloud, output: OutputStream) {
        val file = FrameConversions.worldToFile(cloud)
        output.write(header(file.count, file.shDegree).toByteArray(Charsets.US_ASCII))
        val rest = 3 * file.shRestPerChannel
        val stride = (17 + rest) * 4
        val chunkVertices = maxOf(1, CHUNK_BYTES / stride)
        val bb = ByteBuffer.allocate(chunkVertices * stride).order(ByteOrder.LITTLE_ENDIAN)
        var i = 0
        while (i < file.count) {
            val n = minOf(chunkVertices, file.count - i)
            bb.clear()
            for (v in i until i + n) {
                for (a in 0 until 3) bb.putFloat(file.positions[3 * v + a])
                for (a in 0 until 3) bb.putFloat(0f)
                for (a in 0 until 3) bb.putFloat(file.shDc[3 * v + a])
                for (r in 0 until rest) bb.putFloat(file.shRest[v * rest + r])
                bb.putFloat(file.opacityLogits[v])
                for (a in 0 until 3) bb.putFloat(file.logScales[3 * v + a])
                for (a in 0 until 4) bb.putFloat(file.rotations[4 * v + a])
            }
            output.write(bb.array(), 0, bb.position())
            i += n
        }
        output.flush()
    }
}
