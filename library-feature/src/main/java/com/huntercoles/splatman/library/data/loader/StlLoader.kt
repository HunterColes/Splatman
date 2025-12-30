package com.huntercoles.splatman.library.data.loader

import com.huntercoles.splatman.library.domain.model.Model3D
import java.io.InputStream

/**
 * STL (Stereolithography) loader
 * Supports ASCII and binary formats
 * 
 * TODO: Implement based on ModelViewer3D reference
 */
object StlLoader {
    fun load(inputStream: InputStream, fileName: String): Model3D.TriangleMesh {
        throw UnsupportedOperationException("STL loading not yet implemented")
    }
}
