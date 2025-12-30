package com.huntercoles.splatman.library.data.assets

import android.content.Context
import com.huntercoles.splatman.library.data.loader.ModelLoader
import com.huntercoles.splatman.library.domain.model.Model3D
import timber.log.Timber
import java.io.IOException

/**
 * Manager for loading bundled 3D models from app assets
 * 
 * Models should be placed in: app/src/main/assets/models/
 * Supported formats: .ply, .stl, .obj
 */
class AssetsModelManager(private val context: Context) {
    
    private val modelsPath = "models"
    
    /**
     * List all available model files in assets
     */
    fun listAvailableModels(): List<String> {
        return try {
            val files = context.assets.list(modelsPath) ?: emptyArray()
            files.filter { fileName ->
                fileName.endsWith(".ply", ignoreCase = true) ||
                fileName.endsWith(".stl", ignoreCase = true) ||
                fileName.endsWith(".obj", ignoreCase = true)
            }.sorted()
        } catch (e: IOException) {
            Timber.e(e, "Failed to list assets models")
            emptyList()
        }
    }
    
    /**
     * Load a model from assets by filename
     */
    fun loadModel(fileName: String): Result<Model3D> {
        return try {
            val inputStream = context.assets.open("$modelsPath/$fileName")
            ModelLoader.loadModel(inputStream, fileName)
        } catch (e: Exception) {
            Timber.e(e, "Failed to load asset model: $fileName")
            Result.failure(e)
        }
    }
    
    /**
     * Load all available models from assets
     * Useful for populating the internal models grid
     */
    fun loadAllModels(): List<Model3D> {
        val models = mutableListOf<Model3D>()
        
        listAvailableModels().forEach { fileName ->
            loadModel(fileName).onSuccess { model ->
                models.add(model)
                Timber.d("Loaded asset model: $fileName")
            }.onFailure { error ->
                Timber.w(error, "Skipping failed model: $fileName")
            }
        }
        
        return models
    }
}
