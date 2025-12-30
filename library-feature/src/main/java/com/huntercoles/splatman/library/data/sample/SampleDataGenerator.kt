package com.huntercoles.splatman.library.data.sample

import com.huntercoles.splatman.library.domain.model.BoundingBox
import com.huntercoles.splatman.library.domain.model.CameraIntrinsics
import com.huntercoles.splatman.library.domain.model.CaptureMetadata
import com.huntercoles.splatman.library.domain.model.GaussianSplat
import com.huntercoles.splatman.library.domain.model.SplatScene
import com.huntercoles.splatman.library.domain.repository.SplatSceneRepository
import timber.log.Timber
import java.util.Date
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * Helper to generate sample Gaussian splat scenes for testing.
 * 
 * Creates realistic-looking scenes with varying sizes and properties.
 * Useful for:
 * - UI development without real data
 * - Testing performance with different Gaussian counts
 * - Demonstrating the app without ARCore capture
 */
@Singleton
class SampleDataGenerator @Inject constructor(
    private val repository: SplatSceneRepository
) {
    
    /**
     * Add sample scenes to the library if it's empty
     */
    suspend fun addSampleScenesIfEmpty() {
        val count = repository.getSceneCount()
        if (count == 0) {
            Timber.d("Library is empty, adding sample scenes...")
            addSampleScenes()
        }
    }
    
    /**
     * Add sample scene (Rainbow Sphere only)
     */
    suspend fun addSampleScenes() {
        val rainbowSphere = createRainbowSphere()
        repository.saveScene(rainbowSphere)
            .onSuccess {
                Timber.d("Added sample scene: ${rainbowSphere.name}")
            }
            .onFailure { error ->
                Timber.e(error, "Failed to add sample scene")
            }
    }
    
    /**
     * Create a colorful rainbow sphere (25k Gaussians)
     * Perfect demonstration of Gaussian splat rendering
     */
    private fun createRainbowSphere(): SplatScene {
        val gaussians = mutableListOf<GaussianSplat>()
        
        // Create a sphere of colorful Gaussians
        val radius = 2f
        val count = 25_000
        
        repeat(count) {
            val theta = Random.nextFloat() * Math.PI.toFloat() * 2
            val phi = Random.nextFloat() * Math.PI.toFloat()
            
            val x = radius * kotlin.math.sin(phi) * kotlin.math.cos(theta)
            val y = radius * kotlin.math.sin(phi) * kotlin.math.sin(theta)
            val z = radius * kotlin.math.cos(phi)
            
            // Rainbow colors based on angle
            val hue = (theta / (Math.PI * 2)).toFloat()
            val rgb = hsvToRgb(hue, 0.8f, 0.9f)
            
            gaussians.add(
                GaussianSplat(
                    position = floatArrayOf(x, y, z),
                    scale = floatArrayOf(0.05f, 0.05f, 0.05f),
                    rotation = floatArrayOf(1f, 0f, 0f, 0f),
                    shCoefficients = rgb,
                    opacity = Random.nextFloat() * 0.3f + 0.7f
                )
            )
        }
        
        return SplatScene(
            id = UUID.randomUUID().toString(),
            name = "Rainbow Sphere",
            createdAt = Date(),
            modifiedAt = Date(),
            gaussians = gaussians,
            cameraIntrinsics = createSampleCameraIntrinsics(),
            boundingBox = BoundingBox.fromPoints(gaussians.map { it.position }),
            shDegree = 0,
            captureMetadata = createSampleCaptureMetadata(
                frameCount = 400,
                duration = 40f
            )
        )
    }
    
    /**
     * Generate random Gaussians within bounds (for testing only)
     * Not exposed - only used internally for rainbow sphere generation
     */
    private fun generateRandomGaussians(
        count: Int,
        bounds: Pair<FloatArray, FloatArray>
    ): List<GaussianSplat> {
        val (min, max) = bounds
        return List(count) {
            val x = Random.nextFloat() * (max[0] - min[0]) + min[0]
            val y = Random.nextFloat() * (max[1] - min[1]) + min[1]
            val z = Random.nextFloat() * (max[2] - min[2]) + min[2]
            val sx = Random.nextFloat() * 0.1f + 0.05f
            val sy = Random.nextFloat() * 0.1f + 0.05f
            val sz = Random.nextFloat() * 0.1f + 0.05f
            val r = Random.nextFloat() * 0.5f + 0.3f
            val g = Random.nextFloat() * 0.5f + 0.3f
            val b = Random.nextFloat() * 0.5f + 0.3f
            val opacity = Random.nextFloat() * 0.3f + 0.7f
            
            GaussianSplat.createRGB(
                x, y, z,
                sx, sy, sz,
                1f, 0f, 0f, 0f, // Identity quaternion (qx, qy, qz, qw)
                r, g, b,
                opacity
            )
        }
    }
    
    /**
     * Create sample camera intrinsics
     */
    private fun createSampleCameraIntrinsics(): CameraIntrinsics {
        return CameraIntrinsics(
            focalLength = Pair(1000f, 1000f),
            principalPoint = Pair(540f, 960f),
            imageSize = Pair(1080, 1920)
        )
    }
    
    /**
     * Create sample capture metadata
     */
    private fun createSampleCaptureMetadata(
        frameCount: Int,
        duration: Float
    ): CaptureMetadata {
        return CaptureMetadata(
            deviceModel = "Pixel 8 Pro",
            androidVersion = "14",
            arCoreVersion = "1.40.0",
            captureDuration = duration,
            frameCount = frameCount,
            samplingRate = 10,
            optimizationIterations = 100,
            processingTime = duration * 4f, // Simulated processing time
            averageTrackingQuality = 0.85f
        )
    }
    
    /**
     * Convert HSV to RGB
     */
    private fun hsvToRgb(h: Float, s: Float, v: Float): FloatArray {
        val c = v * s
        val x = c * (1 - kotlin.math.abs((h * 6f) % 2f - 1f))
        val m = v - c
        
        val (r, g, b) = when ((h * 6f).toInt()) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        
        return floatArrayOf(r + m, g + m, b + m)
    }
}
