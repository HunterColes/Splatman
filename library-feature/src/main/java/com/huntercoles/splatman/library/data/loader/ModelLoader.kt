package com.huntercoles.splatman.library.data.loader

import com.huntercoles.splatman.library.domain.model.Model3D
import timber.log.Timber
import java.io.InputStream

/**
 * Unified 3D model loader for PLY, STL, OBJ files
 * Inspired by ModelViewer3D implementation
 */
object ModelLoader {
    
    /**
     * Load model from input stream based on file extension
     */
    fun loadModel(inputStream: InputStream, fileName: String): Result<Model3D> {
        return try {
            val model = when {
                fileName.endsWith(".ply", ignoreCase = true) -> {
                    PlyLoader.load(inputStream, fileName)
                }
                fileName.endsWith(".stl", ignoreCase = true) -> {
                    StlLoader.load(inputStream, fileName)
                }
                fileName.endsWith(".obj", ignoreCase = true) -> {
                    ObjLoader.load(inputStream, fileName)
                }
                else -> {
                    throw IllegalArgumentException("Unsupported file format: $fileName")
                }
            }
            
            Timber.d("Loaded model: $fileName (${model.vertexCount} vertices)")
            Result.success(model)
            
        } catch (e: Exception) {
            Timber.e(e, "Failed to load model: $fileName")
            Result.failure(e)
        }
    }
    
    /**
     * Detect if input stream contains ASCII or binary data
     */
    fun isTextFormat(stream: InputStream, marker: String, testSize: Int = 256): Boolean {
        stream.mark(testSize)
        val testBytes = ByteArray(testSize)
        val bytesRead = stream.read(testBytes, 0, testBytes.size)
        stream.reset()
        
        if (bytesRead <= 0) return false
        
        val string = String(testBytes, 0, bytesRead)
        return string.contains(marker, ignoreCase = true)
    }
}
