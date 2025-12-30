package com.huntercoles.splatman.library.data.storage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.huntercoles.splatman.library.domain.model.GaussianSplat
import com.huntercoles.splatman.library.domain.model.SplatScene
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages file storage for Gaussian splat scenes.
 * 
 * Directory structure:
 * - app_data/scenes/{sceneId}.splat - Binary Gaussian data
 * - app_data/scenes/thumbnails/{sceneId}.jpg - Thumbnail images
 * 
 * Responsibilities:
 * - Save/load .splat binary files
 * - Generate and cache thumbnails
 * - Manage storage directory structure
 * - Calculate storage usage
 * - Clean up files on deletion
 */
@Singleton
class SplatStorageManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    
    private val scenesDir: File by lazy {
        File(context.filesDir, "scenes").apply { mkdirs() }
    }
    
    private val thumbnailsDir: File by lazy {
        File(scenesDir, "thumbnails").apply { mkdirs() }
    }
    
    /**
     * Saves Gaussian splat data to a binary .splat file.
     * Returns the file path on success.
     */
    suspend fun saveSplatFile(
        sceneId: String,
        gaussians: List<GaussianSplat>
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val file = File(scenesDir, "$sceneId.splat")
            
            file.outputStream().use { output ->
                // Write header (128 bytes)
                val header = ByteArray(128)
                // Magic number: "SPLAT" (5 bytes)
                header[0] = 'S'.code.toByte()
                header[1] = 'P'.code.toByte()
                header[2] = 'L'.code.toByte()
                header[3] = 'A'.code.toByte()
                header[4] = 'T'.code.toByte()
                
                // Version: 1 (1 byte)
                header[5] = 1
                
                // Gaussian count (4 bytes, little-endian)
                val count = gaussians.size
                header[6] = (count and 0xFF).toByte()
                header[7] = ((count shr 8) and 0xFF).toByte()
                header[8] = ((count shr 16) and 0xFF).toByte()
                header[9] = ((count shr 24) and 0xFF).toByte()
                
                // SH degree (1 byte) - always 0 for mobile
                header[10] = 0
                
                output.write(header)
                
                // Write Gaussians (56 bytes each)
                gaussians.forEach { gaussian ->
                    output.write(gaussian.toByteArray())
                }
            }
            
            Timber.d("Saved ${gaussians.size} Gaussians to ${file.absolutePath}")
            Result.success(file.absolutePath)
            
        } catch (e: Exception) {
            Timber.e(e, "Failed to save splat file for scene $sceneId")
            Result.failure(e)
        }
    }
    
    /**
     * Loads Gaussian splat data from a .splat file.
     */
    suspend fun loadSplatFile(filePath: String): Result<List<GaussianSplat>> = 
        withContext(Dispatchers.IO) {
        try {
            val file = File(filePath)
            if (!file.exists()) {
                return@withContext Result.failure(
                    IllegalArgumentException("File does not exist: $filePath")
                )
            }
            
            val gaussians = mutableListOf<GaussianSplat>()
            
            file.inputStream().use { input ->
                // Read header (128 bytes)
                val header = ByteArray(128)
                input.read(header)
                
                // Verify magic number
                val magic = String(header.sliceArray(0..4))
                if (magic != "SPLAT") {
                    return@withContext Result.failure(
                        IllegalArgumentException("Invalid file format: $magic")
                    )
                }
                
                // Read Gaussian count
                val count = (header[6].toInt() and 0xFF) or
                           ((header[7].toInt() and 0xFF) shl 8) or
                           ((header[8].toInt() and 0xFF) shl 16) or
                           ((header[9].toInt() and 0xFF) shl 24)
                
                // Read Gaussians
                val gaussianBytes = ByteArray(56)
                repeat(count) {
                    input.read(gaussianBytes)
                    gaussians.add(GaussianSplat.fromByteArray(gaussianBytes))
                }
            }
            
            Timber.d("Loaded ${gaussians.size} Gaussians from $filePath")
            Result.success(gaussians)
            
        } catch (e: Exception) {
            Timber.e(e, "Failed to load splat file: $filePath")
            Result.failure(e)
        }
    }
    
    /**
     * Generates a thumbnail for a scene.
     * For MVP, creates a simple point cloud projection.
     * 
     * TODO: Replace with actual OpenGL rendering once viewer is implemented.
     */
    suspend fun generateThumbnail(
        sceneId: String,
        scene: SplatScene,
        size: Int = 256
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            
            // Black background
            canvas.drawColor(Color.BLACK)
            
            // Simple point projection (placeholder for actual rendering)
            val paint = Paint().apply {
                style = Paint.Style.FILL
                strokeWidth = 2f
            }
            
            val bbox = scene.boundingBox
            val centerX = (bbox.min[0] + bbox.max[0]) / 2f
            val centerY = (bbox.min[1] + bbox.max[1]) / 2f
            val centerZ = (bbox.min[2] + bbox.max[2]) / 2f
            
            val scaleX = bbox.max[0] - bbox.min[0]
            val scaleY = bbox.max[1] - bbox.min[1]
            val scaleZ = bbox.max[2] - bbox.min[2]
            val maxScale = maxOf(scaleX, scaleY, scaleZ)
            
            // Sample every Nth Gaussian for performance
            val sampleRate = maxOf(1, scene.gaussians.size / 1000)
            
            scene.gaussians.filterIndexed { index, _ -> index % sampleRate == 0 }.forEach { gaussian ->
                // Project to 2D (simple orthographic projection)
                val x = ((gaussian.position[0] - centerX) / maxScale * size * 0.8f + size / 2f).toInt()
                val y = ((gaussian.position[1] - centerY) / maxScale * size * 0.8f + size / 2f).toInt()
                
                // Use SH coefficient as color
                val r = (gaussian.shCoefficients[0] * 255).toInt().coerceIn(0, 255)
                val g = (gaussian.shCoefficients[1] * 255).toInt().coerceIn(0, 255)
                val b = (gaussian.shCoefficients[2] * 255).toInt().coerceIn(0, 255)
                
                paint.color = Color.rgb(r, g, b)
                canvas.drawCircle(x.toFloat(), y.toFloat(), 1.5f, paint)
            }
            
            // Save thumbnail
            val thumbnailFile = File(thumbnailsDir, "$sceneId.jpg")
            FileOutputStream(thumbnailFile).use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output)
            }
            
            bitmap.recycle()
            
            Timber.d("Generated thumbnail: ${thumbnailFile.absolutePath}")
            Result.success(thumbnailFile.absolutePath)
            
        } catch (e: Exception) {
            Timber.e(e, "Failed to generate thumbnail for scene $sceneId")
            Result.failure(e)
        }
    }
    
    /**
     * Gets the thumbnail file path for a scene, or null if it doesn't exist.
     */
    fun getThumbnailPath(sceneId: String): String? {
        val file = File(thumbnailsDir, "$sceneId.jpg")
        return if (file.exists()) file.absolutePath else null
    }
    
    /**
     * Deletes all files associated with a scene.
     */
    suspend fun deleteSceneFiles(sceneId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val splatFile = File(scenesDir, "$sceneId.splat")
            val thumbnailFile = File(thumbnailsDir, "$sceneId.jpg")
            
            var deleted = false
            if (splatFile.exists()) {
                deleted = splatFile.delete() || deleted
            }
            if (thumbnailFile.exists()) {
                deleted = thumbnailFile.delete() || deleted
            }
            
            Timber.d("Deleted files for scene $sceneId")
            Result.success(Unit)
            
        } catch (e: Exception) {
            Timber.e(e, "Failed to delete files for scene $sceneId")
            Result.failure(e)
        }
    }
    
    /**
     * Calculates total storage used by all scene files.
     */
    suspend fun getTotalStorageUsed(): Long = withContext(Dispatchers.IO) {
        (scenesDir.walkTopDown()
            .filter { it.isFile }
            .sumOf { it.length() })
    }
    
    /**
     * Generates a unique scene ID.
     */
    fun generateSceneId(): String = UUID.randomUUID().toString()
    
    /**
     * Gets the file path for a scene's .splat file.
     */
    fun getSplatFilePath(sceneId: String): String {
        return File(scenesDir, "$sceneId.splat").absolutePath
    }
}
