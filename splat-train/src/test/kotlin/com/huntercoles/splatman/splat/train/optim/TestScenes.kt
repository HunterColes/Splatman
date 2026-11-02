package com.huntercoles.splatman.splat.train.optim

import com.huntercoles.splatman.splat.math.Quat
import com.huntercoles.splatman.splat.math.Vec3
import com.huntercoles.splatman.splat.model.CameraIntrinsics
import com.huntercoles.splatman.splat.model.CameraPose
import com.huntercoles.splatman.splat.model.Keyframe
import com.huntercoles.splatman.splat.model.RgbImage
import com.huntercoles.splatman.splat.model.SplatCloud
import com.huntercoles.splatman.splat.train.raster.Lens
import com.huntercoles.splatman.splat.train.raster.Rasterizer
import com.huntercoles.splatman.splat.train.raster.RenderBuffers
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Shared fixtures of the optimiser tests: a seeded splat cloud, ring cameras and rendered keyframes. */
internal object TestScenes {

    /**
     * [n] splats in a 0.8 m cube about the origin: anisotropic sizes 5..13 cm, random unit rotations, opacity
     * 0.6..0.9, random colours. Seeded.
     */
    fun cloud(n: Int, seed: Long): SplatCloud {
        val r = Random(seed)
        val positions = FloatArray(3 * n) { (r.nextDouble() * 0.8 - 0.4).toFloat() }
        val logScales = FloatArray(3 * n) { ln(0.05 + 0.08 * r.nextDouble()).toFloat() }
        val rotations = FloatArray(4 * n)
        for (i in 0 until n) {
            val q = DoubleArray(4) { r.nextGaussian() }
            val norm = sqrt(q.sumOf { it * it })
            for (a in 0 until 4) rotations[4 * i + a] = (q[a] / norm).toFloat()
        }
        val opacity = FloatArray(n) { val o = 0.6 + 0.3 * r.nextDouble(); ln(o / (1 - o)).toFloat() }
        val shDc = FloatArray(3 * n) { ((r.nextDouble() - 0.5) * 2.5).toFloat() }
        return SplatCloud(n, 0, positions, logScales, rotations, opacity, shDc, FloatArray(0))
    }

    /** [views] cameras on a ring of [radius] m about +Y, alternately 0.3 m above and below, looking at the origin. */
    fun ring(views: Int, radius: Float): List<CameraPose> = List(views) { v ->
        val theta = 2 * PI * v / views
        val yaw = Quat(cos(theta / 2).toFloat(), 0f, sin(theta / 2).toFloat(), 0f)
        val height = if (v % 2 == 0) 0.3f else -0.3f
        val pitch = -kotlin.math.atan2(height.toDouble(), radius.toDouble())
        val tilt = Quat(cos(pitch / 2).toFloat(), sin(pitch / 2).toFloat(), 0f, 0f)
        CameraPose(
            yaw * tilt,
            Vec3((radius * sin(theta)).toFloat(), height, (radius * cos(theta)).toFloat()),
        )
    }

    fun intrinsics(size: Int, fov: Float = 1.2f): CameraIntrinsics {
        val f = size / 2f / kotlin.math.tan(fov / 2)
        return CameraIntrinsics(f, f, (size - 1) / 2f, (size - 1) / 2f, size, size)
    }

    /** Renders [cloud] from every pose into 8-bit keyframes (the targets). */
    fun keyframes(cloud: SplatCloud, poses: List<CameraPose>, k: CameraIntrinsics): List<Keyframe> =
        Rasterizer(1).use { raster ->
            val out = RenderBuffers(k.width, k.height)
            poses.mapIndexed { id, pose ->
                raster.forward(cloud, k, pose, out)
                val rgba = ByteArray(4 * k.width * k.height)
                for (p in 0 until k.width * k.height) {
                    for (c in 0 until 3) {
                        rgba[4 * p + c] = (out.color[3 * p + c].coerceIn(0f, 1f) * 255f).roundToInt().toByte()
                    }
                    rgba[4 * p + 3] = 0xFF.toByte()
                }
                Keyframe(id, id * 33_000_000L, k, pose, RgbImage(k.width, k.height, rgba), null)
            }
        }

    /**
     * PSNR of [cloud] against the keyframes, dB, from the mean squared error over every view, pixel and channel;
     * rendered through [lens] (null = the keyframes' pinholes).
     */
    fun psnr(cloud: SplatCloud, keyframes: List<Keyframe>, lens: Lens? = null): Double = Rasterizer(1).use { raster ->
        var se = 0.0
        var count = 0L
        for (kf in keyframes) {
            val out = RenderBuffers(kf.image.width, kf.image.height)
            raster.forward(cloud, kf.intrinsics, kf.pose, out, lens)
            for (p in 0 until kf.image.width * kf.image.height) {
                for (c in 0 until 3) {
                    val target = (kf.image.rgba[4 * p + c].toInt() and 0xFF) / 255.0
                    val d = out.color[3 * p + c] - target
                    se += d * d
                    count++
                }
            }
        }
        10 * log10(1.0 / (se / count))
    }
}
