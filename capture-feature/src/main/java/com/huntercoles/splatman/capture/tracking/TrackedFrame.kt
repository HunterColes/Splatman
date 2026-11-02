package com.huntercoles.splatman.capture.tracking

import com.huntercoles.splatman.splat.model.CameraIntrinsics
import com.huntercoles.splatman.splat.model.CameraPose
import com.huntercoles.splatman.splat.model.DepthImage
import com.huntercoles.splatman.splat.model.RgbImage

/** Tracking state of one frame, mirroring ARCore's `TrackingState` in our own type. */
enum class TrackingState { TRACKING, PAUSED, STOPPED }

/** Why tracking is not [TrackingState.TRACKING], mirroring ARCore's `TrackingFailureReason` in our own type. */
enum class TrackingFailure {
    /** No failure (tracking, or still starting up). */
    NONE,

    /** The tracker is in a bad internal state. */
    BAD_STATE,

    /** Too dark. */
    INSUFFICIENT_LIGHT,

    /** The phone moves too fast. */
    EXCESSIVE_MOTION,

    /** Too little texture in view. */
    INSUFFICIENT_FEATURES,

    /** Another app holds the camera. */
    CAMERA_UNAVAILABLE,
}

/** The CPU camera image of one frame (YUV_420_888, sensor orientation). Must be closed exactly once. */
interface CameraImage : AutoCloseable {
    /** Width, pixels. */
    val width: Int

    /** Height, pixels. */
    val height: Int

    /** Converts to RGBA8888 (BT.601 full range); the image stays open. */
    fun toRgb(): RgbImage

    /**
     * A copy of the luma (Y) plane; the image stays open. The default derives BT.601 luma from [toRgb]; a YUV image
     * overrides it with a plain copy of its Y plane.
     */
    fun luma(): LumaPlane {
        val rgb = toRgb()
        val out = ByteArray(rgb.width * rgb.height)
        val px = rgb.rgba
        for (i in out.indices) {
            val r = px[4 * i].toInt() and 0xFF
            val g = px[4 * i + 1].toInt() and 0xFF
            val b = px[4 * i + 2].toInt() and 0xFF
            out[i] = ((LUMA_R * r + LUMA_G * g + LUMA_B * b + LUMA_HALF) shr LUMA_SHIFT).toByte()
        }
        return LumaPlane(rgb.width, rgb.height, rgb.width, out)
    }
}

// BT.601 luma weights, fixed point * 2^8 (they sum to 256).
private const val LUMA_R = 77
private const val LUMA_G = 150
private const val LUMA_B = 29
private const val LUMA_SHIFT = 8
private const val LUMA_HALF = 1 shl (LUMA_SHIFT - 1)

/** An 8-bit luma plane, sensor orientation: pixel (x, y) is `bytes[y * rowStride + x]`, read as unsigned. */
class LumaPlane(
    /** Width, pixels. */
    val width: Int,
    /** Height, pixels. */
    val height: Int,
    /** Bytes between the starts of two rows, >= [width]. */
    val rowStride: Int,
    /** The plane; not copied, immutable by contract. */
    val bytes: ByteArray,
) {
    init {
        require(width > 0 && height > 0) { "plane size must be positive, got ${width}x$height" }
        require(rowStride >= width) { "rowStride $rowStride is less than width $width" }
        require(bytes.size >= (height - 1) * rowStride + width) { "luma plane too small: ${bytes.size} bytes" }
    }
}

/** ARCore feature points of one frame. Arrays are not copied: immutable by contract. */
class FeaturePoints(
    /** Number of points N, >= 0. */
    val count: Int,
    /** 3N floats, x, y, z per point, world frame W, metres. */
    val positions: FloatArray,
    /** N floats, confidence 0..1 per point. */
    val confidence: FloatArray,
) {
    init {
        require(count >= 0) { "count must be >= 0, got $count" }
        require(positions.size == 3 * count) { "positions must hold ${3 * count} floats, got ${positions.size}" }
        require(confidence.size == count) { "confidence must hold $count floats, got ${confidence.size}" }
    }

    companion object {
        /** No points. */
        val EMPTY = FeaturePoints(0, FloatArray(0), FloatArray(0))
    }
}

/**
 * One tracked camera frame (ADR 0002 packet `tracking -> recorder`), in our types. It holds the platform CPU image,
 * so the consumer must [close] it (the recorder converts the frames it keeps to `Keyframe`s first).
 */
class TrackedFrame(
    /** Exposure time, nanoseconds on the ARCore `Frame.getTimestamp()` clock. */
    val timestampNs: Long,
    /** Tracking state of the camera at this frame. */
    val trackingState: TrackingState,
    /** `T_world_camera` (Cgl -> W, metres), from `FrameConversions.poseFromArCore`; last known when not tracking. */
    val pose: CameraPose,
    /** Intrinsics of [image] (`Camera.getImageIntrinsics()`), pixels of the CPU image. */
    val intrinsics: CameraIntrinsics,
    /** The CPU image, or null when the camera had none ready for this frame. Closed by [close]. */
    val image: CameraImage?,
    /** Feature points seen this frame, world frame W. */
    val points: FeaturePoints,
    /** Depth with its own intrinsics, or null when the phone gives none. */
    val depth: DepthImage?,
    /** Why tracking failed this frame; [TrackingFailure.NONE] when tracking. */
    val trackingFailure: TrackingFailure = TrackingFailure.NONE,
    /** Packet version (ADR 0002). */
    val version: Int = VERSION,
) : AutoCloseable {
    /** Releases the CPU image; safe to call more than once only if the image's own close is. */
    override fun close() {
        image?.close()
    }

    companion object {
        /** Current packet version. */
        const val VERSION = 1
    }
}
