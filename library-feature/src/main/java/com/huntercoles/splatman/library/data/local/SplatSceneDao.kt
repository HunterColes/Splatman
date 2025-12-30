package com.huntercoles.splatman.library.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO for Splat scene metadata
 * 
 * Provides database operations for managing scene metadata.
 * Actual Gaussian data is stored in binary files (.splat format).
 */
@Dao
interface SplatSceneDao {
    
    /**
     * Get all scenes, ordered by creation date (newest first)
     */
    @Query("SELECT * FROM splat_scenes ORDER BY createdAt DESC")
    fun getAllScenes(): Flow<List<SplatSceneEntity>>
    
    /**
     * Get a specific scene by ID
     */
    @Query("SELECT * FROM splat_scenes WHERE id = :sceneId")
    suspend fun getSceneById(sceneId: String): SplatSceneEntity?
    
    /**
     * Search scenes by name
     */
    @Query("SELECT * FROM splat_scenes WHERE name LIKE '%' || :query || '%' ORDER BY createdAt DESC")
    fun searchScenes(query: String): Flow<List<SplatSceneEntity>>
    
    /**
     * Get scenes sorted by size
     */
    @Query("SELECT * FROM splat_scenes ORDER BY fileSizeBytes DESC")
    fun getScenesBySize(): Flow<List<SplatSceneEntity>>
    
    /**
     * Get scenes sorted by Gaussian count
     */
    @Query("SELECT * FROM splat_scenes ORDER BY gaussianCount DESC")
    fun getScenesByGaussianCount(): Flow<List<SplatSceneEntity>>
    
    /**
     * Get total number of scenes
     */
    @Query("SELECT COUNT(*) FROM splat_scenes")
    suspend fun getSceneCount(): Int
    
    /**
     * Get total storage used in bytes
     */
    @Query("SELECT SUM(fileSizeBytes) FROM splat_scenes")
    suspend fun getTotalStorageUsed(): Long?
    
    /**
     * Insert a new scene
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertScene(scene: SplatSceneEntity)
    
    /**
     * Insert multiple scenes
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertScenes(scenes: List<SplatSceneEntity>)
    
    /**
     * Update an existing scene
     */
    @Update
    suspend fun updateScene(scene: SplatSceneEntity)
    
    /**
     * Delete a scene by ID
     */
    @Query("DELETE FROM splat_scenes WHERE id = :sceneId")
    suspend fun deleteScene(sceneId: String)
    
    /**
     * Delete multiple scenes
     */
    @Query("DELETE FROM splat_scenes WHERE id IN (:sceneIds)")
    suspend fun deleteScenes(sceneIds: List<String>)
    
    /**
     * Delete all scenes
     */
    @Query("DELETE FROM splat_scenes")
    suspend fun deleteAllScenes()
    
    /**
     * Update scene name
     */
    @Query("UPDATE splat_scenes SET name = :newName, modifiedAt = :timestamp WHERE id = :sceneId")
    suspend fun renameScene(sceneId: String, newName: String, timestamp: Long)
    
    /**
     * Update thumbnail path
     */
    @Query("UPDATE splat_scenes SET thumbnailPath = :path WHERE id = :sceneId")
    suspend fun updateThumbnail(sceneId: String, path: String)
    
    /**
     * Check if scene exists
     */
    @Query("SELECT EXISTS(SELECT 1 FROM splat_scenes WHERE id = :sceneId)")
    suspend fun sceneExists(sceneId: String): Boolean
}
