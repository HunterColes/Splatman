package com.huntercoles.splatman.library.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.huntercoles.splatman.library.domain.model.BoundingBox
import com.huntercoles.splatman.library.domain.model.CameraIntrinsics
import com.huntercoles.splatman.library.domain.model.CaptureMetadata
import com.huntercoles.splatman.library.domain.model.GaussianSplat
import com.huntercoles.splatman.library.domain.model.SplatScene
import java.util.Date

/**
 * Room database entity for Splat scene metadata
 * 
 * We only store metadata in the database, not the actual Gaussian data.
 * Gaussian data is stored in binary files (.splat format) for efficiency.
 * 
 * This keeps the database small and fast while allowing:
 * - Quick library browsing (metadata only)
 * - Search/filter without loading files
 * - Lazy loading of actual scene data
 */
@Entity(tableName = "splat_scenes")
data class SplatSceneEntity(
    @PrimaryKey
    val id: String,
    
    /** User-friendly name */
    val name: String,
    
    /** Creation timestamp */
    val createdAt: Long,
    
    /** Last modification timestamp */
    val modifiedAt: Long,
    
    /** Number of Gaussian primitives */
    val gaussianCount: Int,
    
    /** File size in bytes */
    val fileSizeBytes: Long,
    
    /** Spherical harmonics degree (0 or 3) */
    val shDegree: Int,
    
    /** File path to .splat file */
    val filePath: String,
    
    /** Optional thumbnail image path */
    val thumbnailPath: String?,
    
    /** Bounding box min (x, y, z) as comma-separated string */
    val boundingBoxMin: String,
    
    /** Bounding box max (x, y, z) as comma-separated string */
    val boundingBoxMax: String,
    
    /** Camera focal length (fx, fy) or null */
    val cameraFocalLength: String?,
    
    /** Camera principal point (cx, cy) or null */
    val cameraPrincipalPoint: String?,
    
    /** Camera image size (width, height) or null */
    val cameraImageSize: String?,
    
    /** Capture device model */
    val captureDeviceModel: String?,
    
    /** Capture duration in seconds */
    val captureDuration: Float?,
    
    /** Number of frames used during capture */
    val captureFrameCount: Int?,
    
    /** Optimization iterations performed */
    val optimizationIterations: Int?,
    
    /** Processing time in seconds */
    val processingTime: Float?,
    
    /** Average ARCore tracking quality [0.0, 1.0] */
    val averageTrackingQuality: Float?
) {
    companion object {
        /**
         * Convert domain model to database entity
         */
        fun fromDomain(
            scene: SplatScene,
            filePath: String,
            thumbnailPath: String?
        ): SplatSceneEntity {
            return SplatSceneEntity(
                id = scene.id,
                name = scene.name,
                createdAt = scene.createdAt.time,
                modifiedAt = scene.modifiedAt.time,
                gaussianCount = scene.gaussianCount,
                fileSizeBytes = scene.sizeInBytes,
                shDegree = scene.shDegree,
                filePath = filePath,
                thumbnailPath = thumbnailPath,
                boundingBoxMin = scene.boundingBox.min.joinToString(","),
                boundingBoxMax = scene.boundingBox.max.joinToString(","),
                cameraFocalLength = scene.cameraIntrinsics?.let { 
                    "${it.fx},${it.fy}" 
                },
                cameraPrincipalPoint = scene.cameraIntrinsics?.let { 
                    "${it.cx},${it.cy}" 
                },
                cameraImageSize = scene.cameraIntrinsics?.let { 
                    "${it.width},${it.height}" 
                },
                captureDeviceModel = scene.captureMetadata?.deviceModel,
                captureDuration = scene.captureMetadata?.captureDuration,
                captureFrameCount = scene.captureMetadata?.frameCount,
                optimizationIterations = scene.captureMetadata?.optimizationIterations,
                processingTime = scene.captureMetadata?.processingTime,
                averageTrackingQuality = scene.captureMetadata?.averageTrackingQuality
            )
        }
    }
    
    /**
     * Get file size in megabytes
     */
    val fileSizeMB: Float
        get() = fileSizeBytes / (1024f * 1024f)
    
    /**
     * Get creation date
     */
    val createdDate: Date
        get() = Date(createdAt)
    
    /**
     * Get modification date
     */
    val modifiedDate: Date
        get() = Date(modifiedAt)
    
    /**
     * Parse bounding box min
     */
    fun parseBoundingBoxMin(): FloatArray {
        return boundingBoxMin.split(",").map { it.toFloat() }.toFloatArray()
    }
    
    /**
     * Parse bounding box max
     */
    fun parseBoundingBoxMax(): FloatArray {
        return boundingBoxMax.split(",").map { it.toFloat() }.toFloatArray()
    }
    
    /**
     * Convert entity to domain model
     * Requires Gaussian data to be loaded separately from file
     */
    fun toDomain(gaussians: List<GaussianSplat>): SplatScene {
        return SplatScene(
            id = id,
            name = name,
            createdAt = Date(createdAt),
            modifiedAt = Date(modifiedAt),
            gaussians = gaussians,
            cameraIntrinsics = parseCameraIntrinsics(),
            boundingBox = BoundingBox(
                min = parseBoundingBoxMin(),
                max = parseBoundingBoxMax()
            ),
            thumbnailPath = thumbnailPath,
            filePath = filePath,
            shDegree = shDegree,
            captureMetadata = parseCaptureMetadata()
        )
    }
    
    /**
     * Parse camera intrinsics from stored strings
     */
    private fun parseCameraIntrinsics(): CameraIntrinsics? {
        if (cameraFocalLength == null || cameraPrincipalPoint == null || cameraImageSize == null) {
            return null
        }
        
        val (fx, fy) = cameraFocalLength.split(",").map { it.toFloat() }
        val (cx, cy) = cameraPrincipalPoint.split(",").map { it.toFloat() }
        val (width, height) = cameraImageSize.split(",").map { it.toInt() }
        
        return CameraIntrinsics(
            focalLength = Pair(fx, fy),
            principalPoint = Pair(cx, cy),
            imageSize = Pair(width, height)
        )
    }
    
    /**
     * Parse capture metadata from stored fields
     */
    private fun parseCaptureMetadata(): CaptureMetadata? {
        // Only return metadata if required fields are present
        if (captureDeviceModel == null ||
            captureDuration == null ||
            captureFrameCount == null ||
            optimizationIterations == null ||
            processingTime == null ||
            averageTrackingQuality == null
        ) {
            return null
        }
        
        return CaptureMetadata(
            deviceModel = captureDeviceModel,
            androidVersion = "Unknown", // Not stored in MVP
            arCoreVersion = "Unknown", // Not stored in MVP
            captureDuration = captureDuration,
            frameCount = captureFrameCount,
            samplingRate = 10, // Default - not stored in MVP
            optimizationIterations = optimizationIterations,
            processingTime = processingTime,
            averageTrackingQuality = averageTrackingQuality
        )
    }
}
