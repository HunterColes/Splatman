package com.huntercoles.splatman.viewer.rendering.math

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sqrt

/**
 * Unit tests for Quaternion rotation utilities
 * TDD: Tests written before implementation
 */
class QuaternionTest {
    
    private val epsilon = 0.0001f
    
    @Test
    fun `identity quaternion has correct values`() {
        val identity = Quaternion.identity()
        assertEquals(0f, identity.x, epsilon)
        assertEquals(0f, identity.y, epsilon)
        assertEquals(0f, identity.z, epsilon)
        assertEquals(1f, identity.w, epsilon)
    }
    
    @Test
    fun `fromAxisAngle creates correct rotation around X axis`() {
        val angle = PI.toFloat() / 2f // 90 degrees
        val axis = Vector3.right()
        val quat = Quaternion.fromAxisAngle(axis, angle)
        
        // For 90° rotation around X: (sin(45°), 0, 0, cos(45°))
        val expectedSin = sqrt(2f) / 2f
        assertEquals(expectedSin, quat.x, epsilon)
        assertEquals(0f, quat.y, epsilon)
        assertEquals(0f, quat.z, epsilon)
        assertEquals(expectedSin, quat.w, epsilon)
    }
    
    @Test
    fun `quaternion multiplication composes rotations`() {
        // Rotate 90° around Y, then 90° around X
        val rotY = Quaternion.fromAxisAngle(Vector3.up(), PI.toFloat() / 2f)
        val rotX = Quaternion.fromAxisAngle(Vector3.right(), PI.toFloat() / 2f)
        val combined = rotX * rotY
        
        // Result should be normalized
        val magnitude = sqrt(combined.x * combined.x + combined.y * combined.y + 
                             combined.z * combined.z + combined.w * combined.w)
        assertEquals(1f, magnitude, epsilon)
    }
    
    @Test
    fun `transform rotates vector correctly`() {
        // 90° rotation around Z axis
        val quat = Quaternion.fromAxisAngle(Vector3.forward(), PI.toFloat() / 2f)
        val vector = Vector3.right() // (1, 0, 0)
        val result = quat.transform(vector)
        
        // Should rotate to (0, 1, 0)
        assertEquals(0f, result.x, epsilon)
        assertEquals(1f, result.y, epsilon)
        assertEquals(0f, result.z, epsilon)
    }
    
    @Test
    fun `normalized quaternion has unit magnitude`() {
        val quat = Quaternion(1f, 2f, 3f, 4f)
        val normalized = quat.normalized()
        
        val magnitude = sqrt(normalized.x * normalized.x + normalized.y * normalized.y + 
                             normalized.z * normalized.z + normalized.w * normalized.w)
        assertEquals(1f, magnitude, epsilon)
    }
    
    @Test
    fun `conjugate inverts rotation`() {
        val quat = Quaternion.fromAxisAngle(Vector3.up(), PI.toFloat() / 4f)
        val conjugate = quat.conjugate()
        
        // q * q_conjugate should be identity
        val result = quat * conjugate
        assertEquals(0f, result.x, epsilon)
        assertEquals(0f, result.y, epsilon)
        assertEquals(0f, result.z, epsilon)
        assertEquals(1f, result.w, epsilon)
    }
    
    @Test
    fun `toMatrix4 creates valid rotation matrix`() {
        val quat = Quaternion.fromAxisAngle(Vector3.up(), PI.toFloat() / 2f)
        val matrix = quat.toMatrix4()
        
        // Matrix should be 16 elements
        assertEquals(16, matrix.size)
        
        // For 90° Y rotation, matrix[0] should be ~0, matrix[2] should be ~1
        assertEquals(0f, matrix[0], epsilon)
        assertEquals(1f, matrix[2], epsilon)
    }
}
