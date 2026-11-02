package com.huntercoles.splatman.capture.recording

import com.huntercoles.splatman.splat.math.Vec3
import com.huntercoles.splatman.splat.model.CameraPose
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.sqrt

/** The three height bands of the coverage ring, lowest first (drawn outer = low, middle = level, inner = high). */
enum class CoverageBand { LOW, LEVEL, HIGH }

/**
 * Which of the [Coverage.SEGMENTS] azimuth segments of each [CoverageBand] some keyframe saw the subject from: one
 * bit per segment. Immutable.
 */
data class CoverageCells(val low: Int = 0, val level: Int = 0, val high: Int = 0) {
    /** The segment bits of [band]. */
    fun mask(band: CoverageBand): Int = when (band) {
        CoverageBand.LOW -> low
        CoverageBand.LEVEL -> level
        CoverageBand.HIGH -> high
    }

    /** True when [segment] of [band] is covered. */
    fun covered(band: CoverageBand, segment: Int): Boolean = mask(band) and (1 shl segment) != 0

    /** Covered segments in [band]. */
    fun count(band: CoverageBand): Int = Integer.bitCount(mask(band))

    /** Covered cells over all bands. */
    val total: Int get() = Integer.bitCount(low) + Integer.bitCount(level) + Integer.bitCount(high)

    /** Covered share of all [Coverage.CELLS], 0..1. */
    val fraction: Float get() = total.toFloat() / Coverage.CELLS

    /** Whole percent covered, rounded down (what the ring's centre shows). */
    val percent: Int get() = total * 100 / Coverage.CELLS

    /** Bands with at least one covered segment. */
    val bandsTouched: Int get() = CoverageBand.entries.count { mask(it) != 0 }

    /** True once the scan may finish: [Coverage.READY_FRACTION] covered over at least [Coverage.READY_BANDS] bands. */
    val ready: Boolean get() = fraction >= Coverage.READY_FRACTION && bandsTouched >= Coverage.READY_BANDS

    /** These cells plus [segment] of [band]. */
    fun with(band: CoverageBand, segment: Int): CoverageCells {
        val bit = 1 shl segment
        return when (band) {
            CoverageBand.LOW -> copy(low = low or bit)
            CoverageBand.LEVEL -> copy(level = level or bit)
            CoverageBand.HIGH -> copy(high = high or bit)
        }
    }
}

/** Where a camera at [segment] (azimuth) and [band] (height) sits relative to the subject. */
data class CoverageCell(val band: CoverageBand, val segment: Int)

/**
 * How far round the subject the scan has gone: every keyframe's camera position, seen from the subject, falls in one
 * of [SEGMENTS] azimuth segments (about world +Y) and one of three height bands. A camera nearer than
 * [MIN_DISTANCE_M] to the subject or more than [MAX_ELEVATION_DEG] above or below it counts for nothing. Not
 * thread-safe.
 */
class Coverage(subject: Vec3? = null) {
    /** The subject point, world frame W; null until set, and then nothing counts. */
    var subject: Vec3? = subject
        private set

    /** What is covered so far. */
    var cells: CoverageCells = CoverageCells()
        private set

    /** Covered share, 0..1. */
    val fraction: Float get() = cells.fraction

    /** Counts a keyframe taken at [pose] (`T_W_Cgl`); false when it fell in no cell. */
    fun add(pose: CameraPose): Boolean {
        val s = subject ?: return false
        val cell = cellOf(pose.translationM, s) ?: return false
        cells = cells.with(cell.band, cell.segment)
        return true
    }

    /** Forgets every cell and aims at [subject]. */
    fun reset(subject: Vec3?) {
        this.subject = subject
        cells = CoverageCells()
    }

