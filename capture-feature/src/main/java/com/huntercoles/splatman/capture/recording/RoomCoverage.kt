package com.huntercoles.splatman.capture.recording

import com.huntercoles.splatman.splat.math.Quat
import com.huntercoles.splatman.splat.math.Vec3
import com.huntercoles.splatman.splat.model.CameraPose
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * The room gauge's cells (ADR 0013 section 2): [seen] where one keyframe looked, [covered] where two keyframes
 * [RoomCoverage.BASELINE_M] apart looked. Covered implies Seen. Immutable.
 */
data class RoomCells(val seen: CoverageCells = CoverageCells(), val covered: CoverageCells = CoverageCells()) {
    /** Whole percent Covered (Seen-only cells do not count). */
    val percent: Int get() = covered.percent

    /**
     * True once Finish unlocks (ADR 0013 section 3): [RoomCoverage.READY_CELLS] Covered cells, of them
     * [RoomCoverage.WALLS_DONE] in [CoverageBand.LEVEL], and a walked path of [RoomCoverage.MIN_PATH_M].
     */
    fun ready(pathM: Float): Boolean =
        covered.total >= RoomCoverage.READY_CELLS &&
            covered.count(CoverageBand.LEVEL) >= RoomCoverage.WALLS_DONE &&
            pathM >= RoomCoverage.MIN_PATH_M
}

/**
 * Which way each keyframe looked, from where (ADR 0013 sections 1 and 2): every keyframe's viewing direction
 * `R * (0, 0, -1)` falls in one of [Coverage.SEGMENTS] headings x 3 pitch bands. The cell is Seen at once, and
 * Covered once a keyframe in it was taken at least [BASELINE_M] from one of the cell's anchors (up to [MAX_ANCHORS],
 * each at least [ANCHOR_M] from the others). Spinning on the spot therefore only ever makes cells Seen. A pose with a
 * non-finite rotation or position counts for nothing. Not thread-safe.
 */
class RoomCoverage {
    /** What is Seen and Covered so far. */
    var cells: RoomCells = RoomCells()
        private set

    private val anchors = FloatArray(Coverage.CELLS * MAX_ANCHORS * 3)
    private val anchorCount = IntArray(Coverage.CELLS)

    /** Counts a keyframe taken at [pose] (`T_W_Cgl`); false when it fell in no cell. */
    fun add(pose: CameraPose): Boolean {
        val p = pose.translationM
        if (!p.x.isFinite() || !p.y.isFinite() || !p.z.isFinite()) return false
        val cell = cellOf(pose.rotation) ?: return false
        val index = cell.band.ordinal * Coverage.SEGMENTS + cell.segment
        val count = anchorCount[index]
        var far = false
        var nearAnchor = false
        for (i in 0 until count) {
            val d2 = distanceSquared(index, i, p)
            if (d2 >= BASELINE_M * BASELINE_M) far = true
            if (d2 < ANCHOR_M * ANCHOR_M) nearAnchor = true
        }
        if (!nearAnchor && count < MAX_ANCHORS) {
            val o = (index * MAX_ANCHORS + count) * 3
            anchors[o] = p.x
            anchors[o + 1] = p.y
            anchors[o + 2] = p.z
            anchorCount[index] = count + 1
        }
        val seen = cells.seen.with(cell.band, cell.segment)
        val covered = if (far) cells.covered.with(cell.band, cell.segment) else cells.covered
        cells = RoomCells(seen, covered)
        return true
    }

    /** Forgets every cell and anchor. */
    fun reset() {
        cells = RoomCells()
        anchorCount.fill(0)
    }

    private fun distanceSquared(index: Int, anchor: Int, p: Vec3): Float {
        val o = (index * MAX_ANCHORS + anchor) * 3
        val dx = p.x - anchors[o]
        val dy = p.y - anchors[o + 1]
        val dz = p.z - anchors[o + 2]
        return dx * dx + dy * dy + dz * dz
    }

