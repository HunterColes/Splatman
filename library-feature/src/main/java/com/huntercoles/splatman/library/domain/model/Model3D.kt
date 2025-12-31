package com.huntercoles.splatman.library.domain.model

import java.nio.FloatBuffer

/**
 * Unified 3D model representation for PLY, STL, OBJ files
 *
 * Simpler than SplatScene - represents traditional 3D meshes/point clouds
 * that can be rendered with OpenGL ES 3.0
 */
sealed class Model3D {
    abstract val name: String
    abstract val vertexBuffer: FloatBuffer?
    abstract val normalBuffer: FloatBuffer?
    abstract val colorBuffer: FloatBuffer?
    abstract val indexBuffer: IntArray?
    abstract val vertexCount: Int
    abstract val centerX: Float
    abstract val centerY: Float
    abstract val centerZ: Float
    abstract val minX: Float
    abstract val minY: Float
    abstract val minZ: Float
    abstract val maxX: Float
    abstract val maxY: Float
    abstract val maxZ: Float
    
    /**
     * Get bounding box size for camera positioning
     */
    fun getBoundingBoxSize(): Float {
        val dx = maxX - minX
        val dy = maxY - minY
        val dz = maxZ - minZ
        return kotlin.math.max(dx, kotlin.math.max(dy, dz))
    }
    
    /**
     * Point cloud model (PLY files)
     */
    data class PointCloud(
        override val name: String,
        override val vertexBuffer: FloatBuffer,
        override val normalBuffer: FloatBuffer?,
        override val colorBuffer: FloatBuffer?,
        override val vertexCount: Int,
        override val centerX: Float,
        override val centerY: Float,
        override val centerZ: Float,
        override val minX: Float,
        override val minY: Float,
        override val minZ: Float,
        override val maxX: Float,
        override val maxY: Float,
        override val maxZ: Float
    ) : Model3D() {
        override val indexBuffer: IntArray? = null
    }
    
    /**
     * Triangle mesh model (STL, OBJ files)
     */
    data class TriangleMesh(
        override val name: String,
        override val vertexBuffer: FloatBuffer,
        override val normalBuffer: FloatBuffer,
        override val colorBuffer: FloatBuffer?,
        override val indexBuffer: IntArray?,
        override val vertexCount: Int,
        override val centerX: Float,
        override val centerY: Float,
        override val centerZ: Float,
        override val minX: Float,
        override val minY: Float,
        override val minZ: Float,
        override val maxX: Float,
        override val maxY: Float,
        override val maxZ: Float
    ) : Model3D()
}
