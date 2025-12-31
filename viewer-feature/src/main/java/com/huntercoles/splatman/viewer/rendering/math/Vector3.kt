package com.huntercoles.splatman.viewer.rendering.math

import kotlin.math.sqrt

/**
 * 3D Vector utility class for camera transformations and Gaussian splat math
 * 
 * Immutable value class for performance and thread safety.
 * Used for positions, directions, and translations in 3D space.
 * 
 * @param x X component
 * @param y Y component
 * @param z Z component
 */
data class Vector3(
    val x: Float,
    val y: Float,
    val z: Float
) {
    // Operator overloading for intuitive math
    operator fun plus(other: Vector3) = Vector3(x + other.x, y + other.y, z + other.z)
    operator fun minus(other: Vector3) = Vector3(x - other.x, y - other.y, z - other.z)
    operator fun times(scalar: Float) = Vector3(x * scalar, y * scalar, z * scalar)
    operator fun div(scalar: Float) = Vector3(x / scalar, y / scalar, z / scalar)
    operator fun unaryMinus() = Vector3(-x, -y, -z)
    
    /**
     * Dot product: measures projection of one vector onto another
     * Used for calculating angles and projections
     */
    fun dot(other: Vector3): Float = x * other.x + y * other.y + z * other.z
    
    /**
     * Cross product: finds perpendicular vector
     * Used for calculating rotation axes and normals
     */
    fun cross(other: Vector3): Vector3 = Vector3(
        y * other.z - z * other.y,
        z * other.x - x * other.z,
        x * other.y - y * other.x
    )
    
    /**
     * Vector magnitude (length)
     */
    fun magnitude(): Float = sqrt(x * x + y * y + z * z)
    
    /**
     * Squared magnitude (faster, no sqrt)
     * Use when you only need to compare distances
     */
    fun magnitudeSquared(): Float = x * x + y * y + z * z
    
    /**
     * Returns unit vector in same direction
     */
    fun normalized(): Vector3 {
        val mag = magnitude()
        return if (mag > 0f) this / mag else zero()
    }
    
    /**
     * Linear interpolation between two vectors
     */
    fun lerp(other: Vector3, t: Float): Vector3 = 
        this * (1f - t) + other * t
    
    /**
     * Distance to another vector
     */
    fun distanceTo(other: Vector3): Float = (this - other).magnitude()
    
    /**
     * Convert to float array for OpenGL
     */
    fun toFloatArray(): FloatArray = floatArrayOf(x, y, z)
    
    companion object {
        fun zero() = Vector3(0f, 0f, 0f)
        fun one() = Vector3(1f, 1f, 1f)
        fun right() = Vector3(1f, 0f, 0f)
        fun up() = Vector3(0f, 1f, 0f)
        fun forward() = Vector3(0f, 0f, 1f)
        
        /**
         * Create from float array (for parsing external data)
         */
        fun fromFloatArray(array: FloatArray): Vector3 {
            require(array.size >= 3) { "Array must have at least 3 elements" }
            return Vector3(array[0], array[1], array[2])
        }
    }
}
