package com.huntercoles.splatman.capture.tracking

import com.huntercoles.splatman.splat.model.CameraIntrinsics
import com.huntercoles.splatman.splat.model.DepthImage
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** Pure pieces of building a [TrackedFrame] from tracker buffers. */
object FrameParts {
    private const val MM_PER_M = 1000f

    /**
     * Runs [block]; if it throws, closes [resource] (when not null) before rethrowing. The way a frame builder hands
     * a just-acquired camera image on: either it ends up inside the result, or it is closed.
     */
    @Suppress("TooGenericExceptionCaught")
    inline fun <T> closeOnFailure(resource: AutoCloseable?, block: () -> T): T {
        try {
            return block()
        } catch (t: Throwable) {
            try {
                resource?.close()
            } catch (suppressed: Throwable) {
                t.addSuppressed(suppressed)
            }
            throw t
        }
    }

    /** ARCore `PointCloud.getPoints()` (x, y, z, confidence per point, world W, m) -> [FeaturePoints]. */
    fun featurePoints(xyzc: FloatBuffer): FeaturePoints {
        val src = xyzc.duplicate()
        val count = src.remaining() / 4
        val positions = FloatArray(count * 3)
        val confidence = FloatArray(count)
        for (i in 0 until count) {
            positions[i * 3] = src.get()
            positions[i * 3 + 1] = src.get()
            positions[i * 3 + 2] = src.get()
            confidence[i] = src.get()
        }
        return FeaturePoints(count, positions, confidence)
    }

    /**
     * A 16-bit depth plane (ARCore `acquireDepthImage16Bits`: unsigned millimetres, little endian, 0 = unknown) ->
     * [DepthImage] in metres with [intrinsics] (which must be the depth image's own size).
     */
    fun depthFromMillimetres(
        plane: ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        intrinsics: CameraIntrinsics,
    ): DepthImage {
        val src = plane.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val base = src.position()
        val w = intrinsics.width
        val h = intrinsics.height
        val depth = FloatArray(w * h)
        for (row in 0 until h) {
            for (col in 0 until w) {
                val mm = src.getShort(base + row * rowStride + col * pixelStride).toInt() and 0xFFFF
                depth[row * w + col] = mm / MM_PER_M
            }
        }
        return DepthImage(intrinsics, depth, confidence = null)
    }
}
