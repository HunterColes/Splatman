package com.huntercoles.splatman.splat.io.gltf

import com.huntercoles.splatman.splat.model.TriangleMesh
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow

/**
 * Writes a [TriangleMesh] (frame W) as a binary glTF 2.0 (`.glb`, ADR 0018 point 7). glTF is frame W, so positions
 * and normals are copied as stored. One scene, one node with no transform, one mesh, one TRIANGLES primitive:
 * POSITION and NORMAL float32 VEC3, COLOR_0 unsigned-short normalised VEC4 (display sRGB to linear per
 * IEC 61966-2-1, alpha 65535), UNSIGNED_INT indices, one buffer view per accessor. One unlit, double-sided material
 * (`KHR_materials_unlit` in `extensionsUsed` only). No JSON library: the JSON chunk is built by hand.
 *
 * Every POSITION min/max number is left-padded with spaces (JSON whitespace) to [NUMBER_WIDTH] characters, so the
 * JSON length, and so [byteSize], depends on the counts only. The output is byte-identical across runs. The mesh
 * must hold at least one vertex and one triangle, finite positions and indices in range. The stream is flushed, not
 * closed.
 */
object GlbMeshWriter {

    const val UNLIT = "KHR_materials_unlit"

    private const val GLB_MAGIC = 0x46546C67 // "glTF"
    private const val GLB_VERSION = 2
    private const val CHUNK_JSON = 0x4E4F534A // "JSON"
    private const val CHUNK_BIN = 0x004E4942 // "BIN\0"
    private const val FLOAT = 5126
    private const val UNSIGNED_SHORT = 5123
    private const val UNSIGNED_INT = 5125
    private const val ARRAY_BUFFER = 34962
    private const val ELEMENT_ARRAY_BUFFER = 34963
    private const val TRIANGLES = 4
    private const val CHUNK_BYTES = 1 shl 18
    private const val MAX_GLB_BYTES = 0xFFFF_FFFFL

    /** The longest `Float.toString` ("-1.17549435E-38"); every min/max number is padded to this width. */
    internal const val NUMBER_WIDTH = 15

    /** The exact length in bytes of the `.glb` [write] gives for a mesh of these counts (may exceed 4 GiB). */
    fun byteSize(vertexCount: Int, triangleCount: Int): Long {
        require(vertexCount > 0 && triangleCount > 0) {
            "a glTF mesh needs at least one vertex and one triangle, got $vertexCount and $triangleCount"
        }
        val json = json(vertexCount, triangleCount, PLACEHOLDER, PLACEHOLDER).toByteArray(Charsets.UTF_8).size
        return 12L + 8 + pad4(json) + 8 + binBytes(vertexCount, triangleCount)
    }

    /** [byteSize], or an [IllegalArgumentException] when that is over the 4 GiB GLB limit. */
    internal fun requireFits(vertexCount: Int, triangleCount: Int): Long {
        val total = byteSize(vertexCount, triangleCount)
        require(total <= MAX_GLB_BYTES) { "the .glb would be $total bytes, over the 4 GiB GLB limit" }
        return total
    }

