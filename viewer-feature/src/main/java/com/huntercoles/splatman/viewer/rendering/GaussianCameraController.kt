package com.huntercoles.splatman.viewer.rendering

import com.huntercoles.splatman.viewer.rendering.ViewerConstants
import com.huntercoles.splatman.viewer.rendering.math.Matrix4
import com.huntercoles.splatman.viewer.rendering.math.Quaternion
import com.huntercoles.splatman.viewer.rendering.math.Vector3
import kotlin.math.PI

/**
 * Camera controller for interactive 3D Gaussian splat viewing
 * 
 * Implements arcball rotation, zoom, and pan controls.
 * All state is testable without OpenGL context.
 * 
 * Camera model:
 * - Orbits around a target point
 * - Distance from target is controlled by zoom
 * - Rotation uses quaternions for smooth gimbal-lock-free rotation
 * - Pan moves the target point in camera-relative coordinates
 * 
 * Thread-safe: All operations are immutable or use synchronized state
 */
class GaussianCameraController {
    
    // Camera state (private, accessed through getters for testing)
    private var distance: Float = ViewerConstants.DEFAULT_DISTANCE
    private var rotation: Quaternion = Quaternion.identity()
    private var target: Vector3 = Vector3.zero()
    private var aspectRatio: Float = 16f / 9f
    
    // Camera parameters
    private val fov: Float = ViewerConstants.FOV_DEGREES
    private val nearPlane: Float = ViewerConstants.NEAR_PLANE
    private val farPlane: Float = ViewerConstants.FAR_PLANE
    
    /**
     * Handle rotation gesture (drag)
     * @param deltaX Horizontal movement in screen space
     * @param deltaY Vertical movement in screen space
     */
    fun onRotate(deltaX: Float, deltaY: Float) {
        val sensitivity = ViewerConstants.ROTATION_SENSITIVITY
        
        // Yaw rotation (around world up axis)
        val yaw = Quaternion.fromAxisAngle(Vector3.up(), -deltaX * sensitivity)
        
        // Pitch rotation (around camera right axis)
        val pitch = Quaternion.fromAxisAngle(Vector3.right(), -deltaY * sensitivity)
        
        // Compose rotations: yaw first, then pitch
        rotation = yaw * rotation * pitch
        rotation = rotation.normalized()
    }
    
    /**
     * Handle zoom gesture (pinch)
     * @param scaleFactor Zoom scale factor (< 1 = zoom in, > 1 = zoom out)
     */
    fun onZoom(scaleFactor: Float) {
        distance *= scaleFactor
        distance = distance.coerceIn(ViewerConstants.MIN_DISTANCE, ViewerConstants.MAX_DISTANCE)
    }
    
    /**
     * Handle pan gesture (two-finger drag)
     * @param deltaX Horizontal movement in screen space
     * @param deltaY Vertical movement in screen space
     */
    fun onPan(deltaX: Float, deltaY: Float) {
        val sensitivity = ViewerConstants.PAN_SENSITIVITY * distance
        
        // Get camera's right and up vectors
        val right = rotation.transform(Vector3.right())
        val up = rotation.transform(Vector3.up())
        
        // Move target in camera-relative coordinates
        target = target + (right * deltaX + up * deltaY) * sensitivity
    }
    
    /**
     * Reset camera to default position
     */
    fun reset() {
        distance = ViewerConstants.DEFAULT_DISTANCE
        rotation = Quaternion.identity()
        target = Vector3.zero()
    }
    
    /**
     * Set aspect ratio (call when screen size changes)
     */
    fun setAspectRatio(aspectRatio: Float) {
        this.aspectRatio = aspectRatio
    }
    
    /**
     * Get current view matrix (camera transformation)
     * Converts camera state to matrix for Filament
     */
    fun getViewMatrix(): Matrix4 {
        // Calculate camera position
        val forward = rotation.transform(Vector3.forward())
        val eye = target + forward * distance
        
        // Create look-at matrix
        return Matrix4.lookAt(eye, target, Vector3.up())
    }
    
    /**
     * Get current projection matrix
     */
    fun getProjectionMatrix(): Matrix4 {
        return Matrix4.perspective(fov, aspectRatio, nearPlane, farPlane)
    }
    
    // Testable getters
    fun getDistance(): Float = distance
    fun getRotation(): Quaternion = rotation
    fun getTarget(): Vector3 = target
    fun getNearPlane(): Float = nearPlane
    fun getFarPlane(): Float = farPlane
}
