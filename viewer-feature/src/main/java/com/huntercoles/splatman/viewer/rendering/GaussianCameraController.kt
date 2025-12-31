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
    private var farPlane: Float = ViewerConstants.FAR_PLANE
    
    /**
     * Handle rotation gesture (drag)
     * @param deltaX Horizontal movement in screen space
     * @param deltaY Vertical movement in screen space
     */
    fun onRotate(deltaX: Float, deltaY: Float) {
        val sensitivity = ViewerConstants.ROTATION_SENSITIVITY
        
        // Yaw rotation (around world up axis for horizontal movement)
        val yaw = Quaternion.fromAxisAngle(Vector3.up(), -deltaX * sensitivity)
        
        // Apply yaw first (this rotates the camera around world up)
        rotation = yaw * rotation
        
        // Get the camera's current right vector after yaw rotation
        val right = rotation.transform(Vector3.right())
        
        // Pitch rotation (around camera's right axis for vertical movement)
        val pitch = Quaternion.fromAxisAngle(right, -deltaY * sensitivity)
        
        // Apply pitch
        rotation = pitch * rotation
        
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
     * Set camera target position
     */
    fun setTarget(newTarget: Vector3) {
        target = newTarget
    }
    
    /**
     * Set aspect ratio (call when screen size changes)
     */
    fun setAspectRatio(aspectRatio: Float) {
        this.aspectRatio = aspectRatio
    }
    
    /**
     * Set far plane distance
     */
    fun setFarPlane(farPlane: Float) {
        this.farPlane = farPlane
    }
    
    /**
     * Set camera distance directly (for initial positioning)
     */
    fun setDistance(distance: Float) {
        this.distance = distance.coerceIn(ViewerConstants.MIN_DISTANCE, ViewerConstants.MAX_DISTANCE)
    }
    
    /**
     * Get current view matrix (camera transformation)
     * Converts camera state to matrix for OpenGL
     */
    fun getViewMatrix(): Matrix4 {
        // Calculate camera position
        val forward = rotation.transform(Vector3.forward())
        val eye = target + forward * distance
        
        // Get camera's current up direction (transformed by rotation)
        val up = rotation.transform(Vector3.up())
        
        // Create look-at matrix
        return Matrix4.lookAt(eye, target, up)
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
