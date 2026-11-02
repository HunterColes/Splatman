package com.huntercoles.splatman.splat.io.ply

import com.huntercoles.splatman.splat.io.ply.PlyFixtures.read
import com.huntercoles.splatman.splat.io.ply.PlyFixtures.seededCloud
import com.huntercoles.splatman.splat.io.ply.PlyFixtures.trickle
import com.huntercoles.splatman.splat.io.ply.PlyFixtures.write
import com.huntercoles.splatman.splat.model.SplatCloud
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.junit.jupiter.api.Test

class PlyRoundTripTest {

    private fun assertBitExact(expected: SplatCloud, actual: SplatCloud) {
        assertEquals(expected.count, actual.count)
        assertEquals(expected.shDegree, actual.shDegree)
        assertTrue(expected.positions.contentEquals(actual.positions), "positions")
        assertTrue(expected.logScales.contentEquals(actual.logScales), "logScales")
        assertTrue(expected.rotations.contentEquals(actual.rotations), "rotations")
        assertTrue(expected.opacityLogits.contentEquals(actual.opacityLogits), "opacityLogits")
        assertTrue(expected.shDc.contentEquals(actual.shDc), "shDc")
        assertTrue(expected.shRest.contentEquals(actual.shRest), "shRest")
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 1, 2, 3])
    fun `write then read is bit-exact`(degree: Int) {
        val cloud = seededCloud(count = 257, shDegree = degree, seed = 31 + degree)
        assertBitExact(cloud, read(write(cloud)))
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 3])
    fun `reads a stream that delivers a few bytes at a time`(degree: Int) {
        val cloud = seededCloud(count = 40, shDegree = degree, seed = 7)
        assertBitExact(cloud, PlyReader.read(trickle(write(cloud))))
    }

    @Test
    fun `a cloud larger than one chunk round-trips`() {
        val cloud = seededCloud(count = 5000, shDegree = 3, seed = 99) // 5000 * 248 B > the 256 KiB chunk
        assertBitExact(cloud, read(write(cloud)))
    }

    @Test
    fun `an empty cloud round-trips`() {
        val cloud = seededCloud(count = 0, shDegree = 2, seed = 1)
        assertBitExact(cloud, read(write(cloud)))
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 1, 2, 3])
    fun `writer output is byte-identical across runs and after a round trip`(degree: Int) {
        val cloud = seededCloud(count = 64, shDegree = degree, seed = 5)
        val first = write(cloud)
        assertArrayEquals(first, write(cloud))
        assertArrayEquals(first, write(read(first)))
    }

    @Test
    fun `writer does not modify the cloud`() {
        val cloud = seededCloud(count = 10, shDegree = 1, seed = 3)
        val positions = cloud.positions.copyOf()
        val rotations = cloud.rotations.copyOf()
        val rest = cloud.shRest.copyOf()
        write(cloud)
        assertArrayEquals(positions, cloud.positions)
        assertArrayEquals(rotations, cloud.rotations)
        assertArrayEquals(rest, cloud.shRest)
    }
}
