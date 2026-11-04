package com.huntercoles.splatman.splat.model

/**
 * An indexed triangle mesh (ADR 0018), the output of the mesh export. Struct of arrays like [SplatCloud]; the arrays
 * are not copied and are immutable by contract. The constructor checks every length.
 */
class TriangleMesh(
    /** 3 floats per vertex, x, y, z, world frame W, metres. */
    val positions: FloatArray,
    /** 3 floats per vertex, unit outward normal, frame W. */
    val normals: FloatArray,
    /** 3 floats per vertex, r, g, b, display sRGB 0..1. */
    val colors: FloatArray,
    /** 3 ints per triangle, vertex indices, counter-clockwise seen from outside. */
    val indices: IntArray,
) {
    /** Number of vertices. */
    val vertexCount: Int = positions.size / 3

    /** Number of triangles. */
    val triangleCount: Int = indices.size / 3

    init {
        require(positions.size % 3 == 0) { "positions must hold 3 floats per vertex, got ${positions.size}" }
        require(normals.size == positions.size) { "normals must hold ${positions.size} floats, got ${normals.size}" }
        require(colors.size == positions.size) { "colors must hold ${positions.size} floats, got ${colors.size}" }
        require(indices.size % 3 == 0) { "indices must hold 3 ints per triangle, got ${indices.size}" }
    }
}
