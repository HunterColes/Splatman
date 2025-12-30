package com.huntercoles.splatman.library.domain.usecase

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.huntercoles.splatman.library.data.loader.Model3DConverter
import com.huntercoles.splatman.library.data.loader.ModelLoader
import com.huntercoles.splatman.library.data.preferences.FolderPreferences
import com.huntercoles.splatman.library.domain.repository.SplatSceneRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

/**
 * Scan PLY folder and import all PLY files as SplatScenes
 * 
 * Stores folder URI and scans for .ply files when folder selected
 */
class PickPlyFolderUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val folderPreferences: FolderPreferences,
    private val repository: SplatSceneRepository
) {
    
    /**
     * Save selected folder URI and scan for PLY files
     * 
     * @param uri Content URI from SAF picker (content://...)
     * @return Result with count of loaded files
     */
    suspend operator fun invoke(uri: Uri): Result<Int> = withContext(Dispatchers.IO) {
        try {
            Timber.d("Scanning PLY folder: $uri")
            folderPreferences.setFolderUri(uri)
            
            val contentResolver = context.contentResolver
            var loadedCount = 0
            
            // Get document ID from folder URI
            val docId = DocumentsContract.getTreeDocumentId(uri)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(uri, docId)
            
            // Query for all files in folder
            contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                ),
                null,
                null,
                null
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                
                while (cursor.moveToNext()) {
                    val documentId = cursor.getString(idColumn)
                    val fileName = cursor.getString(nameColumn)
                    
                    // Only process PLY files
                    if (fileName.endsWith(".ply", ignoreCase = true)) {
                        try {
                            val fileUri = DocumentsContract.buildDocumentUriUsingTree(uri, documentId)
                            val inputStream = contentResolver.openInputStream(fileUri)
                            
                            if (inputStream != null) {
                                // Load PLY file
                                ModelLoader.loadModel(inputStream, fileName)
                                    .onSuccess { model3D ->
                                        // Convert to SplatScene
                                        val scene = Model3DConverter.toSplatScene(model3D)
                                        
                                        // Save to repository
                                        repository.saveScene(scene)
                                            .onSuccess {
                                                loadedCount++
                                                Timber.d("Loaded: $fileName ($loadedCount)")
                                            }
                                            .onFailure { error ->
                                                Timber.w(error, "Failed to save: $fileName")
                                            }
                                    }
                                    .onFailure { error ->
                                        Timber.w(error, "Failed to load: $fileName")
                                    }
                                
                                inputStream.close()
                            }
                        } catch (e: Exception) {
                            Timber.e(e, "Error processing: $fileName")
                        }
                    }
                }
            }
            
            Timber.d("Folder scan complete: $loadedCount files loaded")
            Result.success(loadedCount)
            
        } catch (e: Exception) {
            Timber.e(e, "Failed to scan folder")
            Result.failure(e)
        }
    }
    
    /**
     * Get currently selected folder (null if not set)
     */
    fun getCurrentFolder(): Uri? {
        return folderPreferences.getFolderUri()
    }
    
    /**
     * Clear folder selection
     */
    fun clearFolder() {
        folderPreferences.clearFolder()
    }
}
