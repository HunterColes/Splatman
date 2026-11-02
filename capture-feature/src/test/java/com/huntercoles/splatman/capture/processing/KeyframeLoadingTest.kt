package com.huntercoles.splatman.capture.processing

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class KeyframeLoadingTest {

    @Test
    fun `training size keeps aspect on the long side and never upscales`() {
        assertEquals(320 to 180, trainingSize(1280, 720, 320))
        assertEquals(180 to 320, trainingSize(720, 1280, 320))
        assertEquals(200 to 100, trainingSize(200, 100, 320))
        assertEquals(320 to 1, trainingSize(4000, 1, 320))
    }

    @Test
    fun `sample size is the largest power of two that stays at or above the target`() {
        assertEquals(4, sampleSize(1280, 720, 320, 180))
        assertEquals(2, sampleSize(1280, 720, 321, 181))
        assertEquals(1, sampleSize(200, 100, 200, 100))
    }

    @Test
    fun `argb pixels become rgba bytes`() {
        val rgba = argbToRgba(intArrayOf(0x80112233.toInt(), 0xFFFFFFFF.toInt()))
        assertArrayEquals(byteArrayOf(0x11, 0x22, 0x33, 0x80.toByte(), -1, -1, -1, -1), rgba)
    }
}
