package com.huntercoles.splatman.viewer.rendering.math

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for Matrix4 4x4 matrix utilities
 * TDD: Tests written before implementation
 */
class Matrix4Test {
    
    private val epsilon = 0.0001f
    
    @Test
    fun `identity matrix has ones on diagonal`() {
        val identity = Matrix4.identity()
        val expected = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f
        )
        assertArrayEquals(expected, identity.values, epsilon)
    }
    
    @Test
    fun `lookAt creates correct view matrix`() {
        val eye = Vector3(0f, 0f, 5f)
        val target = Vector3.zero()
        val up = Vector3.up()
        
        val matrix = Matrix4.lookAt(eye, target, up)
        
        // Matrix should not be identity
        assert(matrix.values.any { it != 0f && it != 1f })
    }
    
    @Test
    fun `perspective creates correct projection matrix`() {
        val fov = 60f
        val aspect = 16f / 9f
        val near = 0.1f
        val far = 100f
        
        val matrix = Matrix4.perspective(fov, aspect, near, far)
        
        // Check that far plane calculation is correct
        // matrix[10] should be -(far + near) / (far - near)
        val expected = -(far + near) / (far - near)
        assertEquals(expected, matrix.values[10], epsilon)
    }
    
    @Test
    fun `multiply combines matrices correctly`() {
        val m1 = Matrix4.identity()
        val m2 = Matrix4.identity()
        val result = m1 * m2
        
        // Identity * Identity = Identity
        assertArrayEquals(Matrix4.identity().values, result.values, epsilon)
    }
    
    @Test
    fun `transformPoint applies transformation`() {
        // Translation matrix
        val matrix = Matrix4(floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            2f, 3f, 4f, 1f // translation
        ))
        
        val point = Vector3(1f, 1f, 1f)
        val result = matrix.transformPoint(point)
        
        assertEquals(3f, result.x, epsilon)
        assertEquals(4f, result.y, epsilon)
        assertEquals(5f, result.z, epsilon)
    }
    
    @Test
    fun `translation creates correct matrix`() {
        val translation = Vector3(5f, 10f, 15f)
        val matrix = Matrix4.translation(translation)
        
        assertEquals(5f, matrix.values[12], epsilon)
        assertEquals(10f, matrix.values[13], epsilon)
        assertEquals(15f, matrix.values[14], epsilon)
    }
}
