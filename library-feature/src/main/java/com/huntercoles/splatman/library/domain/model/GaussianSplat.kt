package com.huntercoles.splatman.library.domain.model

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Core data structure for a 3D Gaussian primitive.
 * 
 * Based on the original 3D Gaussian Splatting paper (SIGGRAPH 2023):
 * Each Gaussian is represented by:
 * - Position (3D center point)
 * - Covariance (3x3 matrix → compressed as scale + rotation quaternion)
 * - Color (spherical harmonics coefficients or RGB)
 * - Opacity (alpha value)
 * 
 * Mobile optimization: We limit to degree-0 spherical harmonics (RGB only)
 * for memory efficiency and real-time rendering on ARM GPUs.
 */
data class GaussianSplat(
    /** 3D position in world space (x, y, z) */
    val position: FloatArray,
    
    /** 
     * Scale factors for 3D Gaussian (sx, sy, sz)
     * Combined with rotation to form covariance matrix
     */
    val scale: FloatArray,
    
    /** 
     * Rotation as quaternion (qx, qy, qz, qw)
     * Normalized quaternion representing 3D orientation
     */
    val rotation: FloatArray,
    
    /** 
     * Spherical harmonics coefficients for view-dependent color
     * For mobile: degree-0 only (3 floats: R, G, B)
     * For desktop: degree-3 (48 floats for full lighting)
     */
    val shCoefficients: FloatArray,
    
    /** 
     * Opacity value [0.0, 1.0]
     * Passed through sigmoid activation during rendering
     */
    val opacity: Float
) {
    init {
        require(position.size == 3) { "Position must have 3 components (x, y, z)" }
        require(scale.size == 3) { "Scale must have 3 components (sx, sy, sz)" }
        require(rotation.size == 4) { "Rotation quaternion must have 4 components" }
        require(shCoefficients.size == 3 || shCoefficients.size == 48) { 
            "SH coefficients must be degree-0 (3) or degree-3 (48)" 
        }
        require(opacity in 0f..1f) { "Opacity must be in range [0, 1]" }
    }
    
    /**
     * Size in bytes for efficient binary serialization
     * Position (12) + Scale (12) + Rotation (16) + SH (12 or 192) + Opacity (4)
     */
    val sizeInBytes: Int
        get() = 12 + 12 + 16 + (shCoefficients.size * 4) + 4
    
    /**
     * Serialize to binary format for GPU upload or file export
     */
    fun toByteArray(): ByteArray {
        val buffer = ByteBuffer.allocate(sizeInBytes).order(ByteOrder.LITTLE_ENDIAN)
        
        // Position
        position.forEach { buffer.putFloat(it) }
        
        // Scale
        scale.forEach { buffer.putFloat(it) }
        
        // Rotation
        rotation.forEach { buffer.putFloat(it) }
        
        // SH coefficients
        shCoefficients.forEach { buffer.putFloat(it) }
        
        // Opacity
        buffer.putFloat(opacity)
        
        return buffer.array()
    }
    
    companion object {
        /**
         * Deserialize from binary format
         */
        fun fromByteArray(bytes: ByteArray, shDegree: Int = 0): GaussianSplat {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val shSize = if (shDegree == 0) 3 else 48
            
            val position = FloatArray(3) { buffer.float }
            val scale = FloatArray(3) { buffer.float }
            val rotation = FloatArray(4) { buffer.float }
            val shCoefficients = FloatArray(shSize) { buffer.float }
            val opacity = buffer.float
            
            return GaussianSplat(position, scale, rotation, shCoefficients, opacity)
        }
        
        /**
         * Create a simple RGB Gaussian (degree-0 SH)
         */
        fun createRGB(
            x: Float, y: Float, z: Float,
            sx: Float, sy: Float, sz: Float,
            qx: Float, qy: Float, qz: Float, qw: Float,
            r: Float, g: Float, b: Float,
            opacity: Float
        ) = GaussianSplat(
            position = floatArrayOf(x, y, z),
            scale = floatArrayOf(sx, sy, sz),
            rotation = floatArrayOf(qx, qy, qz, qw),
            shCoefficients = floatArrayOf(r, g, b),
            opacity = opacity
        )
    }
    
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GaussianSplat) return false
        
        return position.contentEquals(other.position) &&
               scale.contentEquals(other.scale) &&
               rotation.contentEquals(other.rotation) &&
               shCoefficients.contentEquals(other.shCoefficients) &&
               opacity == other.opacity
    }
    
    override fun hashCode(): Int {
        var result = position.contentHashCode()
        result = 31 * result + scale.contentHashCode()
        result = 31 * result + rotation.contentHashCode()
        result = 31 * result + shCoefficients.contentHashCode()
        result = 31 * result + opacity.hashCode()
        return result
    }
}
