package com.huntercoles.splatman.viewer.presentation.load

import com.huntercoles.splatman.splat.io.ply.PlyWriter
import com.huntercoles.splatman.splat.io.splat.SplatCodec
import com.huntercoles.splatman.splat.io.spz.SpzCodec
import com.huntercoles.splatman.splat.model.SplatCloud
import java.io.ByteArrayOutputStream

/** Small deterministic clouds and their file bytes for the viewer's load tests. */
object TestClouds {
    fun cloud(n: Int, shDegree: Int = 0): SplatCloud {
        val k = SplatCloud.restCoefficients(shDegree)
        return SplatCloud(
            count = n,
            shDegree = shDegree,
            positions = FloatArray(3 * n) { (it % 7 - 3).toFloat() * 0.5f },
            logScales = FloatArray(3 * n) { -2f },
            rotations = FloatArray(4 * n) { if (it % 4 == 0) 1f else 0f },
            opacityLogits = FloatArray(n) { 1f },
            shDc = FloatArray(3 * n) { 0.25f },
            shRest = FloatArray(n * 3 * k) { 0.01f * (it % 5) },
        )
    }

    fun ply(cloud: SplatCloud): ByteArray = ByteArrayOutputStream().also { PlyWriter.write(cloud, it) }.toByteArray()

    fun splat(cloud: SplatCloud): ByteArray = SplatCodec.write(cloud)

    fun spz(cloud: SplatCloud): ByteArray = SpzCodec.write(cloud)
}
