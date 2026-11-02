package com.huntercoles.splatman.splat.io.ply

import com.huntercoles.splatman.splat.io.ply.PlyFixtures.Prop
import com.huntercoles.splatman.splat.io.ply.PlyFixtures.build
import com.huntercoles.splatman.splat.io.ply.PlyFixtures.layoutNames
import com.huntercoles.splatman.splat.io.ply.PlyFixtures.read
import com.huntercoles.splatman.splat.io.ply.PlyFixtures.seededCloud
import com.huntercoles.splatman.splat.io.ply.PlyFixtures.write
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream

class PlyReaderTest {

    /** Row value for property [name] of vertex [v]: distinct and exact in float. */
    private fun valueOf(name: String, v: Int): Double = when (name) {
        "rot_0" -> 1.0 + v // keep w away from zero
        else -> (name.hashCode() % 1000) / 8.0 + v
    }

    private fun rows(names: List<String>, count: Int) =
        List(count) { v -> DoubleArray(names.size) { valueOf(names[it], v) } }

    private fun checkDegree1(cloudNames: List<String>, cloud: com.huntercoles.splatman.splat.model.SplatCloud) {
        assertEquals(1, cloud.shDegree)
        for (v in 0 until cloud.count) {
            fun f(n: String) = valueOf(n, v).toFloat()
            assertArrayEquals(floatArrayOf(f("x"), -f("y"), -f("z")), cloud.positions.copyOfRange(3 * v, 3 * v + 3))
            assertArrayEquals(
                floatArrayOf(f("rot_0"), f("rot_1"), -f("rot_2"), -f("rot_3")),
                cloud.rotations.copyOfRange(4 * v, 4 * v + 4),
            )
            assertArrayEquals(floatArrayOf(f("scale_0"), f("scale_1"), f("scale_2")),
                cloud.logScales.copyOfRange(3 * v, 3 * v + 3))
            assertEquals(f("opacity"), cloud.opacityLogits[v])
            assertArrayEquals(floatArrayOf(f("f_dc_0"), f("f_dc_1"), f("f_dc_2")),
                cloud.shDc.copyOfRange(3 * v, 3 * v + 3))
            val signs = floatArrayOf(-1f, -1f, 1f)
            assertArrayEquals(
                FloatArray(9) { f("f_rest_$it") * signs[it % 3] },
                cloud.shRest.copyOfRange(9 * v, 9 * v + 9),
            )
        }
        assertTrue(cloudNames.isNotEmpty())
    }

    @Test
    fun `reads shuffled properties and skips extra ones`() {
        val layout = layoutNames(1).reversed()
        val props = listOf(Prop("uchar", "red"), Prop("float", "nx")) +
            layout.map { Prop("float", it) }.subList(0, 10) + Prop("int", "segment") +
            layout.map { Prop("float", it) }.subList(10, layout.size) + Prop("double", "confidence")
        val names = props.map { it.name }
        val result = PlyReader.readWithReport(ByteArrayInputStream(build(props, rows(names, 3))))
        checkDegree1(names, result.cloud)
        assertEquals(listOf("red", "segment", "confidence"), result.report.ignoredProperties)
        assertFalse(result.report.shDropped)
    }

    @Test
    fun `reads double fields`() {
        val names = layoutNames(1)
        val props = names.map { Prop(if (it.startsWith("f_rest") || it == "x") "double" else "float", it) }
        checkDegree1(names, read(build(props, rows(names, 4))))
    }

    @Test
    fun `truncated body names the vertex index`() {
        val bytes = write(seededCloud(count = 5, shDegree = 0, seed = 2))
        val stride = 17 * 4
        val cut = bytes.copyOf(bytes.size - 2 * stride + 5) // vertex 3 is incomplete
        val e = assertThrows<PlyFormatException> { read(cut) }
        assertTrue(e.message!!.contains("vertex 3 of 5"), e.message)
    }

    /** A real one-vertex file whose header claims [claimed] vertices. */
    private fun hostile(shDegree: Int, claimed: Long): ByteArray {
        val text = String(write(seededCloud(count = 1, shDegree = shDegree, seed = 4)), Charsets.ISO_8859_1)
        assertTrue(text.contains("element vertex 1\n"))
        return text.replace("element vertex 1\n", "element vertex $claimed\n").toByteArray(Charsets.ISO_8859_1)
    }

