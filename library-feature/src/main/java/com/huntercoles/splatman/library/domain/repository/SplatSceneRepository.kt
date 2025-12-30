package com.huntercoles.splatman.library.domain.repository

import com.huntercoles.splatman.library.domain.model.SplatScene
import kotlinx.coroutines.flow.Flow
import java.io.File

/**
 * Repository interface for Gaussian Splat scene management
 * 
 * Abstracts data layer operations for:
 * - Local storage (database + binary files)
 * - Import/export operations
 * - Scene CRUD operations
 * 
 * Implementation will coordinate between:
 * - Room database (metadata)
 * - File system (.splat binary files)
 * - Format handlers (PLY, SPLAT)
 */
interface SplatSceneRepository {
    
    /**
     * Get all scenes as a Flow for reactive UI updates
     * Sorted by creation date (newest first)
     */
    fun getAllScenes(): Flow<List<SplatScene>>
    
    /**
     * Get a specific scene by ID
     * Loads full Gaussian data from .splat file
     * 
     * @param id Scene unique identifier
     * @return Scene with all Gaussian data, or null if not found
     */
    suspend fun getSceneById(id: String): SplatScene?
    
    /**
     * Save a new or updated scene
     * - Saves metadata to database
     * - Writes Gaussians to .splat file
     * - Generates thumbnail
     * 
     * @param scene Complete scene data
     * @return Success/failure result
     */
    suspend fun saveScene(scene: SplatScene): Result<Unit>
    
    /**
     * Delete a scene
     * - Removes database entry
     * - Deletes .splat file
     * - Deletes thumbnail
     * 
     * @param id Scene ID to delete
     * @return Success/failure result
     */
    suspend fun deleteScene(id: String): Result<Unit>
    
    /**
     * Delete multiple scenes in batch
     * More efficient than individual deletes
     */
    suspend fun deleteScenes(ids: List<String>): Result<Unit>
    
    /**
     * Rename a scene
     * Updates database metadata only
     */
    suspend fun renameScene(id: String, newName: String): Result<Unit>
    
    /**
     * Search scenes by name
     * Returns Flow for reactive filtering
     */
    fun searchScenes(query: String): Flow<List<SplatScene>>
    
    /**
     * Import a file into the library
     * Supports .ply and .splat formats
     * 
     * @param file Input file (.ply or .splat)
     * @return Imported scene with new ID, or error
     */
    suspend fun importFile(file: File): Result<SplatScene>
    
    /**
     * Export a scene to a file
     * 
     * @param sceneId Scene to export
     * @param format Export format (PLY or SPLAT)
     * @param outputFile Destination file
     * @return Success/failure result
     */
    suspend fun exportScene(
        sceneId: String,
        format: ExportFormat,
        outputFile: File
    ): Result<Unit>
    
    /**
     * Get total storage used by all scenes
     * Sum of all .splat file sizes + thumbnails
     */
    suspend fun getTotalStorageUsed(): Long
    
    /**
     * Get number of scenes in library
     */
    suspend fun getSceneCount(): Int
    
    /**
     * Generate or regenerate thumbnail for a scene
     * Called after optimization or manual refresh
     * 
     * @param sceneId Scene ID
     * @return Path to thumbnail file
     */
    suspend fun generateThumbnail(sceneId: String): Result<String>
    
    /**
     * Check if a scene exists
     */
    suspend fun sceneExists(id: String): Boolean
}

/**
 * Export format options
 */
enum class ExportFormat(val extension: String, val mimeType: String) {
    PLY("ply", "application/octet-stream"),
    SPLAT("splat", "application/octet-stream");
    
    companion object {
        fun fromExtension(ext: String): ExportFormat? {
            return values().firstOrNull { it.extension.equals(ext, ignoreCase = true) }
        }
    }
}
