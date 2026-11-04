package com.huntercoles.splatman.splat.io.gltf

import com.huntercoles.splatman.splat.model.TriangleMesh
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow
import kotlin.random.Random

class GlbMeshWriterTest {

    /** Test-only GLB reader: splits the chunks, parses the JSON, reads accessors by index. */
    private class Glb(bytes: ByteArray) {
        val json: JsonObject
        val bin: ByteBuffer
        val jsonChunk: ByteArray
        val binChunkOffset: Int

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
            binChunkOffset = bb.position()
            assertEquals(0, binChunkOffset % 4, "BIN chunk aligned to 4")
            val binLength = bb.int
            assertEquals(0x004E4942, bb.int, "BIN chunk type")
            assertEquals(0, binLength % 4, "BIN chunk padded to 4")
            assertEquals(bb.remaining(), binLength, "BIN chunk fills the rest")
            bin = bb.slice().order(ByteOrder.LITTLE_ENDIAN)
        }

        val primitive: JsonObject
            get() = json["meshes"]!!.jsonArray[0].jsonObject["primitives"]!!.jsonArray[0].jsonObject

        fun accessor(index: Int): JsonObject = json["accessors"]!!.jsonArray[index].jsonObject

        fun attribute(semantic: String): JsonObject =
            accessor(primitive["attributes"]!!.jsonObject[semantic]!!.jsonPrimitive.int)

        private fun view(acc: JsonObject, elementBytes: Int): Int {
            val view = json["bufferViews"]!!.jsonArray[acc["bufferView"]!!.jsonPrimitive.int].jsonObject
            val offset = view["byteOffset"]!!.jsonPrimitive.int
            assertEquals(elementBytes * acc["count"]!!.jsonPrimitive.int, view["byteLength"]!!.jsonPrimitive.int)
            assertNull(view["byteStride"], "tightly packed")
            return offset
        }

        fun floats(semantic: String): FloatArray {
            val acc = attribute(semantic)
            assertEquals(5126, acc["componentType"]!!.jsonPrimitive.int, "$semantic float32")
            assertEquals("VEC3", acc["type"]!!.jsonPrimitive.content)
            val offset = view(acc, 12)
            return FloatArray(3 * acc["count"]!!.jsonPrimitive.int) { bin.getFloat(offset + 4 * it) }
        }

        fun colors(): IntArray {
            val acc = attribute("COLOR_0")
            assertEquals(5123, acc["componentType"]!!.jsonPrimitive.int, "unsigned short")
            assertEquals(true, acc["normalized"]!!.jsonPrimitive.boolean)
            assertEquals("VEC4", acc["type"]!!.jsonPrimitive.content)
            val offset = view(acc, 8)
            val count = 4 * acc["count"]!!.jsonPrimitive.int
            return IntArray(count) { bin.getShort(offset + 2 * it).toInt() and 0xFFFF }
        }

