package com.huntercoles.splatman.splat.capture

import com.huntercoles.splatman.splat.frames.FrameConversions
import com.huntercoles.splatman.splat.math.Vec3
import com.huntercoles.splatman.splat.model.CameraIntrinsics
import com.huntercoles.splatman.splat.model.CameraPose
import com.huntercoles.splatman.splat.model.DepthImage
import com.huntercoles.splatman.splat.model.RgbImage
import com.huntercoles.splatman.splat.model.SeedPoints
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Merges ARCore feature points and unprojected depth pixels into seed points (ADR 0004): one point per
 * [voxelM]-sized voxel of the world frame W (the first point to reach a voxel keeps it, position and colour),
 * at most [maxPoints] points, feature points only at confidence >= [minConfidence]. Colours are sampled from the
 * keyframe's RGB image (nearest pixel); a point outside that image is dropped.
 *
 * Storage is allocated once and reused across frames and [clear]; not thread-safe.
 */
class SeedAccumulator(
    /** Voxel edge, metres. */
    val voxelM: Float = 0.01f,
    /** Most points kept; later points are dropped. */
    val maxPoints: Int = 200_000,
    /** Lowest ARCore feature-point confidence (0..1) that is kept. */
    val minConfidence: Float = 0.3f,
) {
    init {
        require(voxelM > 0f && voxelM.isFinite()) { "voxelM must be positive, got $voxelM" }
        require(maxPoints >= 1) { "maxPoints must be >= 1, got $maxPoints" }
    }

    private val positions = FloatArray(3 * maxPoints)
    private val colours = ByteArray(3 * maxPoints)
    private val invVoxel = 1f / voxelM

    /** Open-addressing set of packed voxel keys, at most half full. */
    private val tableMask: Int
    private val table: LongArray

    init {
        var cap = 16
        while (cap < 2 * maxPoints) cap = cap shl 1
        table = LongArray(cap) { EMPTY }
        tableMask = cap - 1
    }

    /** Points held. */
    var count: Int = 0
        private set

    /** True once [maxPoints] points are held. */
    val isFull: Boolean get() = count >= maxPoints

    /**
     * Adds [count] ARCore feature points: [positionsW] holds x, y, z per point in W (metres, as
     * `PointCloud.getPoints()` after the pose conversion), [confidences] one 0..1 value per point. Colours come
     * from [rgb], seen with [intrinsics] from camera [pose] (`T_W_Cgl`). Returns the number of points added.
     */
    fun addPoints(
        positionsW: FloatArray,
        confidences: FloatArray,
        count: Int,
        intrinsics: CameraIntrinsics,
        pose: CameraPose,
        rgb: RgbImage,
    ): Int {
        require(count >= 0 && positionsW.size >= 3 * count && confidences.size >= count) {
            "need $count points: positions ${positionsW.size} floats, confidences ${confidences.size}"
        }
        checkImage(intrinsics, rgb)
        val toCamera = pose.rotation.normalized().conjugate()
        var added = 0
        for (i in 0 until count) {
            if (isFull) break
            if (!(confidences[i] >= minConfidence)) continue
            val pw = Vec3(positionsW[3 * i], positionsW[3 * i + 1], positionsW[3 * i + 2])
            val pCv = FrameConversions.glToCv(toCamera.rotate(pw - pose.translationM))
            if (add(pw, pCv, intrinsics, rgb)) added++
        }
        return added
    }

    /**
     * Adds every known pixel of [depth] (depth > 0 and finite), unprojected with the depth image's own intrinsics
     * and placed in W by [pose] (`T_W_Cgl`, the camera that took both images). Colours come from [rgb] through
     * [intrinsics], the RGB image's intrinsics. Returns the number of points added.
     */
    fun addDepth(depth: DepthImage, intrinsics: CameraIntrinsics, pose: CameraPose, rgb: RgbImage): Int {
        checkImage(intrinsics, rgb)
        val dk = depth.intrinsics
        val toWorld = pose.rotation.normalized()
        var added = 0
        for (v in 0 until dk.height) {
            val row = v * dk.width
            for (u in 0 until dk.width) {
                if (isFull) return added
                val d = depth.depthM[row + u]
                if (!(d > 0f) || !d.isFinite()) continue
                val pCv = FrameConversions.unproject(dk, u.toFloat(), v.toFloat(), d)
                val pw = toWorld.rotate(FrameConversions.cvToGl(pCv)) + pose.translationM
                if (add(pw, pCv, intrinsics, rgb)) added++
            }
        }
        return added
    }

    /** The points so far as a new [SeedPoints] (arrays copied; the accumulator keeps going). */
    fun toSeedPoints(): SeedPoints =
        SeedPoints(count, positions.copyOf(3 * count), colours.copyOf(3 * count))

    /** Forgets every point; storage is kept for the next capture. */
    fun clear() {
        table.fill(EMPTY)
        count = 0
    }

    /** Adds world point [pw] (camera point [pCv]) unless its voxel is taken or it is outside [rgb]. */
    private fun add(pw: Vec3, pCv: Vec3, intrinsics: CameraIntrinsics, rgb: RgbImage): Boolean {
        val key = voxelKey(pw) ?: return false
        val px = FrameConversions.project(intrinsics, pCv) ?: return false
        if (!px.u.isFinite() || !px.v.isFinite()) return false
        val iu = px.u.roundToInt()
        val iv = px.v.roundToInt()
        if (iu < 0 || iv < 0 || iu >= rgb.width || iv >= rgb.height) return false
        if (!insert(key)) return false
        val o = 3 * count
        positions[o] = pw.x
        positions[o + 1] = pw.y
        positions[o + 2] = pw.z
        val src = (iv * rgb.width + iu) * 4
        colours[o] = rgb.rgba[src]
        colours[o + 1] = rgb.rgba[src + 1]
        colours[o + 2] = rgb.rgba[src + 2]
        count++
        return true
    }

    /** Packs the voxel indices into 21 bits each (about +-10 km at 1 cm); null when out of range or not finite. */
    private fun voxelKey(p: Vec3): Long? {
        val ix = index(p.x) ?: return null
        val iy = index(p.y) ?: return null
        val iz = index(p.z) ?: return null
        return (ix shl 42) or (iy shl 21) or iz
    }

    private fun index(c: Float): Long? {
        if (!c.isFinite()) return null
        val i = floor((c * invVoxel).toDouble())
        if (i < -AXIS_OFFSET || i >= AXIS_OFFSET) return null
        return i.toLong() + AXIS_OFFSET
    }

    /** Inserts [key]; false when it was already there. */
    private fun insert(key: Long): Boolean {
        var h = key * -0x61c8864680b583ebL
        h = h xor (h ushr 29)
        var slot = h.toInt() and tableMask
        while (true) {
            val k = table[slot]
            if (k == EMPTY) {
                table[slot] = key
                return true
            }
            if (k == key) return false
            slot = (slot + 1) and tableMask
        }
    }

    private fun checkImage(intrinsics: CameraIntrinsics, rgb: RgbImage) {
        require(rgb.width == intrinsics.width && rgb.height == intrinsics.height) {
            "rgb is ${rgb.width}x${rgb.height} but its intrinsics are ${intrinsics.width}x${intrinsics.height}"
        }
    }

    private companion object {
        const val EMPTY = -1L
        const val AXIS_OFFSET = 1L shl 20
    }
}
