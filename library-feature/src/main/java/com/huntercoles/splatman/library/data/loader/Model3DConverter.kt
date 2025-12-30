package com.huntercoles.splatman.library.data.loader

import com.huntercoles.splatman.library.domain.model.BoundingBox
import com.huntercoles.splatman.library.domain.model.GaussianSplat
import com.huntercoles.splatman.library.domain.model.Model3D
import com.huntercoles.splatman.library.domain.model.SplatScene
import timber.log.Timber
import java.util.Date
import java.util.UUID

/**
 * Converts Model3D (PLY/STL/OBJ) to SplatScene for rendering
 * 
 * This allows traditional 3D models to be displayed using the Gaussian renderer
 * by treating each vertex as a small Gaussian splat
 */
object Model3DConverter {
    
    /**
     * Convert Model3D point cloud to SplatScene
     * Each vertex becomes a small Gaussian splat with its color
     */
    fun toSplatScene(model: Model3D): SplatScene {
        return when (model) {
            is Model3D.PointCloud -> convertPointCloud(model)
            is Model3D.TriangleMesh -> convertTriangleMesh(model)
        }
    }
    
    private fun convertPointCloud(model: Model3D.PointCloud): SplatScene {
        val gaussians = mutableListOf<GaussianSplat>()
        
        // CRITICAL: Limit vertex count to prevent OOM crashes
        // Mobile devices can't handle millions of splats
        val maxVertices = 200_000
        val actualVertexCount = minOf(model.vertexCount, maxVertices)
        
        if (model.vertexCount > maxVertices) {
            Timber.w("Model has ${model.vertexCount} vertices - downsampling to $maxVertices for performance")
        }
        
        val vertexBuffer = model.vertexBuffer
        val colorBuffer = model.colorBuffer
        
        vertexBuffer.position(0)
        colorBuffer?.position(0)
        
        // Calculate appropriate scale based on bounding box
        val boxSize = model.getBoundingBoxSize()
        val scale = boxSize / 200f // Adjust scale based on model size
        
        // Sample vertices evenly if downsampling
        val stride = if (model.vertexCount > maxVertices) {
            model.vertexCount.toFloat() / maxVertices
        } else {
            1f
        }
        
        var vertexIndex = 0f
        while (vertexIndex < actualVertexCount && vertexBuffer.hasRemaining()) {
            val targetIndex = (vertexIndex * stride).toInt()
            
            // Skip to target vertex
            vertexBuffer.position(targetIndex * 3)
            colorBuffer?.position(targetIndex * 4)
            
            if (!vertexBuffer.hasRemaining()) break
            
            val x = vertexBuffer.get()
            val y = vertexBuffer.get()
            val z = vertexBuffer.get()
            
            val r: Float
            val g: Float
            val b: Float
            val a: Float
            
            if (colorBuffer != null && colorBuffer.hasRemaining()) {
                r = colorBuffer.get()
                g = colorBuffer.get()
                b = colorBuffer.get()
                a = colorBuffer.get()
            } else {
                // Default white color if no colors
                r = 0.8f
                g = 0.8f
                b = 0.8f
                a = 1.0f
            }
            
            gaussians.add(
                GaussianSplat(
                    position = floatArrayOf(x, y, z),
                    scale = floatArrayOf(scale, scale, scale),
                    rotation = floatArrayOf(1f, 0f, 0f, 0f), // Identity quaternion
                    shCoefficients = floatArrayOf(r, g, b),
                    opacity = a
                )
            )
            
            vertexIndex++
        }
        
        Timber.d("Converted ${gaussians.size} vertices to Gaussian splats (original: ${model.vertexCount})")
        
        return SplatScene(
            id = UUID.randomUUID().toString(),
            name = model.name,
            createdAt = Date(),
            modifiedAt = Date(),
            gaussians = gaussians,
            cameraIntrinsics = null,
            boundingBox = BoundingBox(
                min = floatArrayOf(model.minX, model.minY, model.minZ),
                max = floatArrayOf(model.maxX, model.maxY, model.maxZ)
            ),
            shDegree = 0
        )
    }
    
    private fun convertTriangleMesh(model: Model3D.TriangleMesh): SplatScene {
        // For now, treat triangle mesh vertices the same as point cloud
        // TODO: Could sample triangle surfaces for better quality
        val gaussians = mutableListOf<GaussianSplat>()
        
        val vertexBuffer = model.vertexBuffer
        val colorBuffer = model.colorBuffer
        
        vertexBuffer.position(0)
        colorBuffer?.position(0)
        
        val boxSize = model.getBoundingBoxSize()
        val scale = boxSize / 200f
        
        for (i in 0 until model.vertexCount) {
            val x = vertexBuffer.get()
            val y = vertexBuffer.get()
            val z = vertexBuffer.get()
            
            val r: Float
            val g: Float
            val b: Float
            val a: Float
            
            if (colorBuffer != null) {
                r = colorBuffer.get()
                g = colorBuffer.get()
                b = colorBuffer.get()
                a = colorBuffer.get()
            } else {
                r = 0.8f
                g = 0.8f
                b = 0.8f
                a = 1.0f
            }
            
            gaussians.add(
                GaussianSplat(
                    position = floatArrayOf(x, y, z),
                    scale = floatArrayOf(scale, scale, scale),
                    rotation = floatArrayOf(1f, 0f, 0f, 0f),
                    shCoefficients = floatArrayOf(r, g, b),
                    opacity = a
                )
            )
        }
        
        return SplatScene(
            id = UUID.randomUUID().toString(),
            name = model.name,
            createdAt = Date(),
            modifiedAt = Date(),
            gaussians = gaussians,
            cameraIntrinsics = null,
            boundingBox = BoundingBox(
                min = floatArrayOf(model.minX, model.minY, model.minZ),
                max = floatArrayOf(model.maxX, model.maxY, model.maxZ)
            ),
            shDegree = 0
        )
    }
}
