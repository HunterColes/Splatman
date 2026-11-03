package com.huntercoles.splatman.library.importing

import com.huntercoles.splatman.splat.io.ply.PlyWriter
import com.huntercoles.splatman.splat.io.splat.SplatCodec
import com.huntercoles.splatman.splat.model.SplatCloud
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Small splat files built in memory for the import tests. */
object ImportFixtures {

    /** [n] small grey splats along x, identity rotations, opacity 0.5. */
    fun cloud(n: Int, shDegree: Int = 0): SplatCloud {
        val rest = 3 * SplatCloud.restCoefficients(shDegree)
        return SplatCloud(
            count = n,
            shDegree = shDegree,
            positions = FloatArray(3 * n) { if (it % 3 == 0) it / 3 * 0.1f else 0f },
            logScales = FloatArray(3 * n) { -3f },
            rotations = FloatArray(4 * n) { if (it % 4 == 0) 1f else 0f },
            opacityLogits = FloatArray(n),
            shDc = FloatArray(3 * n) { 0.2f },
            shRest = FloatArray(n * rest) { 0.01f },
        )
    }

    fun ply(cloud: SplatCloud): ByteArray = ByteArrayOutputStream().also { PlyWriter.write(cloud, it) }.toByteArray()

    fun splat(cloud: SplatCloud): ByteArray = SplatCodec.write(cloud)

    /** A binary PLY of [n] points with only x y z and colours: a point cloud, not a splat. */
    fun pointCloudPly(n: Int): ByteArray {
        val header = buildString {
            append("ply\nformat binary_little_endian 1.0\nelement vertex ").append(n).append('\n')
            for (p in listOf("x", "y", "z")) append("property float ").append(p).append('\n')
            for (p in listOf("red", "green", "blue")) append("property uchar ").append(p).append('\n')
            append("end_header\n")
        }.toByteArray(Charsets.US_ASCII)
        val body = ByteBuffer.allocate(n * 15).order(ByteOrder.LITTLE_ENDIAN)
        repeat(n) { i ->
            body.putFloat(i.toFloat()).putFloat(0f).putFloat(0f)
            body.put(10).put(20).put(30)
        }
        return header + body.array()
    }

    /** [ply] with the float at [offsetFromEnd] bytes before the end of the body set to NaN. */
    fun withNaN(ply: ByteArray, offsetFromEnd: Int = 4): ByteArray {
        val copy = ply.copyOf()
        ByteBuffer.wrap(copy).order(ByteOrder.LITTLE_ENDIAN).putFloat(copy.size - offsetFromEnd, Float.NaN)
        return copy
    }
}
