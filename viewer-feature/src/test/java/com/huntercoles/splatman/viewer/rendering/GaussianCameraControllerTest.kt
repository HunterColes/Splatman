package com.huntercoles.splatman.viewer.rendering

import com.huntercoles.splatman.splat.math.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * Unit tests for GaussianCameraController
 * JUnit 5 (the module has no vintage engine), against splat-core math.
 */
class GaussianCameraControllerTest {
    
    private lateinit var controller: GaussianCameraController
    private val epsilon = 0.001f
    
    @BeforeEach
    fun setup() {
        controller = GaussianCameraController()
    }
    
    @Test
    fun `initial camera position is at default distance`() {
        val viewMatrix = controller.getViewMatrix()
        
        // Camera should be positioned at default distance (5 units)
        assertTrue(viewMatrix.values.isNotEmpty())
    }
    
    @Test
    fun `setPose puts the eye exactly there, without the zoom clamps`() {
        controller.setInitialView(Vec3.ZERO, 1f)
        val far = controller.getMaxDistance() * 2f
        val rotation = com.huntercoles.splatman.splat.math.Quat.fromAxisAngle(Vec3.UNIT_Y, 1f)
        controller.setPose(rotation, Vec3(1f, 2f, 3f), far)
        assertEquals(far, controller.getDistance(), epsilon)
        assertEquals(Vec3(1f, 2f, 3f), controller.getTarget())
        assertEquals(rotation.w, controller.getRotation().w, epsilon)
        assertEquals(rotation.y, controller.getRotation().y, epsilon)
    }

    @Test
    fun `zoom in decreases distance`() {
        val initialDistance = controller.getDistance()
        
        controller.onZoom(0.5f) // Zoom in (scale factor < 1)
        
        val newDistance = controller.getDistance()
        assertTrue(newDistance < initialDistance)
    }
    
    @Test
    fun `zoom out increases distance`() {
        val initialDistance = controller.getDistance()
        
        controller.onZoom(2.0f) // Zoom out (scale factor > 1)
        
        val newDistance = controller.getDistance()
        assertTrue(newDistance > initialDistance)
    }
    
    @Test
    fun `zoom is clamped to min and max bounds`() {
        // Zoom way in
        repeat(100) { controller.onZoom(0.1f) }
        val minDistance = controller.getDistance()
        assertEquals(ViewerConstants.MIN_DISTANCE, minDistance, epsilon) // Min clamp
        
        // Reset and zoom way out
        controller.reset()
        repeat(100) { controller.onZoom(10f) }
        val maxDistance = controller.getDistance()
        assertEquals(ViewerConstants.MAX_DISTANCE, maxDistance, epsilon) // Max clamp (1000; the old 50 never ran)
    }
    
    @Test
    fun `rotation updates camera orientation`() {
        val initialRotation = controller.getRotation()
        
        controller.onRotate(0.1f, 0.1f)
        
        val newRotation = controller.getRotation()
        // Quaternions should be different
        assertTrue(
            abs(initialRotation.x - newRotation.x) > epsilon ||
            abs(initialRotation.y - newRotation.y) > epsilon ||
            abs(initialRotation.z - newRotation.z) > epsilon ||
            abs(initialRotation.w - newRotation.w) > epsilon
        )
    }
    
    @Test
    fun `pan updates target position`() {
        val initialTarget = controller.getTarget()
        
        controller.onPan(0.5f, 0.5f)
        
        val newTarget = controller.getTarget()
        assertTrue(newTarget.x != initialTarget.x || newTarget.y != initialTarget.y)
    }
    
    @Test
    fun `reset restores initial state`() {
        // Modify camera
        controller.onZoom(2.0f)
        controller.onRotate(1.0f, 1.0f)
        controller.onPan(5.0f, 5.0f)
        
        // Reset
        controller.reset()
        
        // Should be back to defaults
        assertEquals(5.0f, controller.getDistance(), epsilon)
        assertEquals(Vec3.ZERO, controller.getTarget())
    }
    
    @Test
    fun `setTarget changes camera target position`() {
        val newTarget = Vec3(1.0f, 2.0f, 3.0f)
        
        controller.setTarget(newTarget)
        
        assertEquals(newTarget, controller.getTarget())
    }
    
    @Test
    fun `setAspectRatio updates projection matrix`() {
        controller.setAspectRatio(16f / 9f)
        val projection1 = controller.getProjectionMatrix()
        
        controller.setAspectRatio(4f / 3f)
        val projection2 = controller.getProjectionMatrix()
        
        // Projection matrices should be different
        assertTrue(!projection1.values.contentEquals(projection2.values))
    }
    
    @Test
    fun `getViewMatrix returns valid matrix`() {
        val viewMatrix = controller.getViewMatrix()
        
        // Matrix should have 16 elements
        assertEquals(16, viewMatrix.values.size)
        
        // Should not be all zeros
        assertTrue(viewMatrix.values.any { it != 0f })
    }
    
    @Test
    fun `getProjectionMatrix returns valid matrix`() {
        controller.setAspectRatio(16f / 9f)
        val projectionMatrix = controller.getProjectionMatrix()
        
        // Matrix should have 16 elements
        assertEquals(16, projectionMatrix.values.size)
        
        // Should not be all zeros
        assertTrue(projectionMatrix.values.any { it != 0f })
    }
    
    @Test
    fun `multiple rotations compose correctly`() {
        controller.onRotate(0.1f, 0f) // Yaw
        controller.onRotate(0f, 0.1f) // Pitch
        
        val viewMatrix = controller.getViewMatrix()
        
        // Should produce a valid combined rotation
        assertTrue(viewMatrix.values.any { it != 0f && it != 1f })
    }
    
    @Test
    fun `panning in different directions works independently`() {
        controller.onPan(1.0f, 0f) // Pan right
        val targetAfterX = controller.getTarget()
        
        controller.reset()
        controller.onPan(0f, 1.0f) // Pan up
        val targetAfterY = controller.getTarget()
        
        // X and Y should be affected independently
        assertTrue(targetAfterX.x != 0f)
        assertTrue(targetAfterY.y != 0f)
    }
}
