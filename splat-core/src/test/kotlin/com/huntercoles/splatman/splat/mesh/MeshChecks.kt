package com.huntercoles.splatman.splat.mesh

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import kotlin.math.sqrt

/** Topology checks of an indexed triangle mesh (positions xyz, indices abc), shared by the mesh tests. */
internal object MeshChecks {

    private fun key(a: Int, b: Int): Long = (minOf(a, b).toLong() shl 32) or maxOf(a, b).toLong()

    /**
     * Asserts every undirected edge is in exactly two triangles and every directed edge in one (consistent winding),
     * and that no triangle repeats a vertex.
     */
    fun assertClosedManifold(indices: IntArray) {
        assertTrue(indices.isNotEmpty()) { "no triangles" }
        val undirected = HashMap<Long, Int>()
        val directed = HashSet<Long>()
        for (t in 0 until indices.size / 3) {
            val v = intArrayOf(indices[3 * t], indices[3 * t + 1], indices[3 * t + 2])
            assertTrue(v[0] != v[1] && v[1] != v[2] && v[0] != v[2]) { "triangle $t repeats a vertex" }
            for (e in 0..2) {
                val a = v[e]
                val b = v[(e + 1) % 3]
                undirected.merge(key(a, b), 1, Int::plus)
                assertTrue(directed.add((a.toLong() shl 32) or b.toLong())) { "directed edge $a-$b twice" }
            }
        }
        val bad = undirected.values.count { it != 2 }
        assertEquals(0, bad) { "$bad edges not in exactly two triangles" }
    }

    /** V - E + F over the vertices the triangles use. */
    fun euler(indices: IntArray): Int {
        val used = indices.toSet().size
        val edges = HashSet<Long>()
        for (t in 0 until indices.size / 3) {
            for (e in 0..2) edges.add(key(indices[3 * t + e], indices[3 * t + (e + 1) % 3]))
        }
        return used - edges.size + indices.size / 3
    }

    /** Connected components of the triangles. */
    fun components(indices: IntArray, vertexCount: Int): Int {
        val parent = IntArray(vertexCount) { it }
        fun find(x: Int): Int {
            var r = x
            while (parent[r] != r) r = parent[r]
            var c = x
            while (parent[c] != r) {
                val n = parent[c]
                parent[c] = r
                c = n
            }
            return r
        }
        for (t in 0 until indices.size / 3) {
            val a = find(indices[3 * t])
            parent[find(indices[3 * t + 1])] = a
            parent[find(indices[3 * t + 2])] = find(a)
        }
        return indices.toSet().map { find(it) }.toSet().size
    }

    /** Signed volume: positive when the closed components are wound counter-clockwise from outside. */
    fun signedVolume(positions: FloatArray, indices: IntArray): Double {
        var vol = 0.0
        for (t in 0 until indices.size / 3) {
            val a = 3 * indices[3 * t]
            val b = 3 * indices[3 * t + 1]
            val c = 3 * indices[3 * t + 2]
            val ax = positions[a].toDouble(); val ay = positions[a + 1].toDouble(); val az = positions[a + 2].toDouble()
            val bx = positions[b].toDouble(); val by = positions[b + 1].toDouble(); val bz = positions[b + 2].toDouble()
            val cx = positions[c].toDouble(); val cy = positions[c + 1].toDouble(); val cz = positions[c + 2].toDouble()
            vol += ax * (by * cz - bz * cy) - ay * (bx * cz - bz * cx) + az * (bx * cy - by * cx)
        }
        return vol / 6
    }

    /** Asserts each normal is finite and of unit length. */
    fun assertUnitNormals(normals: FloatArray) {
        for (v in 0 until normals.size / 3) {
            val x = normals[3 * v]
            val y = normals[3 * v + 1]
            val z = normals[3 * v + 2]
            assertTrue(x.isFinite() && y.isFinite() && z.isFinite()) { "normal $v not finite" }
            assertEquals(1.0, sqrt((x * x + y * y + z * z).toDouble()), 1e-4) { "normal $v not unit" }
        }
    }

    /** Asserts every position is finite. */
    fun assertFinite(positions: FloatArray) {
        assertTrue(positions.all { it.isFinite() }) { "a position is not finite" }
    }
}
