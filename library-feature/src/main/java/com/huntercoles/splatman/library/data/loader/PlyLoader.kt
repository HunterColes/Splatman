package com.huntercoles.splatman.library.data.loader

import com.huntercoles.splatman.library.domain.model.Model3D
import timber.log.Timber
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

/**
 * PLY (Stanford Polygon File Format) loader
 * Supports ASCII and binary formats
 * Inspired by ModelViewer3D
 */
object PlyLoader {
    
    private const val BUFFER_SIZE = 65536
    private const val BYTES_PER_FLOAT = 4
    private const val COORDS_PER_VERTEX = 3
    
    fun load(inputStream: InputStream, fileName: String): Model3D.PointCloud {
        val stream = BufferedInputStream(inputStream, BUFFER_SIZE)
        stream.mark(1024)
        
        // Check if ASCII or binary
        val isAscii = ModelLoader.isTextFormat(stream, "format ascii", 256)
        stream.reset()
        
        return if (isAscii) {
            loadAscii(stream, fileName)
        } else {
            Timber.d("Using BinaryPlyLoader for robust binary PLY loading")
            BinaryPlyLoader.loadBinary(stream, fileName)
        }
    }
    
    private fun loadAscii(stream: InputStream, fileName: String): Model3D.PointCloud {
        val reader = BufferedReader(InputStreamReader(stream), BUFFER_SIZE)
        
        // Parse header
        var vertexCount = 0
        var faceCount = 0
        val vertexProps = mutableListOf<Pair<String, String>>() // (type, name)
        var inHeader = true
        var currentElement = ""
        
        while (inHeader) {
            val line = reader.readLine()?.trim() ?: break
            
            when {
                line.startsWith("element vertex") -> {
                    vertexCount = line.split("\\s+".toRegex())[2].toInt()
                    currentElement = "vertex"
                }
                line.startsWith("element face") -> {
                    faceCount = line.split("\\s+".toRegex())[2].toInt()
                    currentElement = "face"
                }
                line.startsWith("property") && currentElement == "vertex" -> {
                    val parts = line.split("\\s+".toRegex())
                    if (parts.size >= 3) {
                        vertexProps.add(Pair(parts[1], parts[2]))
                    }
                }
                line == "end_header" -> {
                    inHeader = false
                }
            }
        }
        
        Timber.d("PLY: $vertexCount vertices, $faceCount faces, ${vertexProps.size} properties")
        
        // Find indices for x, y, z, r, g, b, alpha
        var xIdx = -1
        var yIdx = -1
        var zIdx = -1
        var rIdx = -1
        var gIdx = -1
        var bIdx = -1
        var alphaIdx = -1
        
        vertexProps.forEachIndexed { index, (_, name) ->
            when (name) {
                "x" -> if (xIdx < 0) xIdx = index
                "y" -> if (yIdx < 0) yIdx = index
                "z" -> if (zIdx < 0) zIdx = index
                "red" -> if (rIdx < 0) rIdx = index
                "green" -> if (gIdx < 0) gIdx = index
                "blue" -> if (bIdx < 0) bIdx = index
                "alpha" -> if (alphaIdx < 0) alphaIdx = index
            }
        }
        
        if (xIdx < 0 || yIdx < 0 || zIdx < 0) {
            throw IllegalArgumentException("PLY file must have x, y, z coordinates")
        }
        
        val hasColors = rIdx >= 0 && gIdx >= 0 && bIdx >= 0
        
        // Read vertex data
        val vertices = mutableListOf<Float>()
        val colors = if (hasColors) mutableListOf<Float>() else null
        
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var minZ = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE
        var maxY = Float.MIN_VALUE
        var maxZ = Float.MIN_VALUE
        var centerX = 0.0
        var centerY = 0.0
        var centerZ = 0.0
        
        repeat(vertexCount) {
            val line = reader.readLine()?.trim() ?: return@repeat
            if (line.isEmpty()) return@repeat
            
            val parts = line.split("\\s+".toRegex())
            if (parts.size <= maxOf(xIdx, yIdx, zIdx)) return@repeat
            
            val x = parts[xIdx].toFloat()
            val y = parts[yIdx].toFloat()
            val z = parts[zIdx].toFloat()
            
            vertices.add(x)
            vertices.add(y)
            vertices.add(z)
            
            minX = min(minX, x)
            minY = min(minY, y)
            minZ = min(minZ, z)
            maxX = max(maxX, x)
            maxY = max(maxY, y)
            maxZ = max(maxZ, z)
            
            centerX += x
            centerY += y
            centerZ += z
            
            if (hasColors) {
                val r = parts[rIdx].toFloat() / 255f
                val g = parts[gIdx].toFloat() / 255f
                val b = parts[bIdx].toFloat() / 255f
                val a = if (alphaIdx >= 0) parts[alphaIdx].toFloat() / 255f else 1f
                
                colors?.add(r)
                colors?.add(g)
                colors?.add(b)
                colors?.add(a)
            }
        }
        
        centerX /= vertexCount
        centerY /= vertexCount
        centerZ /= vertexCount
        
        // Create buffers
        val vertexBuffer = ByteBuffer.allocateDirect(vertices.size * BYTES_PER_FLOAT)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        vertices.forEach { vertexBuffer.put(it) }
        vertexBuffer.position(0)
        
        val colorBuffer = colors?.let { colorList ->
            ByteBuffer.allocateDirect(colorList.size * BYTES_PER_FLOAT)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply {
                    colorList.forEach { put(it) }
                    position(0)
                }
        }
        
        return Model3D.PointCloud(
            name = fileName,
            vertexBuffer = vertexBuffer,
            normalBuffer = null, // PLY point clouds typically don't have normals
            colorBuffer = colorBuffer,
            vertexCount = vertexCount,
            centerX = centerX.toFloat(),
            centerY = centerY.toFloat(),
            centerZ = centerZ.toFloat(),
            minX = minX,
            minY = minY,
            minZ = minZ,
            maxX = maxX,
            maxY = maxY,
            maxZ = maxZ
        )
    }
    
