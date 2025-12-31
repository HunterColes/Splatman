package com.huntercoles.splatman.viewer.rendering.math

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Quaternion for representing 3D rotations
 * 
 * Quaternions avoid gimbal lock and provide smooth interpolation (slerp).
 * Represented as (x, y, z, w) where w is the scalar component.
 * 
 * Normalized quaternions (magnitude = 1) represent valid rotations.
 * 
 * @param x X component (imaginary i)
 * @param y Y component (imaginary j)
 * @param z Z component (imaginary k)
 * @param w W component (real/scalar)
 */
data class Quaternion(
    val x: Float,
    val y: Float,
    val z: Float,
    val w: Float
) {
    /**
     * Quaternion multiplication (composition of rotations)
     * Order matters: q1 * q2 applies q2 first, then q1
     */
    operator fun times(other: Quaternion): Quaternion {
        return Quaternion(
            w * other.x + x * other.w + y * other.z - z * other.y,
            w * other.y - x * other.z + y * other.w + z * other.x,
            w * other.z + x * other.y - y * other.x + z * other.w,
            w * other.w - x * other.x - y * other.y - z * other.z
        )
    }
    
    /**
     * Quaternion magnitude
     */
    fun magnitude(): Float = sqrt(x * x + y * y + z * z + w * w)
    
    /**
     * Normalize to unit quaternion (valid rotation)
     */
    fun normalized(): Quaternion {
        val mag = magnitude()
        return if (mag > 0f) {
            Quaternion(x / mag, y / mag, z / mag, w / mag)
        } else {
            identity()
        }
    }
    
    /**
     * Quaternion conjugate (inverse rotation if normalized)
     */
    fun conjugate(): Quaternion = Quaternion(-x, -y, -z, w)
    
    /**
     * Transform (rotate) a vector by this quaternion
     * Uses formula: v' = q * v * q_conjugate
     */
    fun transform(vector: Vector3): Vector3 {
        // Convert vector to quaternion (w = 0)
        val vecQuat = Quaternion(vector.x, vector.y, vector.z, 0f)
        
        // q * v * q_conjugate
        val result = this * vecQuat * conjugate()
        
        return Vector3(result.x, result.y, result.z)
    }
    
    /**
     * Convert quaternion to 4x4 rotation matrix
     * Column-major order for OpenGL
     */
    fun toMatrix4(): FloatArray {
        val xx = x * x
        val yy = y * y
        val zz = z * z
        val xy = x * y
        val xz = x * z
        val yz = y * z
        val wx = w * x
        val wy = w * y
        val wz = w * z
        
        return floatArrayOf(
            // Column 0
            1f - 2f * (yy + zz),
            2f * (xy + wz),
            2f * (xz - wy),
            0f,
            
            // Column 1
            2f * (xy - wz),
            1f - 2f * (xx + zz),
            2f * (yz + wx),
            0f,
            
            // Column 2
            2f * (xz + wy),
            2f * (yz - wx),
            1f - 2f * (xx + yy),
            0f,
            
            // Column 3
            0f, 0f, 0f, 1f
        )
    }
    
    companion object {
        /**
         * Identity quaternion (no rotation)
         */
        fun identity() = Quaternion(0f, 0f, 0f, 1f)
        
        /**
         * Create quaternion from axis-angle representation
         * @param axis Normalized rotation axis
         * @param angle Rotation angle in radians
         */
        fun fromAxisAngle(axis: Vector3, angle: Float): Quaternion {
            val halfAngle = angle / 2f
            val s = sin(halfAngle)
            val normalized = axis.normalized()
            
            return Quaternion(
                normalized.x * s,
                normalized.y * s,
                normalized.z * s,
                cos(halfAngle)
            )
        }
        
        /**
         * Create quaternion from Euler angles (pitch, yaw, roll)
         * @param pitch Rotation around X axis (radians)
         * @param yaw Rotation around Y axis (radians)
         * @param roll Rotation around Z axis (radians)
         */
        fun fromEuler(pitch: Float, yaw: Float, roll: Float): Quaternion {
            val cy = cos(yaw * 0.5f)
            val sy = sin(yaw * 0.5f)
            val cp = cos(pitch * 0.5f)
            val sp = sin(pitch * 0.5f)
            val cr = cos(roll * 0.5f)
            val sr = sin(roll * 0.5f)
            
            return Quaternion(
                sr * cp * cy - cr * sp * sy,
                cr * sp * cy + sr * cp * sy,
                cr * cp * sy - sr * sp * cy,
                cr * cp * cy + sr * sp * sy
            )
        }
        
        /**
         * Spherical linear interpolation (smooth rotation interpolation)
         * @param a Start quaternion
         * @param b End quaternion
         * @param t Interpolation factor [0, 1]
         */
        fun slerp(a: Quaternion, b: Quaternion, t: Float): Quaternion {
            var dot = a.x * b.x + a.y * b.y + a.z * b.z + a.w * b.w
            
            // If dot product is negative, negate one quaternion to take shorter path
            val b2 = if (dot < 0f) {
                dot = -dot
                Quaternion(-b.x, -b.y, -b.z, -b.w)
            } else {
                b
            }
            
            // Linear interpolation for nearly parallel quaternions
            if (dot > 0.9995f) {
                return Quaternion(
                    a.x + t * (b2.x - a.x),
                    a.y + t * (b2.y - a.y),
                    a.z + t * (b2.z - a.z),
                    a.w + t * (b2.w - a.w)
                ).normalized()
            }
            
            // Spherical interpolation
            val theta0 = kotlin.math.acos(dot)
            val theta = theta0 * t
            val sinTheta = sin(theta)
            val sinTheta0 = sin(theta0)
            
            val s0 = cos(theta) - dot * sinTheta / sinTheta0
            val s1 = sinTheta / sinTheta0
            
            return Quaternion(
                s0 * a.x + s1 * b2.x,
                s0 * a.y + s1 * b2.y,
                s0 * a.z + s1 * b2.z,
                s0 * a.w + s1 * b2.w
            )
        }
    }
}
