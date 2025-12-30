package com.huntercoles.splatman.library.data.repository

import com.huntercoles.splatman.library.data.format.PlyFormatHandler
import com.huntercoles.splatman.library.data.format.SplatFormatHandler
import com.huntercoles.splatman.library.data.local.SplatSceneDao
import com.huntercoles.splatman.library.data.local.SplatSceneEntity
import com.huntercoles.splatman.library.data.storage.SplatStorageManager
import com.huntercoles.splatman.library.domain.model.SplatScene
import com.huntercoles.splatman.library.domain.repository.ExportFormat
import com.huntercoles.splatman.library.domain.repository.SplatSceneRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import timber.log.Timber
import java.io.File
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of SplatSceneRepository.
 * 
 * Coordinates between:
 * - Room database (metadata)
 * - File system (binary .splat files)
 * - Format handlers (import/export)
 * - Storage manager (file operations)
 */
@Singleton
class SplatSceneRepositoryImpl @Inject constructor(
    private val dao: SplatSceneDao,
    private val storageManager: SplatStorageManager,
    private val plyHandler: PlyFormatHandler,
    private val splatHandler: SplatFormatHandler
) : SplatSceneRepository {
    
    override fun getAllScenes(): Flow<List<SplatScene>> {
        return dao.getAllScenes().map { entities ->
            entities.mapNotNull { entity ->
                loadSceneFromEntity(entity)
            }
        }
    }
    
    override suspend fun getSceneById(id: String): SplatScene? {
        return try {
            val entity = dao.getSceneById(id) ?: return null
            loadSceneFromEntity(entity)
        } catch (e: Exception) {
            Timber.e(e, "Failed to get scene by ID: $id")
            null
        }
    }
    
    override suspend fun saveScene(scene: SplatScene): Result<Unit> {
        return try {
            // Generate ID if new scene
            val sceneId = scene.id.ifEmpty { storageManager.generateSceneId() }
            val sceneWithId = if (scene.id.isEmpty()) {
                scene.copy(
                    id = sceneId,
                    modifiedAt = Date()
                )
            } else {
                scene.copy(modifiedAt = Date())
            }
            
            // Save Gaussian data to file
            val splatResult = storageManager.saveSplatFile(sceneId, sceneWithId.gaussians)
            if (splatResult.isFailure) {
                return Result.failure(splatResult.exceptionOrNull()!!)
            }
            
            // Generate thumbnail
            val thumbnailResult = storageManager.generateThumbnail(sceneId, sceneWithId)
            val thumbnailPath = thumbnailResult.getOrNull()
            
            // Save metadata to database
            val entity = SplatSceneEntity.fromDomain(
                scene = sceneWithId,
                filePath = splatResult.getOrNull()!!,
                thumbnailPath = thumbnailPath
            )
            dao.insertScene(entity)
            
            Timber.d("Saved scene: ${sceneWithId.name} (${sceneWithId.gaussians.size} Gaussians)")
            Result.success(Unit)
            
        } catch (e: Exception) {
            Timber.e(e, "Failed to save scene")
            Result.failure(e)
        }
    }
    
    override suspend fun deleteScene(id: String): Result<Unit> {
        return try {
            // Delete files
            val deleteResult = storageManager.deleteSceneFiles(id)
            if (deleteResult.isFailure) {
                Timber.w("Failed to delete files for scene $id, continuing with DB deletion")
            }
            
            // Delete from database
            dao.deleteScene(id)
            
            Timber.d("Deleted scene: $id")
            Result.success(Unit)
            
        } catch (e: Exception) {
            Timber.e(e, "Failed to delete scene: $id")
            Result.failure(e)
        }
    }
    
    override suspend fun deleteScenes(ids: List<String>): Result<Unit> {
        return try {
            ids.forEach { id ->
                storageManager.deleteSceneFiles(id)
            }
            dao.deleteScenes(ids)
            
            Timber.d("Deleted ${ids.size} scenes")
            Result.success(Unit)
            
        } catch (e: Exception) {
            Timber.e(e, "Failed to delete scenes")
            Result.failure(e)
        }
    }
    
    override suspend fun renameScene(id: String, newName: String): Result<Unit> {
        return try {
            dao.renameScene(id, newName, Date().time)
            Timber.d("Renamed scene $id to: $newName")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to rename scene: $id")
            Result.failure(e)
        }
    }
    
    override suspend fun importFile(file: File): Result<SplatScene> {
        return try {
            if (!file.exists()) {
                return Result.failure(IllegalArgumentException("File not found: ${file.absolutePath}"))
            }
            
            // Detect format by extension
            val scene = when (file.extension.lowercase()) {
                "ply" -> {
                    val result = plyHandler.import(file)
                    if (result.isFailure) {
                        return Result.failure(result.exceptionOrNull()!!)
                    }
                    result.getOrNull()!!
                }
                "splat" -> {
                    val result = splatHandler.import(file)
                    if (result.isFailure) {
                        return Result.failure(result.exceptionOrNull()!!)
                    }
                    result.getOrNull()!!
                }
                else -> {
                    return Result.failure(
                        IllegalArgumentException("Unsupported file format: ${file.extension}")
                    )
                }
            }
            
            // Use filename as scene name
            val sceneName = file.nameWithoutExtension
            val sceneWithName = scene.copy(name = sceneName)
            
            // Save to repository
            val saveResult = saveScene(sceneWithName)
            if (saveResult.isFailure) {
                return Result.failure(saveResult.exceptionOrNull()!!)
            }
            
            Timber.d("Imported scene from ${file.absolutePath}: $sceneName (${scene.gaussians.size} Gaussians)")
            Result.success(sceneWithName)
            
        } catch (e: Exception) {
            Timber.e(e, "Failed to import file: ${file.absolutePath}")
            Result.failure(e)
        }
    }
    
    override suspend fun exportScene(
        sceneId: String,
        format: ExportFormat,
        outputFile: File
    ): Result<Unit> {
        return try {
            // Load scene
            val scene = getSceneById(sceneId)
                ?: return Result.failure(IllegalArgumentException("Scene not found: $sceneId"))
            
            // Export using appropriate handler
            val exportResult = when (format) {
                ExportFormat.PLY -> plyHandler.export(scene, outputFile)
                ExportFormat.SPLAT -> splatHandler.export(scene, outputFile)
            }
            
            if (exportResult.isFailure) {
                return Result.failure(exportResult.exceptionOrNull()!!)
            }
            
            Timber.d("Exported scene $sceneId to ${outputFile.absolutePath} as ${format.name}")
            Result.success(Unit)
            
        } catch (e: Exception) {
            Timber.e(e, "Failed to export scene: $sceneId")
            Result.failure(e)
        }
    }
    
    override fun searchScenes(query: String): Flow<List<SplatScene>> {
        return dao.searchScenes(query).map { entities ->
            entities.mapNotNull { entity ->
                loadSceneFromEntity(entity)
            }
        }
    }
    
    override suspend fun getTotalStorageUsed(): Long {
        return storageManager.getTotalStorageUsed()
    }
    
    override suspend fun getSceneCount(): Int {
        return dao.getSceneCount()
    }
    
    override suspend fun generateThumbnail(sceneId: String): Result<String> {
        return try {
            val scene = getSceneById(sceneId)
                ?: return Result.failure(IllegalArgumentException("Scene not found: $sceneId"))
            
            val thumbnailResult = storageManager.generateThumbnail(sceneId, scene)
            if (thumbnailResult.isFailure) {
                return Result.failure(thumbnailResult.exceptionOrNull()!!)
            }
            
            val thumbnailPath = thumbnailResult.getOrNull()!!
            dao.updateThumbnail(sceneId, thumbnailPath)
            
            Timber.d("Updated thumbnail for scene: $sceneId")
            Result.success(thumbnailPath)
            
        } catch (e: Exception) {
            Timber.e(e, "Failed to update thumbnail: $sceneId")
            Result.failure(e)
        }
    }
    
    override suspend fun sceneExists(id: String): Boolean {
        return dao.sceneExists(id)
    }
    
    /**
     * Helper function to load a complete SplatScene from a database entity.
     * Loads Gaussian data from the .splat file.
     */
    private suspend fun loadSceneFromEntity(entity: SplatSceneEntity): SplatScene? {
        val gaussiansResult = storageManager.loadSplatFile(entity.filePath)
        if (gaussiansResult.isFailure) {
            Timber.e(gaussiansResult.exceptionOrNull(), "Failed to load Gaussians for scene: ${entity.id}")
            return null
        }
        
        return entity.toDomain(gaussiansResult.getOrNull()!!)
    }
}