    private fun loadBinary(stream: InputStream, fileName: String): Model3D.PointCloud {
        val reader = BufferedReader(InputStreamReader(stream), BUFFER_SIZE)
        
        // Parse header
        var vertexCount = 0
        var faceCount = 0
        val vertexProps = mutableListOf<Pair<String, String>>() // (type, name)
        var inHeader = true
        var currentElement = ""
        var isBigEndian = false
        
        while (inHeader) {
            val line = reader.readLine()?.trim() ?: break
            
            when {
                line.startsWith("format binary_big_endian") -> {
                    isBigEndian = true
                }
                line.startsWith("format binary_little_endian") -> {
                    isBigEndian = false
                }
                line.startsWith("element vertex") -> {
                    vertexCount = line.split("\\s+".toRegex())[2].toInt()
                    currentElement = "vertex"
                }
                line.startsWith("element face") -> {
                    faceCount = line.split("\\s+".toRegex())[2].toInt()
                    currentElement = "face"
                }
                line.startsWith("property") && currentElement == "vertex" -> {
                    val parts = line.split("\\s+".toRegex())
                    if (parts.size >= 3) {
                        vertexProps.add(Pair(parts[1], parts[2]))
                    }
                }
                line == "end_header" -> {
                    inHeader = false
                }
            }
        }
        
        Timber.d("Binary PLY: $vertexCount vertices, $faceCount faces, endian=${if (isBigEndian) "big" else "little"}")
        
        // Find property indices
        var xIdx = -1
        var yIdx = -1
        var zIdx = -1
        var rIdx = -1
        var gIdx = -1
        var bIdx = -1
        
        vertexProps.forEachIndexed { index, (_, name) ->
            when (name) {
                "x" -> if (xIdx < 0) xIdx = index
                "y" -> if (yIdx < 0) yIdx = index
                "z" -> if (zIdx < 0) zIdx = index
                "red" -> if (rIdx < 0) rIdx = index
                "green" -> if (gIdx < 0) gIdx = index
                "blue" -> if (bIdx < 0) bIdx = index
            }
        }
        
        if (xIdx < 0 || yIdx < 0 || zIdx < 0) {
            throw IllegalArgumentException("PLY file must have x, y, z coordinates")
        }
        
        val hasColors = rIdx >= 0 && gIdx >= 0 && bIdx >= 0
        
        // Read binary vertex data
        val vertices = mutableListOf<Float>()
        val colors = if (hasColors) mutableListOf<Float>() else null
        
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var minZ = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE
        var maxY = Float.MIN_VALUE
        var maxZ = Float.MIN_VALUE
        var centerX = 0.0
        var centerY = 0.0
        var centerZ = 0.0
        
        val binaryStream = stream
        val propertySize: Int = vertexProps.sumOf { (type, _) ->
            when (type) {
                "float" -> 4 as Int
                "uchar", "char", "int8", "uint8" -> 1 as Int
                "short", "ushort", "int16", "uint16" -> 2 as Int
                "int", "uint", "int32", "uint32" -> 4 as Int
                else -> 0 as Int
            }
        }
        
        val vertexBytes = ByteArray(propertySize)
        
        repeat(vertexCount) {
            val bytesRead = binaryStream.read(vertexBytes)
            if (bytesRead < propertySize) return@repeat
            
            val buffer = ByteBuffer.wrap(vertexBytes)
            if (isBigEndian) buffer.order(ByteOrder.BIG_ENDIAN)
            else buffer.order(ByteOrder.LITTLE_ENDIAN)
            
            var byteOffset = 0
            var propIndex = 0
            var x = 0f
            var y = 0f
            var z = 0f
            var r = 255
            var g = 255
            var b = 255
            
            vertexProps.forEach { (type, _) ->
                when (type) {
                    "float" -> {
                        val value = buffer.getFloat(byteOffset)
                        when (propIndex) {
                            xIdx -> x = value
                            yIdx -> y = value
                            zIdx -> z = value
                        }
                        byteOffset += 4
                    }
                    "uchar", "uint8" -> {
                        val value = buffer.get(byteOffset).toInt() and 0xFF
                        when (propIndex) {
                            rIdx -> r = value
                            gIdx -> g = value
                            bIdx -> b = value
                        }
                        byteOffset += 1
                    }
                    "char", "int8" -> {
                        val value = buffer.get(byteOffset).toInt()
                        byteOffset += 1
                    }
                    "short", "int16" -> {
                        buffer.getShort(byteOffset)
                        byteOffset += 2
                    }
                    "ushort", "uint16" -> {
                        buffer.getShort(byteOffset).toInt() and 0xFFFF
                        byteOffset += 2
                    }
                    "int", "int32", "uint", "uint32" -> {
                        buffer.getInt(byteOffset)
                        byteOffset += 4
                    }
                }
                propIndex++
            }
            
            vertices.add(x)
            vertices.add(y)
            vertices.add(z)
            
            minX = min(minX, x)
            minY = min(minY, y)
            minZ = min(minZ, z)
            maxX = max(maxX, x)
            maxY = max(maxY, y)
            maxZ = max(maxZ, z)
            
            centerX += x
            centerY += y
            centerZ += z
            
            if (hasColors) {
                colors?.add(r / 255f)
                colors?.add(g / 255f)
                colors?.add(b / 255f)
                colors?.add(1f)
            }
        }
        
        centerX /= vertexCount
        centerY /= vertexCount
        centerZ /= vertexCount
        
        // Create buffers
        val vertexBuffer = ByteBuffer.allocateDirect(vertices.size * BYTES_PER_FLOAT)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        vertices.forEach { vertexBuffer.put(it) }
        vertexBuffer.position(0)
        
        val colorBuffer = colors?.let { colorList ->
            ByteBuffer.allocateDirect(colorList.size * BYTES_PER_FLOAT)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply {
                    colorList.forEach { put(it) }
                    position(0)
                }
        }
        
        Timber.d("Loaded $vertexCount vertices from binary PLY")
        
        return Model3D.PointCloud(
            name = fileName,
            vertexBuffer = vertexBuffer,
            normalBuffer = null,
            colorBuffer = colorBuffer,
            vertexCount = vertexCount,
            centerX = centerX.toFloat(),
            centerY = centerY.toFloat(),
            centerZ = centerZ.toFloat(),
            minX = minX,
            minY = minY,
            minZ = minZ,
            maxX = maxX,
            maxY = maxY,
            maxZ = maxZ
        )
    }
}
