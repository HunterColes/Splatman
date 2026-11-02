package com.huntercoles.splatman.splat.gpu.raster

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The backward kernels must get past a GLSL ES 3.10 compiler, which the gate has no driver for. This catches the
 * failure QA hit on the AVD (`packed`, a layout-qualifier word the compiler tokenizes, used as an SSBO member name):
 * no identifier outside a `layout(...)` may be a reserved word of the GLSL ES 3.10 spec (section 3.8) or a
 * block-layout qualifier name.
 */
class BackwardTileShadersTest {
    private val reserved = setOf(
        // GLSL ES 3.10 section 3.8, reserved for future use.
        "attribute", "varying", "resource", "noperspective", "patch", "sample", "subroutine", "common",
        "partition", "active", "asm", "class", "union", "enum", "typedef", "template", "this", "goto", "inline",
        "noinline", "volatile", "public", "static", "extern", "external", "interface", "long", "short", "double",
        "half", "fixed", "unsigned", "superp", "input", "output", "hvec2", "hvec3", "hvec4", "dvec2", "dvec3",
        "dvec4", "fvec2", "fvec3", "fvec4", "sampler3DRect", "filter", "sizeof", "cast", "namespace", "using",
        // Block-layout qualifier names that drivers lex as keywords.
        "packed", "std140", "std430", "row_major", "column_major",
    )

    private val shaders = mapOf(
        "PACK" to BackwardTileShaders.PACK,
        "REPLAY" to BackwardTileShaders.REPLAY,
        "GATHER" to BackwardTileShaders.GATHER,
    )

    private fun reservedIdentifiers(source: String): Set<String> {
        val body = source.replace(Regex("""layout\s*\([^)]*\)"""), " ")
        return Regex("""[A-Za-z_][A-Za-z0-9_]*""").findAll(body).map { it.value }.filter { it in reserved }.toSet()
    }

    @Test
    fun `no backward kernel uses a reserved GLSL ES word as an identifier`() {
        shaders.forEach { (name, src) -> assertEquals(emptySet<String>(), reservedIdentifiers(src), name) }
    }

    @Test
    fun `the checker flags a reserved word used as a buffer member`() {
        val bad = "layout(std430, binding = 4) writeonly buffer Packed { float packed[]; };"
        assertEquals(setOf("packed"), reservedIdentifiers(bad))
    }

    @Test
    fun `every backward kernel targets GLSL ES 3_10`() {
        shaders.forEach { (name, src) -> assertTrue(src.startsWith("#version 310 es\n"), name) }
    }
}