    @Test
    fun `a huge vertex count with a short body is a format error, not an allocation`() {
        for (degree in listOf(0, 3)) {
            val e = assertThrows<PlyFormatException> { read(hostile(degree, 500_000_000)) }
            assertTrue(e.message!!.contains("vertex 1 of 500000000"), e.message)
        }
        val over = assertThrows<PlyFormatException> { read(hostile(0, 5_000_000_000)) }
        assertTrue(over.message!!.contains("too many vertices"), over.message)
    }

    @Test
    fun `wrong f_rest count is an error`() {
        val names = layoutNames(0) + List(4) { "f_rest_$it" }
        val e = assertThrows<PlyFormatException> { read(build(names.map { Prop("float", it) }, rows(names, 1))) }
        assertTrue(e.message!!.contains("4 f_rest"), e.message)
    }

    @Test
    fun `a gap in the f_rest numbering is an error`() {
        val names = layoutNames(0) + List(9) { if (it == 5) "f_rest_9" else "f_rest_$it" }
        val e = assertThrows<PlyFormatException> { read(build(names.map { Prop("float", it) }, rows(names, 1))) }
        assertTrue(e.message!!.contains("f_rest_5"), e.message)
    }

    @Test
    fun `ASCII is rejected with a message`() {
        val names = layoutNames(0)
        val e = assertThrows<PlyFormatException> {
            read(build(names.map { Prop("float", it) }, emptyList(), format = "ascii", count = 1))
        }
        assertTrue(e.message!!.contains("ASCII"), e.message)
    }

    @Test
    fun `big-endian is rejected with a message`() {
        val names = layoutNames(0)
        val e = assertThrows<PlyFormatException> {
            read(build(names.map { Prop("float", it) }, emptyList(), format = "binary_big_endian", count = 1))
        }
        assertTrue(e.message!!.contains("big-endian"), e.message)
    }

    @Test
    fun `not a PLY and a missing property are errors`() {
        assertThrows<PlyFormatException> { read("hello\n".toByteArray()) }
        val names = layoutNames(0) - "opacity"
        val e = assertThrows<PlyFormatException> { read(build(names.map { Prop("float", it) }, rows(names, 1))) }
        assertTrue(e.message!!.contains("'opacity'"), e.message)
    }

    @Test
    fun `an integer typed required property is an error`() {
        val names = layoutNames(0)
        val props = names.map { Prop(if (it == "x") "int" else "float", it) }
        val e = assertThrows<PlyFormatException> { read(build(props, rows(names, 1))) }
        assertTrue(e.message!!.contains("'x'"), e.message)
    }

    @Test
    fun `SH rest over the memory cap is dropped to degree 0 and reported`() {
        val cloud = seededCloud(count = 10, shDegree = 3, seed = 8)
        val bytes = write(cloud)
        val restBytes = 10L * 45 * 4

        val kept = PlyReader.readWithReport(ByteArrayInputStream(bytes), shCapBytes = restBytes + 1)
        assertEquals(3, kept.cloud.shDegree)
        assertFalse(kept.report.shDropped)

        val dropped = PlyReader.readWithReport(ByteArrayInputStream(bytes), shCapBytes = restBytes)
        assertEquals(0, dropped.cloud.shDegree)
        assertEquals(0, dropped.cloud.shRest.size)
        assertEquals(ReadReport(count = 10, fileShDegree = 3, shDegree = 0, ignoredProperties = emptyList()),
            dropped.report)
        assertTrue(dropped.report.shDropped)
        assertTrue(cloud.positions.contentEquals(dropped.cloud.positions))
        assertTrue(cloud.shDc.contentEquals(dropped.cloud.shDc))
        assertTrue(cloud.rotations.contentEquals(dropped.cloud.rotations))
    }

    @Test
    fun `default cap is ADR 0002's 64 MB`() {
        assertEquals(64L * 1024 * 1024, PlyReader.SH_CAP_BYTES)
    }
}
