package com.huntercoles.splatman.splat.sfm

import com.huntercoles.splatman.splat.math.Quat
import com.huntercoles.splatman.splat.model.CameraIntrinsics
import com.huntercoles.splatman.splat.model.CameraPose
import com.huntercoles.splatman.splat.model.SeedPoints

/** One captured image handed to pose recovery (epic #200, ADR 0012). */
data class SfmFrame(val index: Int, val timestampNs: Long, val imagePath: String, val intrinsics: CameraIntrinsics) {
    init {
        require(index >= 0) { "index must be >= 0, got $index" }
        require(imagePath.isNotEmpty()) { "imagePath must not be empty" }
    }
}

/**
 * n IMU samples; [xyz] interleaved (3n) in the IMU (device sensor) frame; gyro rad/s, accel m/s^2; timestamps on
 * the camera clock. Arrays are not copied: immutable by contract.
 */
class ImuSamples(val timestampsNs: LongArray, val xyz: FloatArray) {
    init {
        require(xyz.size == 3 * timestampsNs.size) {
            "xyz must hold ${3 * timestampsNs.size} floats, got ${xyz.size}"
        }
    }

    val count: Int get() = timestampsNs.size
}

enum class FeatureKind { ORB, AKAZE }

data class SfmOptions(
    val features: FeatureKind = FeatureKind.ORB,
    val maxFeatures: Int = 4000,
    val longSidePx: Int = 960,
    val matchWindow: Int = 8,
    val useGyroPrior: Boolean = true,
    val baIterations: Int = 30,
) {
    init {
        require(maxFeatures > 0) { "maxFeatures must be > 0, got $maxFeatures" }
        require(longSidePx > 0) { "longSidePx must be > 0, got $longSidePx" }
        require(matchWindow > 0) { "matchWindow must be > 0, got $matchWindow" }
        require(baIterations >= 0) { "baIterations must be >= 0, got $baIterations" }
    }
}

/** Everything pose recovery needs; [frames] are non-empty and in strictly increasing [SfmFrame.index] order. */
data class SfmInput(
    val frames: List<SfmFrame>,
    val gyro: ImuSamples,
    val accel: ImuSamples,
    val rotationCcvImu: Quat,
    val options: SfmOptions = SfmOptions(),
) {
    init {
        require(frames.isNotEmpty()) { "frames must not be empty" }
        for (k in 1 until frames.size) {
            require(frames[k].index > frames[k - 1].index) {
                "frames out of index order at position $k: ${frames[k - 1].index} then ${frames[k].index}"
            }
        }
    }
}

/** A candidate match pair, i < j; [rotationCjCi] is the gyro-predicted rotation, null = none. */
data class FramePair(val i: Int, val j: Int, val rotationCjCi: Quat?) {
    init {
        require(i >= 0) { "i must be >= 0, got $i" }
        require(i < j) { "pair must have i < j, got i=$i j=$j" }
    }
}

/**
 * What the native side returns, OpenCV axes (Ccv), world-to-camera; arrays indexed by frame / point / observation.
 * [obsUv] is in pixels of the full-size image (the frame's intrinsics). Arrays are not copied: immutable by contract.
 */
class SparseModel(
    val registered: BooleanArray,
    /** w, x, y, z, 4 per frame. */
    val rotationsCcvW: FloatArray,
    /** 3 per frame. */
    val translationsCcvW: FloatArray,
    /** 3 per point. */
    val points: FloatArray,
    /** r, g, b, 3 per point. */
    val colours: ByteArray,
    val obsFrame: IntArray,
    val obsPoint: IntArray,
    /** u, v, 2 per observation. */
    val obsUv: FloatArray,
) {
    val frameCount: Int get() = registered.size
    val pointCount: Int get() = points.size / 3
    val observationCount: Int get() = obsFrame.size

    init {
        val f = registered.size
        require(rotationsCcvW.size == 4 * f) { "rotationsCcvW must hold ${4 * f} floats, got ${rotationsCcvW.size}" }
        require(translationsCcvW.size == 3 * f) {
            "translationsCcvW must hold ${3 * f} floats, got ${translationsCcvW.size}"
        }
        require(points.size % 3 == 0) { "points must hold 3 floats per point, got ${points.size}" }
        val p = points.size / 3
        require(colours.size == 3 * p) { "colours must hold ${3 * p} bytes, got ${colours.size}" }
        val o = obsFrame.size
        require(obsPoint.size == o) { "obsPoint must hold $o entries, got ${obsPoint.size}" }
        require(obsUv.size == 2 * o) { "obsUv must hold ${2 * o} floats, got ${obsUv.size}" }
        for (k in 0 until o) {
            val fi = obsFrame[k]
            require(fi in 0 until f) { "observation $k points at missing frame $fi" }
            require(registered[fi]) { "observation $k points at unregistered frame $fi" }
            val pi = obsPoint[k]
            require(pi in 0 until p) { "observation $k points at missing point $pi" }
        }
    }
}

enum class SfmStage { LOAD, FEATURES, MATCHING, INIT, REGISTER, BUNDLE_ADJUST, ALIGN }

data class SfmStats(
    val totalFrames: Int,
    val registeredFrames: Int,
    val pointCount: Int,
    val meanReprojPx: Float,
    val medianReprojPx: Float,
    val stageMs: Map<SfmStage, Long>,
) {
    init {
        require(totalFrames >= 0 && registeredFrames in 0..totalFrames) {
            "registeredFrames must be in 0..$totalFrames, got $registeredFrames"
        }
        require(pointCount >= 0) { "pointCount must be >= 0, got $pointCount" }
    }
}

/** Poses are `T_W_Cgl` in W (ADR 0003), index-aligned with [SfmInput.frames], null = not registered. */
class SfmResult(val poses: List<CameraPose?>, val seed: SeedPoints, val stats: SfmStats) {
    init {
        require(poses.size == stats.totalFrames) { "poses must hold ${stats.totalFrames} entries, got ${poses.size}" }
        val reg = poses.count { it != null }
        require(reg == stats.registeredFrames) {
            "stats.registeredFrames ${stats.registeredFrames} does not match $reg poses"
        }
        require(seed.count == stats.pointCount) { "seed has ${seed.count} points, stats say ${stats.pointCount}" }
    }
}
