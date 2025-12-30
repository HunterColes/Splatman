package com.huntercoles.splatman.library.data.ply

import com.huntercoles.splatman.library.domain.model.BoundingBox
import com.huntercoles.splatman.library.domain.model.GaussianSplat
import com.huntercoles.splatman.library.domain.model.SplatScene
import timber.log.Timber
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*

/**
 * PLY file parser for Gaussian splat data
 * 
 * Supports:
 * - ASCII format (.ply text files)
 * - Binary little-endian format
 * - Position (x, y, z)
 * - Color (red, green, blue) or SH coefficients
 * - Opacity (alpha)
 * 
 * PLY Format:
 * ```
 * ply
 * format ascii 1.0
 * element vertex 25000
 * property float x
 * property float y
 * property float z
 * property uchar red
 * property uchar green
 * property uchar blue
 * property float opacity
 * end_header
 * -1.23 4.56 7.89 255 128 64 0.8
 * ...
 * ```
 */
class PlyParser {
    
    /**
     * Parse PLY file from InputStream
     * 
     * @param inputStream PLY file stream
     * @param filename Original filename (for scene name)
     * @return SplatScene with parsed Gaussians
     */
    fun parse(inputStream: InputStream, filename: String): Result<SplatScene> {
        return runCatching {
            val reader = BufferedReader(InputStreamReader(inputStream))
            
            // Parse header
            val header = parseHeader(reader)
            
            // Parse vertex data based on format
            val gaussians = when (header.format) {
                PlyFormat.ASCII -> parseAsciiVertices(reader, header)
                PlyFormat.BINARY_LITTLE_ENDIAN -> parseBinaryVertices(inputStream, header)
                PlyFormat.BINARY_BIG_ENDIAN -> throw UnsupportedOperationException("Big-endian PLY not supported")
            }
            
            Timber.d("Parsed $filename: ${gaussians.size} Gaussians")
            
            // Create scene
            createScene(filename, gaussians)
        }
    }
    
    /**
     * Parse PLY header to extract format and properties
     */
    private fun parseHeader(reader: BufferedReader): PlyHeader {
        var format = PlyFormat.ASCII
        var vertexCount = 0
        val properties = mutableListOf<PropertyInfo>()
        var inVertex = false
        
        while (true) {
            val line = reader.readLine()?.trim() ?: break
            
            when {
                line.startsWith("format") -> {
                    format = when {
                        line.contains("ascii") -> PlyFormat.ASCII
                        line.contains("binary_little_endian") -> PlyFormat.BINARY_LITTLE_ENDIAN
                        line.contains("binary_big_endian") -> PlyFormat.BINARY_BIG_ENDIAN
                        else -> throw IllegalArgumentException("Unknown PLY format: $line")
                    }
                }
                line.startsWith("element vertex") -> {
                    vertexCount = line.split(" ").last().toInt()
                    inVertex = true
                }
                line.startsWith("element") && inVertex -> {
                    inVertex = false // End of vertex properties
                }
                line.startsWith("property") && inVertex -> {
                    val parts = line.split(" ")
                    val type = parts[1]
                    val name = parts[2]
                    properties.add(PropertyInfo(name, type))
                }
                line == "end_header" -> {
                    break
                }
            }
        }
        
        return PlyHeader(format, vertexCount, properties)
    }
    
    /**
     * Parse ASCII format vertices
     */
    private fun parseAsciiVertices(reader: BufferedReader, header: PlyHeader): List<GaussianSplat> {
        val gaussians = mutableListOf<GaussianSplat>()
        
        repeat(header.vertexCount) {
            val line = reader.readLine()?.trim() ?: return@repeat
            val values = line.split(Regex("\\s+")).map { it.trim() }
            
            val gaussian = parseVertexValues(values, header.properties)
            gaussian?.let { gaussians.add(it) }
        }
        
        return gaussians
    }
    
    /**
     * Parse binary little-endian format vertices
     */
    private fun parseBinaryVertices(inputStream: InputStream, header: PlyHeader): List<GaussianSplat> {
        val gaussians = mutableListOf<GaussianSplat>()
        val bytesPerVertex = calculateBytesPerVertex(header.properties)
        val buffer = ByteBuffer.allocate(bytesPerVertex).order(ByteOrder.LITTLE_ENDIAN)
        
        repeat(header.vertexCount) {
            buffer.clear()
            val read = inputStream.read(buffer.array())
            if (read < bytesPerVertex) return@repeat
            
            val gaussian = parseBinaryVertex(buffer, header.properties)
            gaussian?.let { gaussians.add(it) }
        }
        
        return gaussians
    }
    
