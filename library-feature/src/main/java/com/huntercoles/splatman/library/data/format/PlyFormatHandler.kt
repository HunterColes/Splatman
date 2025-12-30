package com.huntercoles.splatman.library.data.format

import com.huntercoles.splatman.library.domain.model.BoundingBox
import com.huntercoles.splatman.library.domain.model.GaussianSplat
import com.huntercoles.splatman.library.domain.model.SplatScene
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * PLY (Polygon File Format) parser for Gaussian Splatting
 * 
 * PLY is the standard format from the original 3D Gaussian Splatting paper.
 * Format supports both ASCII and binary encodings.
 * 
 * Expected properties per Gaussian:
 * - x, y, z (position)
 * - nx, ny, nz (scale, stored as "normals")
 * - f_dc_0, f_dc_1, f_dc_2 (RGB color via SH degree 0)
 * - f_rest_* (higher-order SH, optional)
 * - opacity (alpha)
 * - rot_0, rot_1, rot_2, rot_3 (quaternion rotation)
 * - scale_0, scale_1, scale_2 (3D scale factors)
 * 
 * Mobile optimization: We only parse degree-0 SH (RGB) for efficiency.
 */
class PlyFormatHandler {
    
    /**
     * Import a .ply file into a SplatScene
     */
    fun import(file: File): Result<SplatScene> = runCatching {
        require(file.exists()) { "File does not exist: ${file.absolutePath}" }
        require(file.extension.equals("ply", ignoreCase = true)) { 
            "Expected .ply file, got .${file.extension}" 
        }
        
        val lines = file.readLines()
        val header = parseHeader(lines)
        
        val gaussians = when (header.format) {
            PlyFormat.ASCII -> parseAscii(lines, header)
            PlyFormat.BINARY_LITTLE_ENDIAN -> parseBinary(file, header, ByteOrder.LITTLE_ENDIAN)
            PlyFormat.BINARY_BIG_ENDIAN -> parseBinary(file, header, ByteOrder.BIG_ENDIAN)
        }
        
        val boundingBox = BoundingBox.fromPoints(gaussians.map { it.position })
        
        SplatScene(
            name = file.nameWithoutExtension,
            gaussians = gaussians,
            cameraIntrinsics = null,
            boundingBox = boundingBox,
            filePath = file.absolutePath,
            shDegree = 0  // Mobile: degree-0 only
        )
    }
    
    /**
     * Export a SplatScene to .ply file
     */
    fun export(scene: SplatScene, outputFile: File): Result<Unit> = runCatching {
        val writer = outputFile.bufferedWriter()
        
        // Write PLY header
        writer.write("ply\n")
        writer.write("format binary_little_endian 1.0\n")
        writer.write("element vertex ${scene.gaussianCount}\n")
        
        // Position
        writer.write("property float x\n")
        writer.write("property float y\n")
        writer.write("property float z\n")
        
        // Normals (placeholder, unused)
        writer.write("property float nx\n")
        writer.write("property float ny\n")
        writer.write("property float nz\n")
        
        // SH degree 0 (RGB)
        writer.write("property float f_dc_0\n")
        writer.write("property float f_dc_1\n")
        writer.write("property float f_dc_2\n")
        
        // Scale
        writer.write("property float scale_0\n")
        writer.write("property float scale_1\n")
        writer.write("property float scale_2\n")
        
        // Rotation (quaternion)
        writer.write("property float rot_0\n")
        writer.write("property float rot_1\n")
        writer.write("property float rot_2\n")
        writer.write("property float rot_3\n")
        
        // Opacity
        writer.write("property float opacity\n")
        
        writer.write("end_header\n")
        writer.flush()
        writer.close()
        
        // Write binary data
        val buffer = ByteBuffer.allocate(scene.gaussians.size * (4 * 18))  // 18 floats per Gaussian
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        
        scene.gaussians.forEach { gaussian ->
            // Position
            gaussian.position.forEach { buffer.putFloat(it) }
            
            // Normals (zeros, unused)
            buffer.putFloat(0f)
            buffer.putFloat(0f)
            buffer.putFloat(0f)
            
            // SH coefficients (RGB)
            gaussian.shCoefficients.take(3).forEach { buffer.putFloat(it) }
            
            // Scale
            gaussian.scale.forEach { buffer.putFloat(it) }
            
            // Rotation
            gaussian.rotation.forEach { buffer.putFloat(it) }
            
            // Opacity
            buffer.putFloat(gaussian.opacity)
        }
        
        outputFile.appendBytes(buffer.array())
    }
    
    private fun parseHeader(lines: List<String>): PlyHeader {
        var format = PlyFormat.ASCII
        var vertexCount = 0
        var headerEndLine = 0
        
        for ((index, line) in lines.withIndex()) {
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
                }
                line == "end_header" -> {
                    headerEndLine = index + 1
                    break
                }
            }
        }
        
        return PlyHeader(format, vertexCount, headerEndLine)
    }
    
    private fun parseAscii(lines: List<String>, header: PlyHeader): List<GaussianSplat> {
        val gaussians = mutableListOf<GaussianSplat>()
        
        for (i in header.headerEndLine until (header.headerEndLine + header.vertexCount)) {
            val values = lines[i].trim().split("\\s+".toRegex()).map { it.toFloat() }
            
            // Expected format: x y z nx ny nz f_dc_0 f_dc_1 f_dc_2 scale_0 scale_1 scale_2 rot_0 rot_1 rot_2 rot_3 opacity
            if (values.size < 17) continue
            
            gaussians.add(
                GaussianSplat(
                    position = floatArrayOf(values[0], values[1], values[2]),
                    scale = floatArrayOf(values[9], values[10], values[11]),
                    rotation = floatArrayOf(values[12], values[13], values[14], values[15]),
                    shCoefficients = floatArrayOf(values[6], values[7], values[8]),
                    opacity = values[16]
                )
            )
        }
        
        return gaussians
    }
    
    private fun parseBinary(file: File, header: PlyHeader, byteOrder: ByteOrder): List<GaussianSplat> {
        val gaussians = mutableListOf<GaussianSplat>()
        
        // Calculate header size in bytes
        val headerSize = file.readText().indexOf("end_header") + "end_header\n".length
        
        val bytes = file.readBytes().copyOfRange(headerSize, file.readBytes().size)
        val buffer = ByteBuffer.wrap(bytes).order(byteOrder)
        
        val floatsPerVertex = 18  // 3 pos + 3 normals + 3 sh + 3 scale + 4 rot + 1 opacity + 1 padding
        
        repeat(header.vertexCount) {
            val position = FloatArray(3) { buffer.float }
            buffer.float; buffer.float; buffer.float  // Skip normals
            val sh = FloatArray(3) { buffer.float }
            val scale = FloatArray(3) { buffer.float }
            val rotation = FloatArray(4) { buffer.float }
            val opacity = buffer.float
            
            gaussians.add(
                GaussianSplat(position, scale, rotation, sh, opacity)
            )
        }
        
        return gaussians
    }
    
    private data class PlyHeader(
        val format: PlyFormat,
        val vertexCount: Int,
        val headerEndLine: Int
    )
    
    private enum class PlyFormat {
        ASCII,
        BINARY_LITTLE_ENDIAN,
        BINARY_BIG_ENDIAN
    }
}
