package com.huntercoles.splatman.viewer.rendering.math

import kotlin.math.tan

/**
 * 4x4 Matrix for 3D transformations
 * 
 * Column-major order (OpenGL/Filament convention):
 * [m0, m4, m8,  m12]   [0  4  8  12]
 * [m1, m5, m9,  m13] = [1  5  9  13]
 * [m2, m6, m10, m14]   [2  6  10 14]
 * [m3, m7, m11, m15]   [3  7  11 15]
 * 
 * @param values 16-element float array in column-major order
 */
data class Matrix4(val values: FloatArray) {
    
    init {
        require(values.size == 16) { "Matrix4 requires exactly 16 elements" }
    }
    
    /**
     * Matrix multiplication
     * Order matters: m1 * m2 applies m2 first, then m1
     */
    operator fun times(other: Matrix4): Matrix4 {
        val result = FloatArray(16)
        
        for (row in 0 until 4) {
            for (col in 0 until 4) {
                var sum = 0f
                for (i in 0 until 4) {
                    sum += values[row + i * 4] * other.values[i + col * 4]
                }
                result[row + col * 4] = sum
            }
        }
        
        return Matrix4(result)
    }
    
    /**
     * Transform a 3D point (applies translation)
     * Treats point as (x, y, z, 1)
     */
    fun transformPoint(point: Vector3): Vector3 {
        val x = values[0] * point.x + values[4] * point.y + values[8] * point.z + values[12]
        val y = values[1] * point.x + values[5] * point.y + values[9] * point.z + values[13]
        val z = values[2] * point.x + values[6] * point.y + values[10] * point.z + values[14]
        val w = values[3] * point.x + values[7] * point.y + values[11] * point.z + values[15]
        
        return Vector3(x / w, y / w, z / w)
    }
    
    /**
     * Transform a 3D direction vector (ignores translation)
     * Treats vector as (x, y, z, 0)
     */
    fun transformDirection(direction: Vector3): Vector3 {
        val x = values[0] * direction.x + values[4] * direction.y + values[8] * direction.z
        val y = values[1] * direction.x + values[5] * direction.y + values[9] * direction.z
        val z = values[2] * direction.x + values[6] * direction.y + values[10] * direction.z
        
        return Vector3(x, y, z)
    }
    
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Matrix4) return false
        return values.contentEquals(other.values)
    }
    
    override fun hashCode(): Int {
        return values.contentHashCode()
    }
    
    companion object {
        /**
         * Identity matrix (no transformation)
         */
        fun identity() = Matrix4(floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f
        ))
        
        /**
         * Create translation matrix
         */
        fun translation(offset: Vector3) = Matrix4(floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            offset.x, offset.y, offset.z, 1f
        ))
        
        /**
         * Create scaling matrix
         */
        fun scaling(scale: Vector3) = Matrix4(floatArrayOf(
            scale.x, 0f, 0f, 0f,
            0f, scale.y, 0f, 0f,
            0f, 0f, scale.z, 0f,
            0f, 0f, 0f, 1f
        ))
        
        /**
         * Create view matrix (camera transformation)
         * @param eye Camera position
         * @param target Point camera is looking at
         * @param up Up direction (usually (0, 1, 0))
         */
        fun lookAt(eye: Vector3, target: Vector3, up: Vector3): Matrix4 {
            val forward = (target - eye).normalized()
            val right = forward.cross(up).normalized()
            val realUp = right.cross(forward)
            
            return Matrix4(floatArrayOf(
                // Column 0: right
                right.x,
                realUp.x,
                -forward.x,
                0f,
                
                // Column 1: up
                right.y,
                realUp.y,
                -forward.y,
                0f,
                
                // Column 2: forward
                right.z,
                realUp.z,
                -forward.z,
                0f,
                
                // Column 3: translation
                -right.dot(eye),
                -realUp.dot(eye),
                forward.dot(eye),
                1f
            ))
        }
        
        /**
         * Create perspective projection matrix
         * @param fovDegrees Field of view in degrees
         * @param aspectRatio Width / height
         * @param near Near clipping plane
         * @param far Far clipping plane
         */
        fun perspective(fovDegrees: Float, aspectRatio: Float, near: Float, far: Float): Matrix4 {
            val fovRadians = Math.toRadians(fovDegrees.toDouble()).toFloat()
            val tanHalfFov = tan(fovRadians / 2f)
            
            val m00 = 1f / (aspectRatio * tanHalfFov)
            val m11 = 1f / tanHalfFov
            val m22 = -(far + near) / (far - near)
            val m23 = -(2f * far * near) / (far - near)
            
            return Matrix4(floatArrayOf(
                m00, 0f, 0f, 0f,
                0f, m11, 0f, 0f,
                0f, 0f, m22, -1f,
                0f, 0f, m23, 0f
            ))
        }
        
        /**
         * Create orthographic projection matrix
         * @param left Left clipping plane
         * @param right Right clipping plane
         * @param bottom Bottom clipping plane
         * @param top Top clipping plane
         * @param near Near clipping plane
         * @param far Far clipping plane
         */
        fun orthographic(
            left: Float,
            right: Float,
            bottom: Float,
            top: Float,
            near: Float,
            far: Float
        ): Matrix4 {
            val rl = right - left
            val tb = top - bottom
            val fn = far - near
            
            return Matrix4(floatArrayOf(
                2f / rl, 0f, 0f, 0f,
                0f, 2f / tb, 0f, 0f,
                0f, 0f, -2f / fn, 0f,
                -(right + left) / rl, -(top + bottom) / tb, -(far + near) / fn, 1f
            ))
        }
        
        /**
         * Create rotation matrix from quaternion
         */
        fun fromQuaternion(quaternion: Quaternion): Matrix4 {
            return Matrix4(quaternion.toMatrix4())
        }
    }
}