        fun indices(): IntArray {
            val acc = accessor(primitive["indices"]!!.jsonPrimitive.int)
            assertEquals(5125, acc["componentType"]!!.jsonPrimitive.int, "unsigned int")
            assertEquals("SCALAR", acc["type"]!!.jsonPrimitive.content)
            val offset = view(acc, 4)
            return IntArray(acc["count"]!!.jsonPrimitive.int) { bin.getInt(offset + 4 * it) }
        }
    }

    private fun write(mesh: TriangleMesh): ByteArray =
        ByteArrayOutputStream().also { GlbMeshWriter.write(mesh, it) }.toByteArray()

    private fun twoTriangles(): TriangleMesh = TriangleMesh(
        positions = floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 1f, 1f, 0f, 0f, 1f, -0.5f),
        normals = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f),
        colors = floatArrayOf(0f, 0.04045f, 0.5f, 1f, 1f, 1f, 0.2f, 0.4f, 0.6f, 0.9f, 0.1f, 0f),
        indices = intArrayOf(0, 1, 2, 0, 2, 3),
    )

    private fun randomMesh(vertices: Int, triangles: Int, seed: Int): TriangleMesh {
        val r = Random(seed)
        return TriangleMesh(
            positions = FloatArray(3 * vertices) { r.nextFloat() * 20f - 10f },
            normals = FloatArray(3 * vertices) { r.nextFloat() * 2f - 1f },
            colors = FloatArray(3 * vertices) { r.nextFloat() },
            indices = IntArray(3 * triangles) { r.nextInt(vertices) },
        )
    }

    private fun expected16(display: Double): Int {
        val linear = if (display <= 0.04045) display / 12.92 else ((display + 0.055) / 1.055).pow(2.4)
        return Math.round(linear * 65535).toInt()
    }

    private fun assertRoundTrip(mesh: TriangleMesh) {
        val bytes = write(mesh)
        val glb = Glb(bytes)
        val asset = glb.json["asset"]!!.jsonObject
        assertEquals("2.0", asset["version"]!!.jsonPrimitive.content)
        assertEquals("Splatman", asset["generator"]!!.jsonPrimitive.content)
        assertEquals(listOf(GlbMeshWriter.UNLIT), glb.json["extensionsUsed"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertNull(glb.json["extensionsRequired"])
        assertEquals(1, glb.json["scenes"]!!.jsonArray.size)
        val nodes = glb.json["nodes"]!!.jsonArray
        assertEquals(1, nodes.size)
        assertEquals(setOf("mesh"), nodes[0].jsonObject.keys, "no transform")
        assertEquals(1, glb.json["meshes"]!!.jsonArray.size)
        assertEquals(4, glb.primitive["mode"]!!.jsonPrimitive.int, "TRIANGLES")
        assertEquals(setOf("POSITION", "NORMAL", "COLOR_0"), glb.primitive["attributes"]!!.jsonObject.keys)

        val materials = glb.json["materials"]!!.jsonArray
        assertEquals(1, materials.size)
        val m = materials[0].jsonObject
        assertEquals(0, glb.primitive["material"]!!.jsonPrimitive.int)
        val pbr = m["pbrMetallicRoughness"]!!.jsonObject
        assertEquals(listOf(1.0, 1.0, 1.0, 1.0), pbr["baseColorFactor"]!!.jsonArray.map { it.jsonPrimitive.double })
        assertEquals(0.0, pbr["metallicFactor"]!!.jsonPrimitive.double)
        assertEquals(1.0, pbr["roughnessFactor"]!!.jsonPrimitive.double)
        assertEquals(true, m["doubleSided"]!!.jsonPrimitive.boolean)
        assertEquals(JsonObject(emptyMap()), m["extensions"]!!.jsonObject[GlbMeshWriter.UNLIT])

        for (s in listOf("POSITION", "NORMAL", "COLOR_0")) {
            assertEquals(mesh.vertexCount, glb.attribute(s)["count"]!!.jsonPrimitive.int, s)
        }
        assertArrayEquals(mesh.positions, glb.floats("POSITION"), "frame W: copied as stored")
        assertArrayEquals(mesh.normals, glb.floats("NORMAL"))
        assertArrayEquals(mesh.indices, glb.indices())
        val pos = glb.attribute("POSITION")
        for (a in 0 until 3) {
            val axis = (0 until mesh.vertexCount).map { mesh.positions[3 * it + a] }
            assertEquals(axis.min(), pos["min"]!!.jsonArray[a].jsonPrimitive.content.toFloat(), "min $a")
            assertEquals(axis.max(), pos["max"]!!.jsonArray[a].jsonPrimitive.content.toFloat(), "max $a")
        }
        val colors = glb.colors()
        for (i in 0 until mesh.vertexCount) {
            for (a in 0 until 3) {
                assertEquals(expected16(mesh.colors[3 * i + a].toDouble()), colors[4 * i + a], "colour $i.$a")
            }
            assertEquals(65535, colors[4 * i + 3], "alpha $i")
        }

        // Views: one per accessor, each with its target, 4-byte aligned, contiguous, filling the buffer.
        val views = glb.json["bufferViews"]!!.jsonArray
        assertEquals(4, views.size)
        assertEquals(listOf(34962, 34962, 34962, 34963), views.map { it.jsonObject["target"]!!.jsonPrimitive.int })
        var offset = 0L
        for (v in views) {
            val o = v.jsonObject["byteOffset"]!!.jsonPrimitive.long
            assertEquals(offset, o, "views contiguous")
            assertEquals(0L, (glb.binChunkOffset + 8 + o) % 4, "view aligned in file")
            offset += v.jsonObject["byteLength"]!!.jsonPrimitive.long
        }
        assertEquals(offset, glb.json["buffers"]!!.jsonArray[0].jsonObject["byteLength"]!!.jsonPrimitive.long)
        assertEquals(offset.toInt(), glb.bin.remaining())

        val text = String(glb.jsonChunk, Charsets.UTF_8)
        val pad = text.substring(text.lastIndexOf('}') + 1)
        assertTrue(pad.length < 4 && pad.all { it == ' ' }, "JSON chunk padded with spaces")
        assertEquals(bytes.size.toLong(), GlbMeshWriter.byteSize(mesh.vertexCount, mesh.triangleCount), "byteSize")
    }

    @Test
    fun `two-triangle mesh round trips`() = assertRoundTrip(twoTriangles())

    @Test
    fun `1000-vertex mesh round trips`() = assertRoundTrip(randomMesh(1000, 1900, seed = 3))

    @Test
    fun `65536+ vertices keep 32-bit indices`() {
        val mesh = randomMesh(70_000, 500, seed = 9)
        assertTrue(mesh.indices.any { it > 65535 })
        assertRoundTrip(mesh)
    }

    @Test
    fun `COLOR_0 follows the IEC 61966-2-1 transfer`() {
        assertEquals(0, GlbMeshWriter.linear16(0f))
        assertEquals(Math.round(0.04045f.toDouble() / 12.92 * 65535).toInt(), GlbMeshWriter.linear16(0.04045f))
        assertEquals(Math.round(((0.5 + 0.055) / 1.055).pow(2.4) * 65535).toInt(), GlbMeshWriter.linear16(0.5f))
        assertEquals(65535, GlbMeshWriter.linear16(1f))
        assertEquals(14027, GlbMeshWriter.linear16(0.5f), "0.5 display is about 0.214 linear")
        val colors = Glb(write(twoTriangles())).colors()
        assertEquals(listOf(0, 205, 14027, 65535), listOf(colors[0], colors[1], colors[2], colors[4]))
    }

    @Test
    fun `byteSize depends on the counts only, whatever the positions`() {
        val a = randomMesh(50, 40, seed = 1)
        val wide = a.positions.copyOf().also { it[0] = -1.17549435E-38f; it[1] = 1.2345679E-30f; it[2] = -0.0012345678f }
        val b = TriangleMesh(wide, a.normals, a.colors, a.indices)
        assertEquals(write(a).size, write(b).size)
        assertEquals(GlbMeshWriter.byteSize(50, 40), write(b).size.toLong())
        Glb(write(b))
    }

    @Test
    fun `two writes give equal bytes`() {
        val mesh = randomMesh(1000, 1900, seed = 4)
        assertArrayEquals(write(mesh), write(mesh))
    }

    @Test
    fun `rejects an empty mesh`() {
        val empty = TriangleMesh(FloatArray(0), FloatArray(0), FloatArray(0), IntArray(0))
        assertThrows<IllegalArgumentException> { write(empty) }
        val noTriangles = TriangleMesh(FloatArray(3), FloatArray(3), FloatArray(3), IntArray(0))
        assertThrows<IllegalArgumentException> { write(noTriangles) }
        assertThrows<IllegalArgumentException> { GlbMeshWriter.byteSize(0, 1) }
    }

    @Test
    fun `rejects a GLB over 4 GiB`() {
        assertTrue(GlbMeshWriter.byteSize(100_000_000, 200_000_000) > 0xFFFF_FFFFL)
        val e = assertThrows<IllegalArgumentException> { GlbMeshWriter.requireFits(100_000_000, 200_000_000) }
        assertTrue(e.message!!.contains("4 GiB"), e.message)
        GlbMeshWriter.requireFits(1000, 1900)
    }

    @Test
    fun `rejects NaN in a position and an index out of range`() {
        val m = twoTriangles()
        val nan = m.positions.copyOf().also { it[4] = Float.NaN }
        val e = assertThrows<IllegalArgumentException> { write(TriangleMesh(nan, m.normals, m.colors, m.indices)) }
        assertTrue(e.message!!.contains("non-finite"), e.message)
        assertThrows<IllegalArgumentException> {
            write(TriangleMesh(m.positions, m.normals, m.colors, intArrayOf(0, 1, 4, 0, 2, 3)))
        }
    }
}
