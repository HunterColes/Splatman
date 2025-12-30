package com.huntercoles.splatman.library.data.format

import com.huntercoles.splatman.library.domain.model.BoundingBox
import com.huntercoles.splatman.library.domain.model.GaussianSplat
import com.huntercoles.splatman.library.domain.model.SplatScene
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * .SPLAT format handler - optimized binary format for Gaussian Splatting
 * 
 * This is a compact, mobile-optimized format inspired by the Polycam .splat spec.
 * Much smaller than .PLY and faster to load/save.
 * 
 * Format structure:
 * Header (128 bytes):
 * - Magic number: "SPLAT" (5 bytes)
 * - Version: uint8 (1 byte)
 * - Gaussian count: uint32 (4 bytes)
 * - SH degree: uint8 (1 byte, typically 0 for mobile)
 * - Reserved: 117 bytes
 * 
 * Per-Gaussian data (56 bytes for degree-0):
 * - Position: float32[3] (12 bytes)
 * - Scale: float32[3] (12 bytes)
 * - Rotation: float32[4] (16 bytes) - quaternion
 * - SH coefficients: float32[3] (12 bytes) - RGB only for degree-0
 * - Opacity: float32 (4 bytes)
 * 
 * Total file size = 128 + (gaussianCount * 56) bytes
 * For 100k Gaussians: ~5.3 MB (vs ~8-12 MB for .PLY)
 */
class SplatFormatHandler {
    
    companion object {
        private const val MAGIC_NUMBER = "SPLAT"
        private const val VERSION: Byte = 1
        private const val HEADER_SIZE = 128
        private const val GAUSSIAN_SIZE_DEGREE_0 = 56  // bytes per Gaussian (degree-0 SH)
    }
    
    /**
     * Import a .splat file into a SplatScene
     */
    fun import(file: File): Result<SplatScene> = runCatching {
        require(file.exists()) { "File does not exist: ${file.absolutePath}" }
        require(file.extension.equals("splat", ignoreCase = true)) { 
            "Expected .splat file, got .${file.extension}" 
        }
        
        val bytes = file.readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        
        // Parse header
        val magic = ByteArray(5)
        buffer.get(magic)
        require(String(magic) == MAGIC_NUMBER) { "Invalid .splat file: magic number mismatch" }
        
        val version = buffer.get()
        require(version == VERSION) { "Unsupported .splat version: $version" }
        
        val gaussianCount = buffer.int
        val shDegree = buffer.get().toInt()
        
        // Skip reserved bytes
        buffer.position(HEADER_SIZE)
        
        // Parse Gaussians
        val gaussians = mutableListOf<GaussianSplat>()
        val shSize = if (shDegree == 0) 3 else 48
        
        repeat(gaussianCount) {
            val position = FloatArray(3) { buffer.float }
            val scale = FloatArray(3) { buffer.float }
            val rotation = FloatArray(4) { buffer.float }
            val sh = FloatArray(shSize) { buffer.float }
            val opacity = buffer.float
            
            gaussians.add(
                GaussianSplat(position, scale, rotation, sh, opacity)
            )
        }
        
        val boundingBox = BoundingBox.fromPoints(gaussians.map { it.position })
        
        SplatScene(
            name = file.nameWithoutExtension,
            gaussians = gaussians,
            cameraIntrinsics = null,
            boundingBox = boundingBox,
            filePath = file.absolutePath,
            shDegree = shDegree
        )
    }
    
    /**
     * Export a SplatScene to .splat file
     * 
     * This is the preferred export format for mobile due to:
     * - Smaller file size (~40% smaller than .PLY)
     * - Faster loading (direct binary read, no parsing)
     * - Mobile-optimized (degree-0 SH only)
     */
    fun export(scene: SplatScene, outputFile: File): Result<Unit> = runCatching {
        val shDegree = scene.shDegree
        val gaussianSize = if (shDegree == 0) GAUSSIAN_SIZE_DEGREE_0 else 204  // degree-3 = 204 bytes
        val totalSize = HEADER_SIZE + (scene.gaussianCount * gaussianSize)
        
        val buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)
        
        // Write header
        buffer.put(MAGIC_NUMBER.toByteArray())  // Magic number (5 bytes)
        buffer.put(VERSION)                      // Version (1 byte)
        buffer.putInt(scene.gaussianCount)       // Gaussian count (4 bytes)
        buffer.put(shDegree.toByte())           // SH degree (1 byte)
        
        // Reserved bytes (117 bytes)
        buffer.put(ByteArray(117))
        
        // Write Gaussians
        scene.gaussians.forEach { gaussian ->
            // Position
            gaussian.position.forEach { buffer.putFloat(it) }
            
            // Scale
            gaussian.scale.forEach { buffer.putFloat(it) }
            
            // Rotation
            gaussian.rotation.forEach { buffer.putFloat(it) }
            
            // SH coefficients
            gaussian.shCoefficients.forEach { buffer.putFloat(it) }
            
            // Opacity
            buffer.putFloat(gaussian.opacity)
        }
        
        outputFile.writeBytes(buffer.array())
    }
    
    /**
     * Quick metadata read without loading all Gaussians
     * Useful for library preview/thumbnails
     */
    fun readMetadata(file: File): Result<SplatMetadata> = runCatching {
        val buffer = ByteBuffer.wrap(file.readBytes().copyOfRange(0, HEADER_SIZE))
            .order(ByteOrder.LITTLE_ENDIAN)
        
        val magic = ByteArray(5)
        buffer.get(magic)
        require(String(magic) == MAGIC_NUMBER) { "Invalid .splat file" }
        
        val version = buffer.get()
        val gaussianCount = buffer.int
        val shDegree = buffer.get().toInt()
        
        val fileSize = file.length()
        val gaussianSize = if (shDegree == 0) GAUSSIAN_SIZE_DEGREE_0 else 204
        val expectedSize = HEADER_SIZE + (gaussianCount * gaussianSize)
        
        SplatMetadata(
            fileName = file.name,
            gaussianCount = gaussianCount,
            shDegree = shDegree,
            fileSizeBytes = fileSize,
            isValid = fileSize == expectedSize.toLong()
        )
    }
    
    data class SplatMetadata(
        val fileName: String,
        val gaussianCount: Int,
        val shDegree: Int,
        val fileSizeBytes: Long,
        val isValid: Boolean
    ) {
        val fileSizeMB: Float
            get() = fileSizeBytes / (1024f * 1024f)
    }
}
