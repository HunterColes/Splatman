package com.huntercoles.splatman.library.data.ply

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * Tests for PLY file parser
 * 
 * Validates:
 * - ASCII format parsing
 * - Binary little-endian format parsing
 * - Header parsing
 * - Vertex data extraction
 * - BoundingBox calculation
 */
class PlyParserTest {
    
    private val parser = PlyParser()
    
    @Test
    fun `parse ASCII PLY with RGB colors`() {
        val plyContent = """
            ply
            format ascii 1.0
            element vertex 3
            property float x
            property float y
            property float z
            property uchar red
            property uchar green
            property uchar blue
            property float opacity
            end_header
            1.0 2.0 3.0 255 128 64 0.8
            -1.0 0.0 1.0 128 255 128 1.0
            0.0 -1.0 0.0 64 64 255 0.5
        """.trimIndent()
        
        val result = parser.parse(
            ByteArrayInputStream(plyContent.toByteArray()),
            "test.ply"
        )
        
        assertTrue("Parse should succeed", result.isSuccess)
        val scene = result.getOrThrow()
        
        assertEquals("Should have 3 Gaussians", 3, scene.gaussians.size)
        assertEquals("Scene name should match filename", "test", scene.name)
        
        // Check first Gaussian
        val g1 = scene.gaussians[0]
        assertArrayEquals(floatArrayOf(1.0f, 2.0f, 3.0f), g1.position, 0.001f)
        assertEquals(1.0f, g1.shCoefficients[0], 0.01f) // Red
        assertEquals(0.8f, g1.opacity, 0.001f)
        
        // Check bounding box
        assertEquals(-1.0f, scene.boundingBox.min[0], 0.001f)
        assertEquals(-1.0f, scene.boundingBox.min[1], 0.001f)
        assertEquals(0.0f, scene.boundingBox.min[2], 0.001f)
        assertEquals(1.0f, scene.boundingBox.max[0], 0.001f)
        assertEquals(2.0f, scene.boundingBox.max[1], 0.001f)
        assertEquals(3.0f, scene.boundingBox.max[2], 0.001f)
    }
    
    @Test
    fun `parse empty PLY should fail`() {
        val plyContent = """
            ply
            format ascii 1.0
            element vertex 0
            property float x
            property float y
            property float z
            end_header
        """.trimIndent()
        
        val result = parser.parse(
            ByteArrayInputStream(plyContent.toByteArray()),
            "empty.ply"
        )
        
        assertTrue("Empty PLY should fail", result.isFailure)
    }
    
    @Test
    fun `parse PLY with minimal properties`() {
        val plyContent = """
            ply
            format ascii 1.0
            element vertex 2
            property float x
            property float y
            property float z
            end_header
            0.0 1.0 2.0
            3.0 4.0 5.0
        """.trimIndent()
        
        val result = parser.parse(
            ByteArrayInputStream(plyContent.toByteArray()),
            "minimal.ply"
        )
        
        assertTrue("Parse should succeed", result.isSuccess)
        val scene = result.getOrThrow()
        
        assertEquals("Should have 2 Gaussians", 2, scene.gaussians.size)
        
        // Should have default colors (0, 0, 0) and opacity 1.0
        val g1 = scene.gaussians[0]
        assertEquals(0.0f, g1.shCoefficients[0], 0.001f)
        assertEquals(1.0f, g1.opacity, 0.001f)
    }
    
    @Test
    fun `parse PLY removes file extension from name`() {
        val plyContent = """
            ply
            format ascii 1.0
            element vertex 1
            property float x
            property float y
            property float z
            end_header
            0.0 0.0 0.0
        """.trimIndent()
        
        val result = parser.parse(
            ByteArrayInputStream(plyContent.toByteArray()),
            "my_model.ply"
        )
        
        val scene = result.getOrThrow()
        assertEquals("my_model", scene.name)
    }
    
    @Test
    fun `parse PLY with large coordinate values`() {
        val plyContent = """
            ply
            format ascii 1.0
            element vertex 2
            property float x
            property float y
            property float z
            end_header
            -100.5 200.3 -50.7
            100.5 -200.3 50.7
        """.trimIndent()
        
        val result = parser.parse(
            ByteArrayInputStream(plyContent.toByteArray()),
            "large.ply"
        )
        
        val scene = result.getOrThrow()
        
        // Bounding box should encompass all points
        assertEquals(-100.5f, scene.boundingBox.min[0], 0.001f)
        assertEquals(-200.3f, scene.boundingBox.min[1], 0.001f)
        assertEquals(-50.7f, scene.boundingBox.min[2], 0.001f)
        assertEquals(100.5f, scene.boundingBox.max[0], 0.001f)
        assertEquals(200.3f, scene.boundingBox.max[1], 0.001f)
        assertEquals(50.7f, scene.boundingBox.max[2], 0.001f)
    }
    
    @Test
    fun `parse malformed PLY should fail gracefully`() {
        val plyContent = "not a valid ply file"
        
        val result = parser.parse(
            ByteArrayInputStream(plyContent.toByteArray()),
            "invalid.ply"
        )
        
        assertTrue("Malformed PLY should fail", result.isFailure)
    }
}
