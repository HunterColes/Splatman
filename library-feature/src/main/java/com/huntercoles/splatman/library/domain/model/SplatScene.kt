package com.huntercoles.splatman.library.domain.model

import java.util.Date
import java.util.UUID

/**
 * Complete 3D Gaussian Splatting scene representation
 * 
 * This is the main data structure for a captured/optimized 3D scene.
 * Contains metadata, camera parameters, and all Gaussian primitives.
 * 
 * Mobile constraints:
 * - Max 200k Gaussians (memory limit ~40-80MB)
 * - Degree-0 SH only (3 coefficients vs 48)
 * - Single-precision floats (no doubles)
 */
data class SplatScene(
    /** Unique identifier for this scene */
    val id: String = UUID.randomUUID().toString(),
    
    /** User-friendly name */
    val name: String,
    
    /** Creation timestamp */
    val createdAt: Date = Date(),
    
    /** Last modification timestamp */
    val modifiedAt: Date = Date(),
    
    /** All Gaussian primitives in this scene */
    val gaussians: List<GaussianSplat>,
    
    /** Camera parameters used during capture */
    val cameraIntrinsics: CameraIntrinsics?,
    
    /** Bounding box of the scene (min, max corners) */
    val boundingBox: BoundingBox,
    
    /** Optional thumbnail image path (for library grid view) */
    val thumbnailPath: String? = null,
    
    /** File path where this scene is stored */
    val filePath: String? = null,
    
    /** Spherical harmonics degree (0 for mobile, 3 for desktop) */
    val shDegree: Int = 0,
    
    /** Capture metadata (device, settings, etc.) */
    val captureMetadata: CaptureMetadata? = null
) {
    /**
     * Total memory footprint in bytes
     * Useful for memory management and UI display
     */
    val sizeInBytes: Long
        get() = gaussians.sumOf { it.sizeInBytes.toLong() }
    
    /**
     * Memory footprint in megabytes
     */
    val sizeInMB: Float
        get() = sizeInBytes / (1024f * 1024f)
    
    /**
     * Number of Gaussian primitives
     */
    val gaussianCount: Int
        get() = gaussians.size
    
    /**
     * Check if this scene is within mobile memory limits
     */
    val isWithinMobileLimits: Boolean
        get() = gaussianCount <= MAX_MOBILE_GAUSSIANS && sizeInMB <= MAX_MOBILE_SIZE_MB
    
    companion object {
        /** Maximum Gaussians for mobile devices (200k) */
        const val MAX_MOBILE_GAUSSIANS = 200_000
        
        /** Maximum scene size in MB for mobile (100MB) */
        const val MAX_MOBILE_SIZE_MB = 100f
        
        /**
         * Create an empty scene template
         */
        fun empty(name: String = "Untitled Scene") = SplatScene(
            name = name,
            gaussians = emptyList(),
            cameraIntrinsics = null,
            boundingBox = BoundingBox.empty()
        )
    }
}

/**
 * Camera intrinsic parameters
 * Used for proper rendering and reprojection
 */
data class CameraIntrinsics(
    /** Focal length in pixels (fx, fy) */
    val focalLength: Pair<Float, Float>,
    
    /** Principal point in pixels (cx, cy) */
    val principalPoint: Pair<Float, Float>,
    
    /** Image resolution (width, height) */
    val imageSize: Pair<Int, Int>,
    
    /** Radial distortion coefficients (k1, k2, k3) */
    val distortion: FloatArray? = null
) {
    val aspectRatio: Float
        get() = imageSize.first.toFloat() / imageSize.second
    
    val fx: Float get() = focalLength.first
    val fy: Float get() = focalLength.second
    val cx: Float get() = principalPoint.first
    val cy: Float get() = principalPoint.second
    val width: Int get() = imageSize.first
    val height: Int get() = imageSize.second
}

/**
 * Axis-aligned bounding box
 */
data class BoundingBox(
    val min: FloatArray,
    val max: FloatArray
) {
    init {
        require(min.size == 3) { "Min must have 3 components" }
        require(max.size == 3) { "Max must have 3 components" }
    }
    
    val center: FloatArray
        get() = floatArrayOf(
            (min[0] + max[0]) / 2f,
            (min[1] + max[1]) / 2f,
            (min[2] + max[2]) / 2f
        )
    
    val size: FloatArray
        get() = floatArrayOf(
            max[0] - min[0],
            max[1] - min[1],
            max[2] - min[2]
        )
    
    val diagonal: Float
        get() {
            val s = size
            return kotlin.math.sqrt(s[0] * s[0] + s[1] * s[1] + s[2] * s[2])
        }
    
    companion object {
        fun empty() = BoundingBox(
            min = floatArrayOf(0f, 0f, 0f),
            max = floatArrayOf(0f, 0f, 0f)
        )
        
        fun fromPoints(points: List<FloatArray>): BoundingBox {
            require(points.isNotEmpty()) { "Cannot create bounding box from empty points" }
            
            val min = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
            val max = floatArrayOf(Float.MIN_VALUE, Float.MIN_VALUE, Float.MIN_VALUE)
            
            points.forEach { point ->
                for (i in 0..2) {
                    min[i] = minOf(min[i], point[i])
                    max[i] = maxOf(max[i], point[i])
                }
            }
            
            return BoundingBox(min, max)
        }
    }
    
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BoundingBox) return false
        return min.contentEquals(other.min) && max.contentEquals(other.max)
    }
    
    override fun hashCode(): Int {
        return 31 * min.contentHashCode() + max.contentHashCode()
    }
}

/**
 * Metadata captured during scene acquisition
 */
data class CaptureMetadata(
    /** Device model (e.g., "Pixel 8 Pro") */
    val deviceModel: String,
    
    /** Android version */
    val androidVersion: String,
    
    /** ARCore version */
    val arCoreVersion: String,
    
    /** Video capture duration in seconds */
    val captureDuration: Float,
    
    /** Number of frames extracted */
    val frameCount: Int,
    
    /** Frame sampling rate (e.g., 10 fps) */
    val samplingRate: Int,
    
    /** Optimization iterations performed */
    val optimizationIterations: Int,
    
    /** Processing time in seconds */
    val processingTime: Float,
    
    /** Average tracking quality [0.0, 1.0] */
    val averageTrackingQuality: Float
)
