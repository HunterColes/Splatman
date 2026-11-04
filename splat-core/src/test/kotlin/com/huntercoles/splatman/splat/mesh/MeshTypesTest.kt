package com.huntercoles.splatman.splat.mesh

import com.huntercoles.splatman.splat.model.TriangleMesh
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MeshTypesTest {

    @Test
    fun `TriangleMesh counts vertices and triangles`() {
        val m = TriangleMesh(FloatArray(12), FloatArray(12), FloatArray(12), intArrayOf(0, 1, 2, 0, 2, 3))
        assertEquals(4, m.vertexCount)
        assertEquals(2, m.triangleCount)
        assertEquals(0, TriangleMesh(FloatArray(0), FloatArray(0), FloatArray(0), IntArray(0)).triangleCount)
    }

    @Test
    fun `TriangleMesh rejects each array of the wrong length`() {
        assertThrows<IllegalArgumentException> { TriangleMesh(FloatArray(10), FloatArray(10), FloatArray(10), IntArray(3)) }
        assertThrows<IllegalArgumentException> { TriangleMesh(FloatArray(9), FloatArray(6), FloatArray(9), IntArray(3)) }
        assertThrows<IllegalArgumentException> { TriangleMesh(FloatArray(9), FloatArray(9), FloatArray(12), IntArray(3)) }
        assertThrows<IllegalArgumentException> { TriangleMesh(FloatArray(9), FloatArray(9), FloatArray(9), IntArray(4)) }
    }

    @Test
    fun `MeshSettings defaults are the ADR's`() {
        val s = MeshSettings()
        assertEquals(256, s.maxPointsPerAxis)
        assertEquals(128, s.minPointsPerAxis)
        assertEquals(0.05f, s.minOpacity)
        assertEquals(0.5f, s.iso)
        assertEquals(3f, s.sigmas)
        assertEquals(5, s.maxRadiusCells)
        assertEquals(0.005f, s.boundsQuantile)
        assertEquals(2, s.padCells)
        assertEquals(7, s.totalPad)
        assertEquals(2_000_000, s.maxTriangles)
        assertEquals(Runtime.getRuntime().availableProcessors(), s.threads)
    }

    @Test
    fun `MeshSettings rejects nonsense`() {
        assertThrows<IllegalArgumentException> { MeshSettings(minPointsPerAxis = 300) }
        assertThrows<IllegalArgumentException> { MeshSettings(threads = 0) }
        assertThrows<IllegalArgumentException> { MeshSettings(maxRadiusCells = 0) }
        assertThrows<IllegalArgumentException> { MeshSettings(boundsQuantile = 0.5f) }
        assertThrows<IllegalArgumentException> { MeshSettings(sigmas = Float.NaN) }
    }
}
