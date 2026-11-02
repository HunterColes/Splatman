package com.huntercoles.splatman.splat.io.ply

import com.huntercoles.splatman.splat.io.ply.PlyFixtures.seededCloud
import com.huntercoles.splatman.splat.io.ply.PlyFixtures.write
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PlyHeaderTest {

    private fun golden(name: String): String =
        requireNotNull(javaClass.getResourceAsStream("/golden/ply/$name")) { "missing golden $name" }
            .use { it.readBytes().toString(Charsets.US_ASCII) }

    @Test
    fun `header matches the golden file byte for byte`() {
        assertEquals(golden("header-sh1-n2.txt"), PlyWriter.header(count = 2, shDegree = 1))
    }

    @Test
    fun `written file starts with the golden header`() {
        val bytes = write(seededCloud(count = 2, shDegree = 1, seed = 4))
        val expected = golden("header-sh1-n2.txt")
        assertEquals(expected, String(bytes, 0, expected.length, Charsets.US_ASCII))
        assertEquals(expected.length + 2 * 26 * 4, bytes.size) // 26 floats a splat at degree 1
    }

    @Test
    fun `f_rest field count follows the degree`() {
        for ((degree, fields) in listOf(0 to 0, 1 to 9, 2 to 24, 3 to 45)) {
            val header = PlyWriter.header(count = 1, shDegree = degree)
            assertEquals(fields, header.lines().count { it.startsWith("property float f_rest_") }, "degree $degree")
            assertEquals(17 + fields, header.lines().count { it.startsWith("property float ") }, "degree $degree")
        }
    }
}
