package com.huntercoles.splatman.capture.recording

import com.huntercoles.splatman.splat.math.Quat
import com.huntercoles.splatman.splat.math.Vec3
import com.huntercoles.splatman.splat.model.CameraPose
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RoomCoverageTest {
    /** A camera looking at [headingDeg] (0 along +Z, towards +X) and [pitchDeg] (up positive). */
    private fun look(headingDeg: Float, pitchDeg: Float = 0f): Quat {
        val yaw = Quat.fromAxisAngle(Vec3(0f, 1f, 0f), Math.toRadians(headingDeg - 180.0).toFloat())
        val pitch = Quat.fromAxisAngle(Vec3(1f, 0f, 0f), Math.toRadians(pitchDeg.toDouble()).toFloat())
        return yaw * pitch
    }

    private fun pose(rotation: Quat, x: Float = 0f, y: Float = 0f, z: Float = 0f) = CameraPose(rotation, Vec3(x, y, z))

    private fun bits(n: Int) = (1 shl n) - 1

    private val all = bits(Coverage.SEGMENTS)

    private fun covered(low: Int = 0, level: Int = 0, high: Int = 0): RoomCells {
        val c = CoverageCells(bits(low), bits(level), bits(high))
        return RoomCells(c, c)
    }

    @Test
    fun `facing +Z is heading 0, segment 0, walls`() {
        val q = Quat(0f, 0f, 1f, 0f) // 180 degrees about +Y: (0, 0, -1) -> (0, 0, 1)
        val f = RoomCoverage.forwardOf(q)!!
        assertEquals(0f, RoomCoverage.headingDeg(f), 1e-4f)
        assertEquals(CoverageCell(CoverageBand.LEVEL, 0), RoomCoverage.cellOf(q))
    }

    @Test
    fun `facing +X is heading 90, growing heading turns towards +X`() {
        val q = Quat.fromAxisAngle(Vec3(0f, 1f, 0f), (-Math.PI / 2).toFloat())
        assertEquals(90f, RoomCoverage.headingDeg(RoomCoverage.forwardOf(q)!!), 1e-3f)
        assertEquals(CoverageCell(CoverageBand.LEVEL, 6), RoomCoverage.cellOf(look(90.5f)))
        assertEquals(CoverageCell(CoverageBand.LEVEL, 0), RoomCoverage.cellOf(look(14.9f)))
        assertEquals(CoverageCell(CoverageBand.LEVEL, 1), RoomCoverage.cellOf(look(15.1f)))
        assertEquals(CoverageCell(CoverageBand.LEVEL, 23), RoomCoverage.cellOf(look(-1f)))
    }

    @Test
    fun `pitch bands split at 25 degrees and stop at 80`() {
        assertEquals(CoverageCell(CoverageBand.LOW, 6), RoomCoverage.cellOf(look(97.5f, -30f)))
        assertEquals(CoverageBand.LEVEL, RoomCoverage.cellOf(look(7.5f, -24f))!!.band)
        assertEquals(CoverageBand.LOW, RoomCoverage.cellOf(look(7.5f, -26f))!!.band)
        assertEquals(CoverageBand.LEVEL, RoomCoverage.cellOf(look(7.5f, 24f))!!.band)
        assertEquals(CoverageBand.HIGH, RoomCoverage.cellOf(look(7.5f, 26f))!!.band)
        assertEquals(CoverageBand.HIGH, RoomCoverage.cellOf(look(7.5f, 79f))!!.band)
        assertNull(RoomCoverage.cellOf(look(7.5f, 85f)))
        assertNull(RoomCoverage.cellOf(look(7.5f, -85f)))
        assertFalse(RoomCoverage().add(pose(look(7.5f, 85f))))
    }

    @Test
    fun `spinning in place only makes cells Seen and never moves the percent`() {
        val room = RoomCoverage()
        repeat(3) { turn ->
            for (i in 0 until Coverage.SEGMENTS) {
                for (pitch in floatArrayOf(-40f, 0f, 40f)) {
                    assertTrue(room.add(pose(look(i * 15f + 7.5f + turn, pitch), 1f, 1.5f, -2f)))
                    assertEquals(0, room.cells.percent)
                }
            }
        }
        assertEquals(Coverage.CELLS, room.cells.seen.total)
        assertEquals(0, room.cells.covered.total)
    }

    @Test
    fun `Covered at 0_5 m but not at 0_49 m`() {
        val q = look(7.5f)
        val near = RoomCoverage()
        near.add(pose(q))
        near.add(pose(q, x = 0.49f))
        assertTrue(near.cells.seen.covered(CoverageBand.LEVEL, 0))
        assertFalse(near.cells.covered.covered(CoverageBand.LEVEL, 0))

        val far = RoomCoverage()
        far.add(pose(q))
        far.add(pose(q, y = 0.5f))
        assertTrue(far.cells.covered.covered(CoverageBand.LEVEL, 0))
        assertEquals(1, far.cells.covered.total)
        assertEquals(1, far.cells.percent)
    }

    @Test
    fun `A-B-C walk Covers through the first anchor and Covered never reverts`() {
        val q = look(187.5f, -40f)
        val room = RoomCoverage()
        room.add(pose(q))
        room.add(pose(q, x = 0.3f))
        assertFalse(room.cells.covered.covered(CoverageBand.LOW, 12))
        room.add(pose(q, x = 0.6f))
        assertTrue(room.cells.covered.covered(CoverageBand.LOW, 12))
        room.add(pose(q, x = 0.6f))
        assertTrue(room.cells.covered.covered(CoverageBand.LOW, 12))
        assertEquals(room.cells.covered, room.cells.seen)
    }

    @Test
    fun `a keyframe too near an anchor adds none`() {
        val q = look(7.5f)
        val room = RoomCoverage()
        room.add(pose(q))
        room.add(pose(q, x = 0.24f)) // not an anchor
        room.add(pose(q, x = 0.73f)) // 0.49 from the would-be anchor, 0.73 from A: Covered by A
        assertTrue(room.cells.covered.covered(CoverageBand.LEVEL, 0))

        val other = RoomCoverage()
        other.add(pose(q))
        other.add(pose(q, x = -0.24f)) // not an anchor
        other.add(pose(q, x = 0.27f)) // 0.51 from the would-be anchor, 0.27 from A: not Covered
        assertFalse(other.cells.covered.covered(CoverageBand.LEVEL, 0))
    }

    @Test
    fun `a NaN rotation or position counts for nothing`() {
        val room = RoomCoverage()
        assertFalse(room.add(pose(Quat(Float.NaN, 0f, 0f, 0f))))
        assertFalse(room.add(pose(Quat(0f, 0f, 0f, 0f))))
        assertFalse(room.add(CameraPose(look(7.5f), Vec3(Float.NaN, 0f, 0f))))
        assertEquals(RoomCells(), room.cells)
        room.add(pose(look(7.5f)))
        assertFalse(room.add(CameraPose(look(7.5f), Vec3(Float.POSITIVE_INFINITY, 0f, 0f))))
        assertEquals(0, room.cells.covered.total)
    }

    @Test
    fun `reset forgets cells and anchors`() {
        val q = look(7.5f)
        val room = RoomCoverage()
        room.add(pose(q))
        room.add(pose(q, x = 1f))
        room.reset()
        assertEquals(RoomCells(), room.cells)
        room.add(pose(q, x = 1f))
        assertEquals(0, room.cells.covered.total)
    }

    @Test
    fun `ready needs 29 cells, 18 walls and 2 m walked`() {
        assertTrue(covered(level = 18, high = 11).ready(2.0f))
        assertFalse(covered(level = 18, high = 10).ready(2.0f))
        assertFalse(covered(level = 17, high = 12).ready(2.0f))
        assertTrue(covered(level = 18, low = 11).ready(2.0f))
        assertFalse(covered(level = 18, high = 11).ready(1.99f))
        assertFalse(covered(level = 18, high = 11).ready(Float.NaN))
        val seenOnly = RoomCells(seen = CoverageCells(all, all, all))
        assertFalse(seenOnly.ready(10f))
        assertEquals(29, RoomCoverage.READY_CELLS)
        assertEquals(18, RoomCoverage.WALLS_DONE)
    }

    @Test
    fun `roomStep walks, looks up, looks down, and READY wins`() {
        assertEquals(RoomStep.WALK, roomStep(RoomCells(), 5f))
        assertEquals(RoomStep.WALK, roomStep(covered(level = 11, high = 24), 5f))
        assertEquals(RoomStep.LOOK_UP, roomStep(covered(level = 12), 5f))
        assertEquals(RoomStep.LOOK_UP, roomStep(covered(level = 12, high = 11), 5f))
        assertEquals(RoomStep.LOOK_DOWN, roomStep(covered(level = 12, high = 12), 5f))
        assertEquals(RoomStep.LOOK_UP, roomStep(covered(level = 18, high = 11), 1.99f))
        assertEquals(RoomStep.READY, roomStep(covered(level = 18, high = 11), 2f))
        assertEquals(RoomStep.READY, roomStep(covered(level = 18, low = 11), 2f))
    }

    @Test
    fun `description names the largest not-Covered arc relative to the facing heading`() {
        val gap = all and (0b111 shl 11).inv() // walls missing at segments 11..13
        val c = CoverageCells(all, gap, all)
        val cells = RoomCells(c, c)
        val ahead = roomDescription(cells, 12)
        assertEquals(RoomMissing(CoverageBand.LEVEL, 11, 3, RoomSide.AHEAD), ahead.missing)
        assertEquals(cells.percent, ahead.percent)
        assertEquals(RoomSide.BEHIND, roomDescription(cells, 0).missing!!.side)
        assertEquals(RoomSide.RIGHT, roomDescription(cells, 6).missing!!.side)
        assertEquals(RoomSide.LEFT, roomDescription(cells, 18).missing!!.side)
    }

    @Test
    fun `Seen-only counts as missing, nothing missing is null`() {
        val seenOnly = RoomCells(seen = CoverageCells(all, all, all))
        val d = roomDescription(seenOnly, 0)
        assertEquals(0, d.percent)
        assertEquals(RoomMissing(CoverageBand.HIGH, 0, 24, RoomSide.ALL_ROUND), d.missing)

        val full = CoverageCells(all, all, all)
        val done = roomDescription(RoomCells(full, full), 5)
        assertEquals(100, done.percent)
        assertNull(done.missing)
    }
}