    companion object {
        /** Two keyframes this far apart (3D), metres, in one cell make it Covered. */
        const val BASELINE_M = 0.5f

        /** A keyframe at least this far from every anchor of its cell becomes one, metres. */
        const val ANCHOR_M = BASELINE_M / 2f

        /** Anchors kept per cell. */
        const val MAX_ANCHORS = 4

        /** Covered cells Finish needs: 40 % of [Coverage.CELLS]. */
        const val READY_CELLS = 29

        /** Covered [CoverageBand.LEVEL] cells (walls) Finish needs. */
        const val WALLS_DONE = Coverage.BAND_DONE

        /** Walked path Finish needs, metres. */
        const val MIN_PATH_M = 2.0f

        /** Covered cells of a band after which the guidance moves on to the next step. */
        const val STEP_BAND = 12

        /** Looking further down than this is the floor band, degrees. */
        const val LOW_BELOW_DEG = -25f

        /** Looking further up than this is the ceiling band, degrees. */
        const val HIGH_ABOVE_DEG = 25f

        /** Steeper than this up or down: no useful heading, degrees. */
        const val MAX_PITCH_DEG = 80f

        private const val DEG = (180.0 / PI).toFloat()

        /** The unit viewing direction `R * (0, 0, -1)` of [rotation], or null when it is not finite or zero. */
        fun forwardOf(rotation: Quat): Vec3? {
            val n = rotation.normSquared()
            if (!n.isFinite() || n < 1e-6f) return null
            val f = rotation.rotate(Vec3(0f, 0f, -1f))
            val length = sqrt(f.x * f.x + f.y * f.y + f.z * f.z)
            if (!length.isFinite() || length <= 1e-6f) return null
            return Vec3(f.x / length, f.y / length, f.z / length)
        }

        /** Heading of direction [f], degrees in [0, 360): 0 along world +Z, growing towards +X. */
        fun headingDeg(f: Vec3): Float {
            val a = atan2(f.x, f.z) * DEG
            return if (a < 0f) a + 360f else a
        }

        /** The heading segment of direction [f]. */
        fun segmentOf(f: Vec3): Int =
            floor(headingDeg(f) / (360f / Coverage.SEGMENTS)).toInt().coerceIn(0, Coverage.SEGMENTS - 1)

        /** The cell a camera at [rotation] looks into, or null when the rotation is not finite or too steep. */
        fun cellOf(rotation: Quat): CoverageCell? {
            val f = forwardOf(rotation) ?: return null
            val pitch = asin(f.y.coerceIn(-1f, 1f)) * DEG
            val band = when {
                pitch > MAX_PITCH_DEG || pitch < -MAX_PITCH_DEG -> return null
                pitch < LOW_BELOW_DEG -> CoverageBand.LOW
                pitch > HIGH_ABOVE_DEG -> CoverageBand.HIGH
                else -> CoverageBand.LEVEL
            }
            return CoverageCell(band, segmentOf(f))
        }
    }
}

/** What the room gauge asks the user to do next (ADR 0013 section 3). */
enum class RoomStep { WALK, LOOK_UP, LOOK_DOWN, READY }

/**
 * The guidance step for [cells] after a walk of [pathM] metres: [RoomStep.READY] once Finish unlocks, else
 * [RoomStep.WALK] until [RoomCoverage.STEP_BAND] walls are Covered, then [RoomStep.LOOK_UP] until as many ceiling
 * cells are, then [RoomStep.LOOK_DOWN].
 */
fun roomStep(cells: RoomCells, pathM: Float): RoomStep = when {
    cells.ready(pathM) -> RoomStep.READY
    cells.covered.count(CoverageBand.LEVEL) < RoomCoverage.STEP_BAND -> RoomStep.WALK
    cells.covered.count(CoverageBand.HIGH) < RoomCoverage.STEP_BAND -> RoomStep.LOOK_UP
    else -> RoomStep.LOOK_DOWN
}

/** Where a missing arc lies relative to the way the user faces. */
enum class RoomSide { AHEAD, LEFT, RIGHT, BEHIND, ALL_ROUND }

/** The largest run of not-Covered headings: [length] segments of [band] from [start], on [side]. */
data class RoomMissing(val band: CoverageBand, val start: Int, val length: Int, val side: RoomSide)

/** The room gauge's content description in parts: "31 percent covered. Missing: ceiling, behind you". */
data class RoomDescription(val percent: Int, val missing: RoomMissing?)

/**
 * Describes [cells] for a user facing heading segment [facingSegment]: the largest circular run of cells not Covered
 * (Seen-only cells count as missing; ties go to the higher band, then the lower start), its side within 45 degrees of
 * the facing heading AHEAD, beyond 135 BEHIND, else LEFT or RIGHT (growing heading is the user's right); missing is
 * null when every cell is Covered.
 */
fun roomDescription(cells: RoomCells, facingSegment: Int): RoomDescription {
    val arc = largestMissingArc(cells.covered, facingSegment)
    val missing = arc?.let {
        val side = when (it.side) {
            ArcSide.NEAR -> RoomSide.AHEAD
            ArcSide.FAR -> RoomSide.BEHIND
            ArcSide.LEFT -> RoomSide.LEFT
            ArcSide.RIGHT -> RoomSide.RIGHT
            ArcSide.ALL_ROUND -> RoomSide.ALL_ROUND
        }
        RoomMissing(it.band, it.start, it.length, side)
    }
    return RoomDescription(cells.percent, missing)
}
