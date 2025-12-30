package com.huntercoles.splatman.viewer.rendering.math

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.sqrt

/**
 * Unit tests for Vector3 math utilities
 * TDD: Tests written before implementation
 */
class Vector3Test {
    
    private val epsilon = 0.0001f
    
    @Test
    fun `zero vector has all components zero`() {
        val zero = Vector3.zero()
        assertEquals(0f, zero.x, epsilon)
        assertEquals(0f, zero.y, epsilon)
        assertEquals(0f, zero.z, epsilon)
    }
    
    @Test
    fun `unit vectors have correct values`() {
        val right = Vector3.right()
        assertEquals(1f, right.x, epsilon)
        assertEquals(0f, right.y, epsilon)
        assertEquals(0f, right.z, epsilon)
        
        val up = Vector3.up()
        assertEquals(0f, up.x, epsilon)
        assertEquals(1f, up.y, epsilon)
        assertEquals(0f, up.z, epsilon)
        
        val forward = Vector3.forward()
        assertEquals(0f, forward.x, epsilon)
        assertEquals(0f, forward.y, epsilon)
        assertEquals(1f, forward.z, epsilon)
    }
    
    @Test
    fun `addition combines components correctly`() {
        val v1 = Vector3(1f, 2f, 3f)
        val v2 = Vector3(4f, 5f, 6f)
        val result = v1 + v2
        
        assertEquals(5f, result.x, epsilon)
        assertEquals(7f, result.y, epsilon)
        assertEquals(9f, result.z, epsilon)
    }
    
    @Test
    fun `subtraction combines components correctly`() {
        val v1 = Vector3(5f, 7f, 9f)
        val v2 = Vector3(1f, 2f, 3f)
        val result = v1 - v2
        
        assertEquals(4f, result.x, epsilon)
        assertEquals(5f, result.y, epsilon)
        assertEquals(6f, result.z, epsilon)
    }
    
    @Test
    fun `scalar multiplication scales all components`() {
        val v = Vector3(1f, 2f, 3f)
        val result = v * 2f
        
        assertEquals(2f, result.x, epsilon)
        assertEquals(4f, result.y, epsilon)
        assertEquals(6f, result.z, epsilon)
    }
    
    @Test
    fun `dot product calculates correctly`() {
        val v1 = Vector3(1f, 2f, 3f)
        val v2 = Vector3(4f, 5f, 6f)
        val result = v1.dot(v2)
        
        // 1*4 + 2*5 + 3*6 = 4 + 10 + 18 = 32
        assertEquals(32f, result, epsilon)
    }
    
    @Test
    fun `cross product calculates correctly`() {
        val v1 = Vector3(1f, 0f, 0f)
        val v2 = Vector3(0f, 1f, 0f)
        val result = v1.cross(v2)
        
        // Should give (0, 0, 1)
        assertEquals(0f, result.x, epsilon)
        assertEquals(0f, result.y, epsilon)
        assertEquals(1f, result.z, epsilon)
    }
    
    @Test
    fun `magnitude calculates correctly`() {
        val v = Vector3(3f, 4f, 0f)
        val mag = v.magnitude()
        
        // sqrt(9 + 16) = 5
        assertEquals(5f, mag, epsilon)
    }
    
    @Test
    fun `normalized vector has unit length`() {
        val v = Vector3(3f, 4f, 0f)
        val normalized = v.normalized()
        
        assertEquals(1f, normalized.magnitude(), epsilon)
        assertEquals(0.6f, normalized.x, epsilon)
        assertEquals(0.8f, normalized.y, epsilon)
    }
    
    @Test
    fun `toFloatArray returns correct array`() {
        val v = Vector3(1f, 2f, 3f)
        val array = v.toFloatArray()
        
        assertArrayEquals(floatArrayOf(1f, 2f, 3f), array, epsilon)
    }
}
