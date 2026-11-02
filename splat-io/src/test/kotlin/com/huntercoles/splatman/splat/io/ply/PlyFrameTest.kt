package com.huntercoles.splatman.splat.io.ply

import com.huntercoles.splatman.splat.io.ply.PlyFixtures.read
import com.huntercoles.splatman.splat.io.ply.PlyFixtures.write
import com.huntercoles.splatman.splat.model.SplatCloud
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PlyFrameTest {

    private val cloud = SplatCloud(
        count = 1,
        shDegree = 1,
        positions = floatArrayOf(1f, 2f, 3f),
        logScales = floatArrayOf(-1f, -2f, -3f),
        rotations = floatArrayOf(0.9f, 0.1f, 0.2f, 0.3f),
        opacityLogits = floatArrayOf(0.7f),
        shDc = floatArrayOf(0.1f, 0.2f, 0.3f),
        shRest = floatArrayOf(1f, 1f, 1f, 2f, 2f, 2f, 3f, 3f, 3f),
    )

    /** The written body as floats, in header order: x y z nx ny nz dc(3) rest(9) opacity scale(3) rot(4). */
    private fun body(): FloatArray {
        val bytes = write(cloud)
        val headerLength = PlyWriter.header(1, 1).length
        val bb = ByteBuffer.wrap(bytes, headerLength, bytes.size - headerLength).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray((bytes.size - headerLength) / 4) { bb.getFloat() }
    }

    @Test
    fun `a splat at W (1,2,3) is written as (1,-2,-3)`() {
        assertArrayEquals(floatArrayOf(1f, -2f, -3f), body().copyOfRange(0, 3))
    }

    @Test
    fun `normals are written as zero`() {
        assertArrayEquals(floatArrayOf(0f, 0f, 0f), body().copyOfRange(3, 6))
    }

    @Test
    fun `q (w,x,y,z) is written as (w,x,-y,-z)`() {
        assertArrayEquals(floatArrayOf(0.9f, 0.1f, -0.2f, -0.3f), body().copyOfRange(22, 26))
    }

    @Test
    fun `scales, opacity and DC are written unchanged and degree-1 SH gets signs - - +`() {
        val b = body()
        assertArrayEquals(floatArrayOf(0.1f, 0.2f, 0.3f), b.copyOfRange(6, 9))
        assertArrayEquals(floatArrayOf(-1f, -1f, 1f, -2f, -2f, 2f, -3f, -3f, 3f), b.copyOfRange(9, 18))
        assertArrayEquals(floatArrayOf(0.7f, -1f, -2f, -3f), b.copyOfRange(18, 22))
    }

    @Test
    fun `reading converts back to W`() {
        val back = read(write(cloud))
        assertArrayEquals(cloud.positions, back.positions)
        assertArrayEquals(cloud.rotations, back.rotations)
        assertArrayEquals(cloud.shRest, back.shRest)
    }
}