    /**
     * Parse single vertex from ASCII values
     */
    private fun parseVertexValues(values: List<String>, properties: List<PropertyInfo>): GaussianSplat? {
        if (values.size < properties.size) return null
        
        var x = 0f; var y = 0f; var z = 0f
        var r = 0f; var g = 0f; var b = 0f
        var opacity = 1f
        
        properties.forEachIndexed { index, prop ->
            val value = values.getOrNull(index) ?: return@forEachIndexed
            
            when (prop.name.lowercase()) {
                "x" -> x = value.toFloatOrNull() ?: 0f
                "y" -> y = value.toFloatOrNull() ?: 0f
                "z" -> z = value.toFloatOrNull() ?: 0f
                "red", "r" -> r = (value.toIntOrNull() ?: 0) / 255f
                "green", "g" -> g = (value.toIntOrNull() ?: 0) / 255f
                "blue", "b" -> b = (value.toIntOrNull() ?: 0) / 255f
                "opacity", "alpha" -> opacity = value.toFloatOrNull() ?: 1f
            }
        }
        
        return createGaussian(x, y, z, r, g, b, opacity)
    }
    
    /**
     * Parse single vertex from binary buffer
     */
    private fun parseBinaryVertex(buffer: ByteBuffer, properties: List<PropertyInfo>): GaussianSplat? {
        buffer.rewind()
        
        var x = 0f; var y = 0f; var z = 0f
        var r = 0f; var g = 0f; var b = 0f
        var opacity = 1f
        
        properties.forEach { prop ->
            val value = when (prop.type) {
                "float" -> buffer.float
                "double" -> buffer.double.toFloat()
                "uchar", "uint8" -> buffer.get().toInt() and 0xFF
                "int" -> buffer.int
                else -> 0f
            }
            
            when (prop.name.lowercase()) {
                "x" -> x = value as Float
                "y" -> y = value as Float
                "z" -> z = value as Float
                "red", "r" -> r = (value as Int) / 255f
                "green", "g" -> g = (value as Int) / 255f
                "blue", "b" -> b = (value as Int) / 255f
                "opacity", "alpha" -> opacity = value as Float
            }
        }
        
        return createGaussian(x, y, z, r, g, b, opacity)
    }
    
    /**
     * Create GaussianSplat from parsed values
     */
    private fun createGaussian(x: Float, y: Float, z: Float, r: Float, g: Float, b: Float, opacity: Float): GaussianSplat {
        return GaussianSplat(
            position = floatArrayOf(x, y, z),
            rotation = floatArrayOf(0f, 0f, 0f, 1f), // Identity quaternion
            scale = floatArrayOf(0.01f, 0.01f, 0.01f), // Small default scale
            shCoefficients = floatArrayOf(r, g, b), // RGB as SH coefficients (simplified)
            opacity = opacity.coerceIn(0f, 1f)
        )
    }
    
    /**
     * Calculate bounding box and create scene
     */
    private fun createScene(filename: String, gaussians: List<GaussianSplat>): SplatScene {
        if (gaussians.isEmpty()) {
            throw IllegalArgumentException("PLY file contains no valid vertices")
        }
        
        // Calculate bounding box
        val minX = gaussians.minOf { it.position[0] }
        val minY = gaussians.minOf { it.position[1] }
        val minZ = gaussians.minOf { it.position[2] }
        val maxX = gaussians.maxOf { it.position[0] }
        val maxY = gaussians.maxOf { it.position[1] }
        val maxZ = gaussians.maxOf { it.position[2] }
        
        val bbox = BoundingBox(
            min = floatArrayOf(minX, minY, minZ),
            max = floatArrayOf(maxX, maxY, maxZ)
        )
        
        return SplatScene(
            id = UUID.randomUUID().toString(),
            name = filename.removeSuffix(".ply"),
            gaussians = gaussians,
            cameraIntrinsics = null, // No camera data in PLY files
            boundingBox = bbox,
            createdAt = Date(System.currentTimeMillis()),
            modifiedAt = Date(System.currentTimeMillis())
        )
    }
    
    /**
     * Calculate bytes per vertex for binary format
     */
    private fun calculateBytesPerVertex(properties: List<PropertyInfo>): Int {
        return properties.map { prop ->
            when (prop.type) {
                "float" -> 4
                "double" -> 8
                "uchar", "uint8" -> 1
                "int" -> 4
                "short" -> 2
                else -> 4
            }
        }.sum()
    }
}

/**
 * PLY header information
 */
private data class PlyHeader(
    val format: PlyFormat,
    val vertexCount: Int,
    val properties: List<PropertyInfo>
)

/**
 * PLY property metadata
 */
private data class PropertyInfo(
    val name: String,
    val type: String
)

/**
 * PLY file format types
 */
private enum class PlyFormat {
    ASCII,
    BINARY_LITTLE_ENDIAN,
    BINARY_BIG_ENDIAN
}
