package com.huntercoles.splatman.splat.capture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ScanModeTest {

    @Test
    fun `keys are object and room`() {
        assertEquals("object", ScanMode.OBJECT.key)
        assertEquals("room", ScanMode.ROOM.key)
    }

    @Test
    fun `fromKey reads every key back`() {
        ScanMode.entries.forEach { assertEquals(it, ScanMode.fromKey(it.key)) }
    }

    @Test
    fun `null or unknown keys read as OBJECT`() {
        assertEquals(ScanMode.OBJECT, ScanMode.fromKey(null))
        assertEquals(ScanMode.OBJECT, ScanMode.fromKey("hall"))
        assertEquals(ScanMode.OBJECT, ScanMode.fromKey("ROOM"))
        assertEquals(ScanMode.OBJECT, ScanMode.fromKey(""))
    }

    @Test
    fun `ROOM keyframe policy is the room budget and the default is unchanged`() {
        val room = KeyframePolicy.ROOM
        assertEquals(0.10f, room.minTranslationM)
        assertEquals(10f, room.minRotationDeg)
        assertEquals(200, room.maxKeyframes)
        assertEquals(KeyframePolicy().sharpnessWindow, room.sharpnessWindow)
        assertEquals(KeyframePolicy().sharpnessQuantile, room.sharpnessQuantile)
        assertEquals(KeyframePolicy(0.05f, 10f, 30, 0.25f, 120), KeyframePolicy())
    }
}
