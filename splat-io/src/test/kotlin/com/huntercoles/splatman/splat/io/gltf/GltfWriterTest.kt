package com.huntercoles.splatman.splat.io.gltf

import com.huntercoles.splatman.splat.io.ply.PlyFixtures.seededCloud
import com.huntercoles.splatman.splat.model.SplatCloud
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

class GltfWriterTest {

    /** Test-only GLB reader: splits the chunks, parses the JSON and reads float32 accessors by semantic. */
    private class Glb(bytes: ByteArray) {
        val json: JsonObject
        val bin: ByteBuffer
        val jsonChunk: ByteArray

        init {
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(0x46546C67, bb.int, "magic")
            assertEquals(2, bb.int, "version")
            assertEquals(bytes.size, bb.int, "total length")
            val jsonLength = bb.int
            assertEquals(0x4E4F534A, bb.int, "JSON chunk type")
            assertEquals(0, jsonLength % 4, "JSON chunk padded to 4")
            jsonChunk = ByteArray(jsonLength).also { bb.get(it) }
            json = Json.parseToJsonElement(String(jsonChunk, Charsets.UTF_8)).jsonObject
            val binLength = bb.int
            assertEquals(0x004E4942, bb.int, "BIN chunk type")
            assertEquals(bb.remaining(), binLength, "BIN chunk fills the rest")
            bin = bb.slice().order(ByteOrder.LITTLE_ENDIAN)
        }

        val primitive: JsonObject
            get() = json["meshes"]!!.jsonArray[0].jsonObject["primitives"]!!.jsonArray[0].jsonObject

        val attributes: JsonObject get() = primitive["attributes"]!!.jsonObject

        fun accessor(semantic: String): JsonObject =
            json["accessors"]!!.jsonArray[attributes[semantic]!!.jsonPrimitive.int].jsonObject

        fun floats(semantic: String): FloatArray {
            val acc = accessor(semantic)
            assertEquals(5126, acc["componentType"]!!.jsonPrimitive.int, "$semantic float32")
            val comps = when (acc["type"]!!.jsonPrimitive.content) {
                "SCALAR" -> 1
                "VEC3" -> 3
                "VEC4" -> 4
                else -> error("type")
            }
            val count = acc["count"]!!.jsonPrimitive.int
            val view = json["bufferViews"]!!.jsonArray[acc["bufferView"]!!.jsonPrimitive.int].jsonObject
            val offset = view["byteOffset"]!!.jsonPrimitive.int
            assertEquals(4 * comps * count, view["byteLength"]!!.jsonPrimitive.int, "$semantic view length")
            assertNull(view["byteStride"], "tightly packed")
            return FloatArray(comps * count) { bin.getFloat(offset + 4 * it) }
        }
    }

    private fun write(cloud: SplatCloud): ByteArray =
        ByteArrayOutputStream().also { GltfWriter.write(cloud, it) }.toByteArray()

    private val ext = GltfWriter.EXTENSION

