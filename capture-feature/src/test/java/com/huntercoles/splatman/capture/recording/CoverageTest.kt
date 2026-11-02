package com.huntercoles.splatman.capture.recording

import com.huntercoles.splatman.splat.math.Quat
import com.huntercoles.splatman.splat.math.Vec3
import com.huntercoles.splatman.splat.model.CameraPose
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.cos
import kotlin.math.sin

class CoverageTest {
    private val subject = Vec3(1f, 0.5f, -2f)

    /** A camera [distance] from [subject] at [azimuthDeg] and [elevationDeg]; the rotation does not matter. */
    private fun at(azimuthDeg: Float, elevationDeg: Float, distance: Float = 1f): CameraPose {
        val a = Math.toRadians(azimuthDeg.toDouble())
        val e = Math.toRadians(elevationDeg.toDouble())
        val offset = Vec3(
            (distance * cos(e) * sin(a)).toFloat(),
            (distance * sin(e)).toFloat(),
            (distance * cos(e) * cos(a)).toFloat(),
        )
        return CameraPose(Quat.IDENTITY, subject + offset)
    }

    private val all = (1 shl Coverage.SEGMENTS) - 1

    @Test
    fun `azimuth picks one of 24 segments of 15 degrees, growing towards +X`() {
        assertEquals(0, Coverage.segmentOf(at(0f, 0f).translationM, subject))
        assertEquals(0, Coverage.segmentOf(at(14.9f, 0f).translationM, subject))
        assertEquals(1, Coverage.segmentOf(at(15.1f, 0f).translationM, subject))
        assertEquals(6, Coverage.segmentOf(at(90.5f, 0f).translationM, subject))
        assertEquals(23, Coverage.segmentOf(at(-1f, 0f).translationM, subject))
        assertEquals(90f, Coverage.azimuthDeg(subject + Vec3(1f, 0f, 0f), subject), 1e-4f)
    }

    @Test
    fun `elevation picks the band, and too steep or too close counts for nothing`() {
        assertEquals(CoverageBand.LOW, Coverage.cellOf(at(0f, -20f).translationM, subject)?.band)
        assertEquals(CoverageBand.LEVEL, Coverage.cellOf(at(0f, -9f).translationM, subject)?.band)
        assertEquals(CoverageBand.LEVEL, Coverage.cellOf(at(0f, 29f).translationM, subject)?.band)
        assertEquals(CoverageBand.HIGH, Coverage.cellOf(at(0f, 31f).translationM, subject)?.band)
        assertEquals(CoverageBand.HIGH, Coverage.cellOf(at(0f, 79f).translationM, subject)?.band)
        assertNull(Coverage.cellOf(at(0f, 85f).translationM, subject))
        assertNull(Coverage.cellOf(at(0f, -85f).translationM, subject))
        assertNull(Coverage.cellOf(at(0f, 0f, distance = 0.01f).translationM, subject))
    }

    @Test
    fun `a full level turn fills the level band, a third of the ring`() {
        val c = Coverage(subject)
        for (i in 0 until 48) c.add(at(i * 7.5f, 0f))
        assertEquals(CoverageCells(level = all), c.cells)
        assertEquals(1f / 3, c.fraction, 1e-6f)
        assertEquals(33, c.cells.percent)
    }

    @Test
    fun `nothing counts without a subject, and reset forgets`() {
        val c = Coverage()
        assertFalse(c.add(at(0f, 0f)))
        c.reset(subject)
        assertTrue(c.add(at(0f, 0f)))
        assertEquals(1, c.cells.total)
        c.reset(subject)
        assertEquals(0, c.cells.total)
    }

    @Test
    fun `ready needs 40 percent over at least two bands`() {
        // 29 of 72 cells is 40.3 %, all in one band but for one.
        val oneBand = CoverageCells(level = all, high = 0b11111)
        assertTrue(oneBand.fraction >= Coverage.READY_FRACTION)
        assertTrue(oneBand.ready)
        assertFalse(CoverageCells(level = all, high = 0b1111).ready) // 28 / 72 = 38.9 %
        // Enough cells in a single band can never be ready: a band is a third.
        assertFalse(CoverageCells(level = all).ready)
        assertEquals(2, oneBand.bandsTouched)
    }

    @Test
    fun `the largest missing arc names its band and side`() {
        // Facing segment 0; the high band misses segments 4..9 (right side), the level band 20..22 (left side).
        val level = all and (0b111 shl 20).inv()
        val high = all and (0b111111 shl 4).inv()
        val cells = CoverageCells(low = all, level = level, high = high)
        assertEquals(MissingArc(CoverageBand.HIGH, 4, 6, ArcSide.RIGHT), largestMissingArc(cells, facing = 0))
        // Seen from segment 12, the same arc is on the user's left.
        assertEquals(ArcSide.LEFT, largestMissingArc(cells, facing = 12)?.side)
        assertEquals(ArcSide.NEAR, largestMissingArc(cells, facing = 7)?.side)
        assertEquals(ArcSide.FAR, largestMissingArc(cells, facing = 19)?.side)
    }

    @Test
    fun `a missing arc that wraps past segment 0 is one arc`() {
        // Level misses 22, 23, 0, 1, 2: five segments centred on segment 0.
        val level = all and (0b11 shl 22).inv() and 0b111.inv()
        val arc = largestMissingArc(CoverageCells(low = all, level = level, high = all), facing = 0)
        assertEquals(MissingArc(CoverageBand.LEVEL, 22, 5, ArcSide.NEAR), arc)
    }

    @Test
    fun `an empty band is missing all the way round, the higher band first`() {
        val arc = largestMissingArc(CoverageCells(level = all), facing = 3)
        assertEquals(MissingArc(CoverageBand.HIGH, 0, Coverage.SEGMENTS, ArcSide.ALL_ROUND), arc)
    }

    @Test
    fun `a full ring has nothing missing`() {
        val d = ringDescription(CoverageCells(all, all, all), facing = 0)
        assertEquals(RingDescription(100, null), d)
    }

    @Test
    fun `the description carries the percent and the arc`() {
        val d = ringDescription(CoverageCells(low = all, level = all and 1.inv()), facing = 0)
        assertEquals(65, d.percent) // 47 of 72
        assertEquals(MissingArc(CoverageBand.HIGH, 0, Coverage.SEGMENTS, ArcSide.ALL_ROUND), d.missing)
    }
}
