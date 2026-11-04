package com.huntercoles.splatman.splat.io.gltf

import com.huntercoles.splatman.splat.model.SplatCloud
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Writes a [SplatCloud] (frame W) as a binary glTF (`.glb`) with the ratified `KHR_gaussian_splatting` extension
 * (ADR 0007, docs/research/gltf-gaussian-splatting.md). glTF is frame W, so nothing goes through `worldToFile`:
 * positions and SH are copied as stored, scales become `exp(logScale)`, opacities `sigmoid(logit)`, rotations unit
 * quaternions in glTF order x, y, z, w, and `f_rest` is regrouped into one RGB triple per coefficient
 * (`SH_DEGREE_d_COEF_k`). One POINTS primitive on one node with no transform (identity), every attribute a tightly
 * packed float32 accessor in its own buffer view. No JSON library: the JSON chunk is built by hand.
 *
 * The cloud must hold at least one splat (a glTF accessor count is >= 1), finite positions (POSITION min/max are
 * JSON numbers) and no zero quaternion. The stream is flushed, not closed.
 */
object GltfWriter {

    const val EXTENSION = "KHR_gaussian_splatting"

    private const val GLB_MAGIC = 0x46546C67 // "glTF"
    private const val GLB_VERSION = 2
    private const val CHUNK_JSON = 0x4E4F534A // "JSON"
    private const val CHUNK_BIN = 0x004E4942 // "BIN\0"
    private const val FLOAT = 5126
    private const val CHUNK_BYTES = 1 shl 18
    private const val MAX_GLB_BYTES = 0xFFFF_FFFFL

    private class Attribute(val semantic: String, val type: String, val components: Int, val value: (Int, Int) -> Float)

    /** The primitive's attributes in file order; the SH rest attributes for every degree up to [SplatCloud.shDegree]. */
    private fun attributes(cloud: SplatCloud): List<Attribute> {
        val p = cloud.positions
        val q = cloud.rotations
        val s = cloud.logScales
        val o = cloud.opacityLogits
        val dc = cloud.shDc
        val list = mutableListOf(
            Attribute("POSITION", "VEC3", 3) { i, c -> p[3 * i + c] },
            // SplatCloud is w, x, y, z; glTF is x, y, z, w.
            Attribute("$EXTENSION:ROTATION", "VEC4", 4) { i, c -> q[4 * i + (c + 1) % 4] / quatNorm(q, i) },
            Attribute("$EXTENSION:SCALE", "VEC3", 3) { i, c -> exp(s[3 * i + c]) },
            Attribute("$EXTENSION:OPACITY", "SCALAR", 1) { i, _ -> 1f / (1f + exp(-o[i])) },
            Attribute("$EXTENSION:SH_DEGREE_0_COEF_0", "VEC3", 3) { i, c -> dc[3 * i + c] },
        )
        for (d in 1..cloud.shDegree) {
            for (k in 0 until 2 * d + 1) {
                val coeff = d * d - 1 + k
                list += Attribute("$EXTENSION:SH_DEGREE_${d}_COEF_$k", "VEC3", 3) { i, c ->
                    cloud.shRest[cloud.shRestIndex(i, c, coeff)]
                }
            }
        }
        return list
    }

    private fun quatNorm(q: FloatArray, i: Int): Float {
        val w = q[4 * i]
        val x = q[4 * i + 1]
        val y = q[4 * i + 2]
        val z = q[4 * i + 3]
        return sqrt(w * w + x * x + y * y + z * z)
    }

    fun write(cloud: SplatCloud, out: OutputStream) {
        val n = cloud.count
        require(n > 0) { "a glTF needs at least one splat, got 0" }
        val min = FloatArray(3) { Float.POSITIVE_INFINITY }
        val max = FloatArray(3) { Float.NEGATIVE_INFINITY }
        for (i in 0 until n) {
            for (a in 0 until 3) {
                val v = cloud.positions[3 * i + a]
                require(v.isFinite()) { "splat $i has a non-finite position" }
                if (v < min[a]) min[a] = v
                if (v > max[a]) max[a] = v
            }
            require(quatNorm(cloud.rotations, i) > 0f) { "splat $i has a zero quaternion" }
        }

        val attrs = attributes(cloud)
        val binBytes = attrs.sumOf { 4L * it.components * n }
        val json = json(attrs, n, binBytes, min, max).toByteArray(Charsets.UTF_8)
        val jsonPadded = (json.size + 3) and 3.inv()
        val total = 12L + 8 + jsonPadded + 8 + binBytes
        require(total <= MAX_GLB_BYTES) { "the .glb would be $total bytes, over the 4 GiB GLB limit" }

        val head = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(GLB_MAGIC).putInt(GLB_VERSION).putInt(total.toInt())
            .putInt(jsonPadded).putInt(CHUNK_JSON)
        out.write(head.array())
        out.write(json)
        repeat(jsonPadded - json.size) { out.write(' '.code) }
        val binHead = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(binBytes.toInt()).putInt(CHUNK_BIN)
        out.write(binHead.array())

        val bb = ByteBuffer.allocate(CHUNK_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        for (attr in attrs) {
            for (i in 0 until n) {
                if (bb.remaining() < 4 * attr.components) {
                    out.write(bb.array(), 0, bb.position())
                    bb.clear()
                }
                for (c in 0 until attr.components) bb.putFloat(attr.value(i, c))
            }
        }
        out.write(bb.array(), 0, bb.position())
        // Every view is a multiple of 4 bytes, so the BIN chunk needs no padding.
        out.flush()
    }

    private fun json(attrs: List<Attribute>, n: Int, binBytes: Long, min: FloatArray, max: FloatArray): String =
        buildString {
            append("{\"asset\":{\"version\":\"2.0\",\"generator\":\"Splatman\"},")
            append("\"extensionsUsed\":[\"").append(EXTENSION).append("\"],")
            append("\"scene\":0,\"scenes\":[{\"nodes\":[0]}],\"nodes\":[{\"mesh\":0}],")
            append("\"meshes\":[{\"primitives\":[{\"mode\":0,\"attributes\":{")
            attrs.forEachIndexed { j, a ->
                if (j > 0) append(',')
                append('"').append(a.semantic).append("\":").append(j)
            }
            append("},\"extensions\":{\"").append(EXTENSION)
            append("\":{\"kernel\":\"ellipse\",\"colorSpace\":\"srgb_rec709_display\"}}}]}],")
            append("\"buffers\":[{\"byteLength\":").append(binBytes).append("}],")
            append("\"bufferViews\":[")
            var offset = 0L
            attrs.forEachIndexed { j, a ->
                val length = 4L * a.components * n
                if (j > 0) append(',')
                append("{\"buffer\":0,\"byteOffset\":").append(offset).append(",\"byteLength\":").append(length)
                append('}')
                offset += length
            }
            append("],\"accessors\":[")
            attrs.forEachIndexed { j, a ->
                if (j > 0) append(',')
                append("{\"bufferView\":").append(j).append(",\"componentType\":").append(FLOAT)
                append(",\"count\":").append(n).append(",\"type\":\"").append(a.type).append('"')
                if (j == 0) {
                    append(",\"min\":[").append(min.joinToString(",")).append(']')
                    append(",\"max\":[").append(max.joinToString(",")).append(']')
                }
                append('}')
            }
            append("]}")
        }
}