    private fun assertClose(expected: Double, actual: Float, what: String) {
        assertTrue(abs(expected - actual) <= 1e-6 * maxOf(1.0, abs(expected)), "$what: $expected vs $actual")
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 3])
    fun `structure, counts and extension object`(degree: Int) {
        val cloud = seededCloud(count = 37, shDegree = degree, seed = 5 + degree)
        val glb = Glb(write(cloud))
        assertEquals("2.0", glb.json["asset"]!!.jsonObject["version"]!!.jsonPrimitive.content)
        assertEquals(listOf(ext), glb.json["extensionsUsed"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertNull(glb.json["extensionsRequired"])
        assertEquals(0, glb.primitive["mode"]!!.jsonPrimitive.int, "POINTS")
        val e = glb.primitive["extensions"]!!.jsonObject[ext]!!.jsonObject
        assertEquals("ellipse", e["kernel"]!!.jsonPrimitive.content)
        assertEquals("srgb_rec709_display", e["colorSpace"]!!.jsonPrimitive.content)
        // One node, the mesh only: no matrix and no TRS, so the identity transform.
        val nodes = glb.json["nodes"]!!.jsonArray
        assertEquals(1, nodes.size)
        assertEquals(setOf("mesh"), nodes[0].jsonObject.keys)

        val shRestAttributes = SplatCloud.restCoefficients(degree)
        val expected = mutableListOf("POSITION", "$ext:ROTATION", "$ext:SCALE", "$ext:OPACITY", "$ext:SH_DEGREE_0_COEF_0")
        for (d in 1..degree) for (k in 0 until 2 * d + 1) expected += "$ext:SH_DEGREE_${d}_COEF_$k"
        assertEquals(5 + shRestAttributes, expected.size)
        assertEquals(expected.toSet(), glb.attributes.keys)
        for (s in expected) assertEquals(37, glb.accessor(s)["count"]!!.jsonPrimitive.int, s)

        val views = glb.json["bufferViews"]!!.jsonArray
        var offset = 0L
        for (v in views) {
            assertEquals(offset, v.jsonObject["byteOffset"]!!.jsonPrimitive.long, "views contiguous")
            offset += v.jsonObject["byteLength"]!!.jsonPrimitive.long
        }
        assertEquals(offset, glb.json["buffers"]!!.jsonArray[0].jsonObject["byteLength"]!!.jsonPrimitive.long)
        assertEquals(offset.toInt(), glb.bin.remaining())
        val text = String(glb.jsonChunk, Charsets.UTF_8)
        val pad = text.substring(text.lastIndexOf('}') + 1)
        assertTrue(pad.length < 4 && pad.all { it == ' ' }, "JSON chunk padded with spaces")
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 3])
    fun `positions as stored with min and max, scale exp, opacity sigmoid, dc copied`(degree: Int) {
        val cloud = seededCloud(count = 300, shDegree = degree, seed = 11 + degree)
        val glb = Glb(write(cloud))
        assertArrayEquals(cloud.positions, glb.floats("POSITION"), "no worldToFile")
        assertArrayEquals(cloud.shDc, glb.floats("$ext:SH_DEGREE_0_COEF_0"))
        val acc = glb.accessor("POSITION")
        for (a in 0 until 3) {
            val axis = (0 until cloud.count).map { cloud.positions[3 * it + a] }
            assertEquals(axis.min(), acc["min"]!!.jsonArray[a].jsonPrimitive.content.toFloat(), "min $a")
            assertEquals(axis.max(), acc["max"]!!.jsonArray[a].jsonPrimitive.content.toFloat(), "max $a")
        }
        val scale = glb.floats("$ext:SCALE")
        for (j in scale.indices) assertClose(exp(cloud.logScales[j].toDouble()), scale[j], "scale $j")
        val opacity = glb.floats("$ext:OPACITY")
        for (i in opacity.indices) {
            assertClose(1.0 / (1.0 + exp(-cloud.opacityLogits[i].toDouble())), opacity[i], "opacity $i")
            assertTrue(opacity[i] in 0f..1f)
        }
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 3])
    fun `rotations are unit quaternions in x, y, z, w order`(degree: Int) {
        val cloud = seededCloud(count = 64, shDegree = degree, seed = 2)
        val rot = glb(cloud).floats("$ext:ROTATION")
        for (i in 0 until cloud.count) {
            val w = cloud.rotations[4 * i].toDouble()
            val x = cloud.rotations[4 * i + 1].toDouble()
            val y = cloud.rotations[4 * i + 2].toDouble()
            val z = cloud.rotations[4 * i + 3].toDouble()
            val norm = sqrt(w * w + x * x + y * y + z * z)
            assertClose(x / norm, rot[4 * i], "x $i")
            assertClose(y / norm, rot[4 * i + 1], "y $i")
            assertClose(z / norm, rot[4 * i + 2], "z $i")
            assertClose(w / norm, rot[4 * i + 3], "w $i")
        }
    }

    @Test
    fun `a known quaternion lands as x, y, z, w`() {
        val cloud = SplatCloud(
            count = 2, shDegree = 0,
            positions = FloatArray(6), logScales = FloatArray(6),
            rotations = floatArrayOf(2f, 0f, 0f, 0f, 1f, 2f, 3f, 4f),
            opacityLogits = FloatArray(2), shDc = FloatArray(6), shRest = FloatArray(0),
        )
        val rot = glb(cloud).floats("$ext:ROTATION")
        assertArrayEquals(floatArrayOf(0f, 0f, 0f, 1f), rot.copyOfRange(0, 4))
        val s = sqrt(30.0)
        listOf(2 / s, 3 / s, 4 / s, 1 / s).forEachIndexed { c, v -> assertClose(v, rot[4 + c], "q1 $c") }
        assertArrayEquals(floatArrayOf(0.5f, 0.5f), glb(cloud).floats("$ext:OPACITY"), "sigmoid(0)")
        assertArrayEquals(FloatArray(6) { 1f }, glb(cloud).floats("$ext:SCALE"), "exp(0)")
    }

    @ParameterizedTest
    @ValueSource(ints = [1, 2, 3])
    fun `f_rest is regrouped into one RGB triple per coefficient`(degree: Int) {
        val n = 9
        val k = SplatCloud.restCoefficients(degree)
        // A value that names its own splat, channel and coefficient: i * 1000 + c * 100 + coeff.
        val rest = FloatArray(n * 3 * k)
        for (i in 0 until n) for (c in 0 until 3) for (q in 0 until k) rest[i * 3 * k + c * k + q] = i * 1000f + c * 100f + q
        val cloud = SplatCloud(
            count = n, shDegree = degree,
            positions = FloatArray(3 * n), logScales = FloatArray(3 * n),
            rotations = FloatArray(4 * n) { if (it % 4 == 0) 1f else 0f },
            opacityLogits = FloatArray(n), shDc = FloatArray(3 * n), shRest = rest,
        )
        val glb = glb(cloud)
        var coeff = 0
        for (d in 1..degree) {
            for (m in 0 until 2 * d + 1) {
                val v = glb.floats("$ext:SH_DEGREE_${d}_COEF_$m")
                for (i in 0 until n) for (c in 0 until 3) {
                    assertEquals(i * 1000f + c * 100f + coeff, v[3 * i + c], "d=$d m=$m splat $i channel $c")
                }
                coeff++
            }
        }
        assertEquals(k, coeff)
        assertFalse(glb.attributes.containsKey("$ext:SH_DEGREE_${degree + 1}_COEF_0"))
    }

    @Test
    fun `an empty cloud or a zero quaternion is refused`() {
        val empty = SplatCloud(0, 0, FloatArray(0), FloatArray(0), FloatArray(0), FloatArray(0), FloatArray(0), FloatArray(0))
        assertThrows<IllegalArgumentException> { write(empty) }
        val zero = SplatCloud(1, 0, FloatArray(3), FloatArray(3), FloatArray(4), FloatArray(1), FloatArray(3), FloatArray(0))
        assertThrows<IllegalArgumentException> { write(zero) }
        val nan = SplatCloud(
            1, 0, floatArrayOf(Float.NaN, 0f, 0f), FloatArray(3), floatArrayOf(1f, 0f, 0f, 0f),
            FloatArray(1), FloatArray(3), FloatArray(0),
        )
        assertThrows<IllegalArgumentException> { write(nan) }
    }

    @Test
    fun `a large cloud streams across buffer chunks`() {
        val cloud = seededCloud(count = 20_000, shDegree = 3, seed = 9)
        val glb = glb(cloud)
        assertArrayEquals(cloud.positions, glb.floats("POSITION"))
        assertEquals(20_000 * (3 + 4 + 3 + 1 + 3 + 45) * 4, glb.bin.remaining())
        val last = glb.floats("$ext:SH_DEGREE_3_COEF_6")
        assertEquals(cloud.shRest[cloud.shRestIndex(19_999, 2, 14)], last[3 * 19_999 + 2])
    }

    private fun glb(cloud: SplatCloud) = Glb(write(cloud))
}