    fun write(mesh: TriangleMesh, out: OutputStream) {
        val n = mesh.vertexCount
        val t = mesh.triangleCount
        require(n > 0 && t > 0) { "a glTF mesh needs at least one vertex and one triangle, got $n and $t" }
        val total = requireFits(n, t)

        val p = mesh.positions
        val min = FloatArray(3) { Float.POSITIVE_INFINITY }
        val max = FloatArray(3) { Float.NEGATIVE_INFINITY }
        for (i in 0 until n) {
            for (a in 0 until 3) {
                val v = p[3 * i + a]
                require(v.isFinite()) { "vertex $i has a non-finite position" }
                if (v < min[a]) min[a] = v
                if (v > max[a]) max[a] = v
            }
        }
        for (k in mesh.indices.indices) {
            val v = mesh.indices[k]
            require(v in 0 until n) { "index $k is $v, outside 0 until $n" }
        }

        val json = json(n, t, min, max).toByteArray(Charsets.UTF_8)
        val jsonPadded = pad4(json.size)
        val bin = binBytes(n, t)
        check(12L + 8 + jsonPadded + 8 + bin == total) { "byteSize disagrees with the written JSON" }

        val head = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(GLB_MAGIC).putInt(GLB_VERSION).putInt(total.toInt())
            .putInt(jsonPadded).putInt(CHUNK_JSON)
        out.write(head.array())
        out.write(json)
        repeat(jsonPadded - json.size) { out.write(' '.code) }
        val binHead = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(bin.toInt()).putInt(CHUNK_BIN)
        out.write(binHead.array())

        val bb = ByteBuffer.allocate(CHUNK_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        fun room(bytes: Int) {
            if (bb.remaining() < bytes) {
                out.write(bb.array(), 0, bb.position())
                bb.clear()
            }
        }
        for (v in p) { room(4); bb.putFloat(v) }
        for (v in mesh.normals) { room(4); bb.putFloat(v) }
        val c = mesh.colors
        for (i in 0 until n) {
            room(8)
            for (a in 0 until 3) bb.putShort(linear16(c[3 * i + a]).toShort())
            bb.putShort(0xFFFF.toShort())
        }
        for (v in mesh.indices) { room(4); bb.putInt(v) }
        out.write(bb.array(), 0, bb.position())
        // Every view is a multiple of 4 bytes, so the BIN chunk needs no padding.
        out.flush()
    }

    /** Display sRGB 0..1 to linear (IEC 61966-2-1) as round(linear * 65535); out-of-range input is clamped. */
    internal fun linear16(srgb: Float): Int {
        val s = srgb.toDouble().coerceIn(0.0, 1.0)
        val linear = if (s <= 0.04045) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        return Math.round(linear * 65535).toInt().coerceIn(0, 65535)
    }

    private val PLACEHOLDER = FloatArray(3)

    private fun pad4(bytes: Int): Int = (bytes + 3) and 3.inv()

    private fun binBytes(n: Int, t: Int): Long = 12L * n + 12L * n + 8L * n + 12L * t

    private fun StringBuilder.number(v: Float) {
        val s = v.toString()
        check(s.length <= NUMBER_WIDTH) { "number $s is wider than $NUMBER_WIDTH" }
        repeat(NUMBER_WIDTH - s.length) { append(' ') }
        append(s)
    }

    private fun StringBuilder.vec(v: FloatArray) {
        append('[')
        for (a in v.indices) {
            if (a > 0) append(',')
            number(v[a])
        }
        append(']')
    }

    private fun json(n: Int, t: Int, min: FloatArray, max: FloatArray): String = buildString {
        val views = longArrayOf(12L * n, 12L * n, 8L * n, 12L * t)
        val targets = intArrayOf(ARRAY_BUFFER, ARRAY_BUFFER, ARRAY_BUFFER, ELEMENT_ARRAY_BUFFER)
        append("{\"asset\":{\"version\":\"2.0\",\"generator\":\"Splatman\"},")
        append("\"extensionsUsed\":[\"").append(UNLIT).append("\"],")
        append("\"scene\":0,\"scenes\":[{\"nodes\":[0]}],\"nodes\":[{\"mesh\":0}],")
        append("\"meshes\":[{\"primitives\":[{\"mode\":").append(TRIANGLES)
        append(",\"attributes\":{\"POSITION\":0,\"NORMAL\":1,\"COLOR_0\":2},\"indices\":3,\"material\":0}]}],")
        append("\"materials\":[{\"pbrMetallicRoughness\":{\"baseColorFactor\":[1,1,1,1],")
        append("\"metallicFactor\":0,\"roughnessFactor\":1},\"doubleSided\":true,")
        append("\"extensions\":{\"").append(UNLIT).append("\":{}}}],")
        append("\"buffers\":[{\"byteLength\":").append(views.sum()).append("}],")
        append("\"bufferViews\":[")
        var offset = 0L
        for (j in views.indices) {
            if (j > 0) append(',')
            append("{\"buffer\":0,\"byteOffset\":").append(offset).append(",\"byteLength\":").append(views[j])
            append(",\"target\":").append(targets[j]).append('}')
            offset += views[j]
        }
        append("],\"accessors\":[")
        append("{\"bufferView\":0,\"componentType\":").append(FLOAT).append(",\"count\":").append(n)
        append(",\"type\":\"VEC3\",\"min\":")
        vec(min)
        append(",\"max\":")
        vec(max)
        append("},{\"bufferView\":1,\"componentType\":").append(FLOAT).append(",\"count\":").append(n)
        append(",\"type\":\"VEC3\"}")
        append(",{\"bufferView\":2,\"componentType\":").append(UNSIGNED_SHORT).append(",\"normalized\":true")
        append(",\"count\":").append(n).append(",\"type\":\"VEC4\"}")
        append(",{\"bufferView\":3,\"componentType\":").append(UNSIGNED_INT).append(",\"count\":").append(3L * t)
        append(",\"type\":\"SCALAR\"}")
        append("]}")
    }
}
