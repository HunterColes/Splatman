package com.huntercoles.splatman.library.data.loader

import com.huntercoles.splatman.library.domain.model.Model3D
import timber.log.Timber
import java.io.BufferedInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

/**
 * Robust binary PLY loader based on ModelViewer3D
 * 
 * CRITICAL FIX: Do NOT use BufferedReader for binary files!
 * BufferedReader consumes extra bytes which breaks binary offset calculations
 */
object BinaryPlyLoader {
    
    private const val BYTES_PER_FLOAT = 4
    
    fun loadBinary(stream: InputStream, fileName: String): Model3D.PointCloud {
        val buffered = BufferedInputStream(stream, 65536)
        
        // Read header as ASCII text until "end_header"
        val headerBytes = readHeaderBytes(buffered)
        val header = String(headerBytes)
        
        // Parse header
        val lines = header.split("\n")
        var vertexCount = 0
        var isBigEndian = false
        val vertexProps = mutableListOf<Pair<String, String>>()
        
        for (line in lines) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("format binary_big_endian") -> {
                    isBigEndian = true
                    Timber.d("Binary PLY: big endian")
                }
                trimmed.startsWith("format binary_little_endian") -> {
                    isBigEndian = false
                    Timber.d("Binary PLY: little endian")
                }
                trimmed.startsWith("element vertex") -> {
                    vertexCount = trimmed.split("\\s+".toRegex())[2].toInt()
                }
                trimmed.startsWith("property") -> {
                    val parts = trimmed.split("\\s+".toRegex())
                    if (parts.size >= 3) {
                        vertexProps.add(Pair(parts[1], parts[2]))
                    }
                }
            }
        }
        
        Timber.d("Binary PLY: $vertexCount vertices, ${vertexProps.size} properties")
        
        // Find property byte offsets
        var xIdx = -1
        var yIdx = -1
        var zIdx = -1
        var rIdx = -1
        var gIdx = -1
        var bIdx = -1
        
        vertexProps.forEachIndexed { index, (_, name) ->
            when (name) {
                "x" -> xIdx = index
                "y" -> yIdx = index
                "z" -> zIdx = index
                "red" -> rIdx = index
                "green" -> gIdx = index
                "blue" -> bIdx = index
            }
        }
        
        if (xIdx < 0 || yIdx < 0 || zIdx < 0) {
            throw IllegalArgumentException("PLY must have x,y,z properties")
        }
        
        // Calculate byte size per vertex
        val vertexByteSize = vertexProps.sumOf { (type, _) ->
            when (type) {
                "float", "float32" -> 4
                "double", "float64" -> 8
                "uchar", "char", "int8", "uint8" -> 1
                "short", "ushort", "int16", "uint16" -> 2
                "int", "uint", "int32", "uint32" -> 4
                else -> 0
            }.toLong()
        }.toInt()
        
        Timber.d("Vertex byte size: $vertexByteSize")
        
        // Read all vertex data
        val vertices = mutableListOf<Float>()
        val colors = mutableListOf<Float>()
        
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var maxZ = -Float.MAX_VALUE
        var centerX = 0.0
        var centerY = 0.0
        var centerZ = 0.0
        
        val vertexBuffer = ByteArray(vertexByteSize)
        
        repeat(vertexCount) {
            val bytesRead = buffered.read(vertexBuffer)
            if (bytesRead < vertexByteSize) {
                Timber.w("Incomplete vertex data at vertex $it")
                return@repeat
            }
            
            val bb = ByteBuffer.wrap(vertexBuffer)
            bb.order(if (isBigEndian) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN)
            
            // Read vertex position
            var byteOffset = 0
            var x = 0f
            var y = 0f
            var z = 0f
            var r = 255
            var g = 255
            var b = 255
            
            vertexProps.forEachIndexed { propIdx, (type, _) ->
                when (type) {
                    "float", "float32" -> {
                        val value = bb.getFloat(byteOffset)
                        when (propIdx) {
                            xIdx -> x = value
                            yIdx -> y = value
                            zIdx -> z = value
                        }
                        byteOffset += 4
                    }
                    "double", "float64" -> {
                        val value = bb.getDouble(byteOffset).toFloat()
                        when (propIdx) {
                            xIdx -> x = value
                            yIdx -> y = value
                            zIdx -> z = value
                        }
                        byteOffset += 8
                    }
                    "uchar", "uint8" -> {
                        val value = bb.get(byteOffset).toInt() and 0xFF
                        when (propIdx) {
                            rIdx -> r = value
                            gIdx -> g = value
                            bIdx -> b = value
                        }
                        byteOffset += 1
                    }
                    "char", "int8" -> {
                        byteOffset += 1
                    }
                    "short", "int16" -> {
                        byteOffset += 2
                    }
                    "ushort", "uint16" -> {
                        byteOffset += 2
                    }
                    "int", "int32", "uint", "uint32" -> {
                        byteOffset += 4
                    }
                }
            }
            
            // Validate coordinates
            if (x.isNaN() || y.isNaN() || z.isNaN() || 
                x.isInfinite() || y.isInfinite() || z.isInfinite()) {
                Timber.e("Invalid vertex at $it: ($x, $y, $z)")
                return@repeat
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
            
            // Add colors (normalized to 0-1)
            colors.add(r / 255f)
            colors.add(g / 255f)
            colors.add(b / 255f)
            colors.add(1f)
        }
        
        if (vertices.isEmpty()) {
            throw IllegalArgumentException("No valid vertices read from PLY")
        }
        
        val actualVertexCount = vertices.size / 3
        centerX /= actualVertexCount
        centerY /= actualVertexCount
        centerZ /= actualVertexCount
        
        Timber.d("Loaded $actualVertexCount vertices (bounds: $minX to $maxX, $minY to $maxY, $minZ to $maxZ)")
        
        // Create buffers
        val vertexFloatBuffer = ByteBuffer.allocateDirect(vertices.size * BYTES_PER_FLOAT)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        vertices.forEach { vertexFloatBuffer.put(it) }
        vertexFloatBuffer.position(0)
        
        val colorFloatBuffer = ByteBuffer.allocateDirect(colors.size * BYTES_PER_FLOAT)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        colors.forEach { colorFloatBuffer.put(it) }
        colorFloatBuffer.position(0)
        
        return Model3D.PointCloud(
            name = fileName,
            vertexBuffer = vertexFloatBuffer,
            normalBuffer = null,
            colorBuffer = colorFloatBuffer,
            vertexCount = actualVertexCount,
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
    
    /**
     * Read header bytes until "end_header\n"
     * CRITICAL: This ensures we don't over-read into binary data
     */
    private fun readHeaderBytes(stream: BufferedInputStream): ByteArray {
        val headerBytes = mutableListOf<Byte>()
        val endMarker = "end_header\n".toByteArray()
        var matchIndex = 0
        
        while (true) {
            val byte = stream.read()
            if (byte == -1) break
            
            headerBytes.add(byte.toByte())
            
            // Check for end_header marker
            if (byte.toByte() == endMarker[matchIndex]) {
                matchIndex++
                if (matchIndex == endMarker.size) {
                    break
                }
            } else {
                matchIndex = 0
            }
        }
        
        return headerBytes.toByteArray()
    }
}