    companion object {
        /** Azimuth segments of 15 degrees. */
        const val SEGMENTS = 24

        /** Segments over all three bands. */
        const val CELLS = SEGMENTS * 3

        /** A band whose segments are this many or more covered counts as done in the guidance sequence. */
        const val BAND_DONE = 18

        /** The Finish threshold (placeholder until the trainer ticket sets it): 40 % ... */
        const val READY_FRACTION = 0.4f

        /** ... over at least this many bands. */
        const val READY_BANDS = 2

        /** Below this elevation the camera is in the low band, degrees. */
        const val LOW_BELOW_DEG = -10f

        /** Above this elevation the camera is in the high band, degrees. */
        const val HIGH_ABOVE_DEG = 30f

        /** Straight above or below: no useful azimuth, degrees. */
        const val MAX_ELEVATION_DEG = 80f

        /** Closer than this to the subject, metres, a camera counts for nothing. */
        const val MIN_DISTANCE_M = 0.05f

        private const val DEG = (180.0 / PI).toFloat()

        /**
         * Azimuth of [camera] round [subject], degrees in [0, 360): 0 along world +Z, growing towards +X (to the right
         * of someone at azimuth 0 looking at the subject).
         */
        fun azimuthDeg(camera: Vec3, subject: Vec3): Float {
            val a = atan2(camera.x - subject.x, camera.z - subject.z) * DEG
            return if (a < 0f) a + 360f else a
        }

        /** The azimuth segment of [camera] round [subject]. */
        fun segmentOf(camera: Vec3, subject: Vec3): Int =
            floor(azimuthDeg(camera, subject) / (360f / SEGMENTS)).toInt().coerceIn(0, SEGMENTS - 1)

        /** The cell of [camera] round [subject], or null when too close or too steep. */
        fun cellOf(camera: Vec3, subject: Vec3): CoverageCell? {
            val d = camera - subject
            val horizontal = sqrt(d.x * d.x + d.z * d.z)
            if (d.length() < MIN_DISTANCE_M) return null
            val elevation = atan2(d.y, horizontal) * DEG
            val band = when {
                elevation > MAX_ELEVATION_DEG || elevation < -MAX_ELEVATION_DEG -> return null
                elevation < LOW_BELOW_DEG -> CoverageBand.LOW
                elevation > HIGH_ABOVE_DEG -> CoverageBand.HIGH
                else -> CoverageBand.LEVEL
            }
            return CoverageCell(band, segmentOf(camera, subject))
        }
    }
}

/** Where a missing arc lies, seen by the user standing at their current segment and facing the subject. */
enum class ArcSide { NEAR, LEFT, RIGHT, FAR, ALL_ROUND }

/** The largest run of uncovered segments: [length] segments of [band] starting at [start], on [side]. */
data class MissingArc(val band: CoverageBand, val start: Int, val length: Int, val side: ArcSide)

/** The ring's content description in parts: "46 percent covered. Missing: high, left side". */
data class RingDescription(val percent: Int, val missing: MissingArc?)

/** Describes [cells] for the user standing in segment [facing]. */
fun ringDescription(cells: CoverageCells, facing: Int): RingDescription =
    RingDescription(cells.percent, largestMissingArc(cells, facing))

/**
 * The longest circular run of uncovered segments over all bands (ties go to the higher band, then the lower start),
 * or null when everything is covered. Its side is where the run's middle lies relative to segment [facing]: within
 * 45 degrees NEAR, beyond 135 FAR, else LEFT or RIGHT (growing azimuth is the user's right).
 */
fun largestMissingArc(cells: CoverageCells, facing: Int): MissingArc? {
    val n = Coverage.SEGMENTS
    var best: MissingArc? = null
    for (band in CoverageBand.entries.reversed()) {
        val mask = cells.mask(band)
        if (mask == 0) {
            if (best == null || n > best.length) best = MissingArc(band, 0, n, ArcSide.ALL_ROUND)
            continue
        }
        // Start each run just after a covered segment, so a run never splits across segment 0.
        for (start in 0 until n) {
            if (mask and (1 shl start) != 0) continue
            if (mask and (1 shl ((start + n - 1) % n)) == 0) continue
            var length = 0
            while (length < n && mask and (1 shl ((start + length) % n)) == 0) length++
            if (best == null || length > best.length) best = MissingArc(band, start, length, sideOf(start, length, facing))
        }
    }
    return best
}

private fun sideOf(start: Int, length: Int, facing: Int): ArcSide {
    val n = Coverage.SEGMENTS
    val middle = start + (length - 1) / 2f
    var d = (middle - facing) % n
    if (d > n / 2f) d -= n
    if (d <= -n / 2f) d += n
    val quarter = n / 8f // 45 degrees
    return when {
        kotlin.math.abs(d) <= quarter -> ArcSide.NEAR
        kotlin.math.abs(d) >= n / 2f - quarter -> ArcSide.FAR
        d > 0 -> ArcSide.RIGHT
        else -> ArcSide.LEFT
    }
}
