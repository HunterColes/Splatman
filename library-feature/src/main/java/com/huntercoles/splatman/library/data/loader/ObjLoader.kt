package com.huntercoles.splatman.library.data.loader

import com.huntercoles.splatman.library.domain.model.Model3D
import java.io.InputStream

/**
 * OBJ (Wavefront) loader
 * Supports basic OBJ format (vertices, faces, normals)
 * 
 * TODO: Implement based on ModelViewer3D reference
 */
object ObjLoader {
    fun load(inputStream: InputStream, fileName: String): Model3D.TriangleMesh {
        throw UnsupportedOperationException("OBJ loading not yet implemented")
    }
}
