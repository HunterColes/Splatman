package com.huntercoles.splatman.viewer.export.video

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VideoSpecTest {

    @Test
    fun `1080 square at 8 Mbit for 6 s is about 6 MB`() {
        val spec = VideoSpec.choose(supports = { true })!!
        assertEquals(VideoSpec(1080, 8_000_000), spec)
        assertEquals(6_000_000L, spec.estimatedBytes)
    }

    @Test
    fun `a smaller encoder gets its largest square and a bitrate scaled by area`() {
        val spec = VideoSpec.choose(supports = { it <= 720 })!!
        assertEquals(720, spec.size)
        assertEquals(Math.round(8_000_000.0 * 720 * 720 / (1080 * 1080)).toInt(), spec.bitrate)
        assertEquals(spec.bitrate.toLong() * 6 / 8, spec.estimatedBytes)
    }

    @Test
    fun `the bitrate stays inside the encoder's range`() {
        val spec = VideoSpec.choose(supports = { true }, clampBitrate = { it.coerceAtMost(5_000_000) })!!
        assertEquals(5_000_000, spec.bitrate)
        assertEquals(3_750_000L, spec.estimatedBytes)
    }

    @Test
    fun `candidates are 1080 then multiples of 16, largest first`() {
        val c = VideoSpec.candidates()
        assertEquals(1080, c.first())
        assertEquals(1072, c[1])
        assertTrue(c.drop(1).all { it % 16 == 0 && it >= VideoSpec.MIN_SIZE })
        assertEquals(c.sortedDescending(), c)
    }

    @Test
    fun `no square fits, no video`() {
        assertNull(VideoSpec.choose(supports = { false }))
    }
}
