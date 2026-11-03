package com.huntercoles.splatman.capture.recording

import com.huntercoles.splatman.splat.math.Quat
import com.huntercoles.splatman.splat.math.Vec3
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.floor

/**
 * The coverage ring without poses (#214): each frame's viewing direction (device -Z) from the accumulated gyro
 * [orientation][add] gives the azimuth segment by yaw round gravity, relative to the first frame after [reset], and
 * the accelerometer's gravity gives the height band by pitch, with [Coverage]'s segment count and band thresholds.
 * Without gravity every frame counts as LEVEL and yaw is taken round the last known up (the start frame's +Y before
 * any). Not thread-safe.
 */
class GyroCoverage {
    /** What is covered so far. */
    var cells: CoverageCells = CoverageCells()
        private set

    /** The segment of the latest counted frame; unchanged by a frame that falls in no cell. */
    var facingSegment: Int = 0
        private set

    /** Covered share, 0..1. */
    val fraction: Float get() = cells.fraction

    /** The first counted frame's viewing direction, start frame; null until one is counted. */
    private var reference: Vec3? = null

    /** World up in the start frame, from the last valid gravity. */
    private var up: Vec3 = Vec3.UNIT_Y

    /** Forgets every cell and the reference orientation. */
    fun reset() {
        cells = CoverageCells()
        reference = null
        up = Vec3.UNIT_Y
        facingSegment = 0
    }

    /**
     * Counts a frame at [orientation] (device frame to start frame, as GyroIntegrator gives it) with [gravity], the
     * accelerometer's vector in the device frame (Android convention: pointing up at rest), or null when unknown.
     * Zero-length or non-finite gravity counts as unknown. False when the frame fell in no cell (looking more than
     * [Coverage.MAX_ELEVATION_DEG] up or down, or a degenerate orientation).
     */
    fun add(orientation: Quat, gravity: Vec3?): Boolean {
        val n = orientation.norm()
        if (!n.isFinite() || n < MIN_NORM) return false
        val q = orientation.normalized()
        val g = gravity
            ?.takeIf { it.x.isFinite() && it.y.isFinite() && it.z.isFinite() && it.lengthSquared() > EPS }
            ?.normalized()
        val band = if (g == null) {
            CoverageBand.LEVEL
        } else {
            // Elevation of the viewing direction (device -Z) against up (the normalised gravity), both device frame.
            val elevation = asin((-g.z).coerceIn(-1f, 1f)) * DEG
            up = q.rotate(g).normalized()
            when {
                elevation > Coverage.MAX_ELEVATION_DEG || elevation < -Coverage.MAX_ELEVATION_DEG -> return false
                elevation < Coverage.LOW_BELOW_DEG -> CoverageBand.LOW
                elevation > Coverage.HIGH_ABOVE_DEG -> CoverageBand.HIGH
                else -> CoverageBand.LEVEL
            }
        }
        val look = q.rotate(VIEW)
        val view = horizontal(look) ?: return false
        val ref = reference?.let { horizontal(it) }
        if (reference == null) reference = look
        val yawDeg = if (ref == null) 0f else atan2(up dot (ref cross view), ref dot view) * DEG
        val segment = segmentOf(yawDeg)
        cells = cells.with(band, segment)
        facingSegment = segment
        return true
    }

    /** [v] projected on the plane normal to [up], unit length, or null when it is (nearly) along [up]. */
    private fun horizontal(v: Vec3): Vec3? {
        val h = v - up * (v dot up)
        return if (h.lengthSquared() < EPS) null else h.normalized()
    }

    companion object {
        private val VIEW = Vec3(0f, 0f, -1f)
        private const val EPS = 1e-6f
        private const val MIN_NORM = 1e-3f
        private const val DEG = (180.0 / PI).toFloat()

        /** The azimuth segment of a yaw in degrees, any range: wraps at 360 so segment 0 starts at the reference. */
        internal fun segmentOf(yawDeg: Float): Int {
            if (!yawDeg.isFinite()) return 0
            var a = yawDeg % 360f
            if (a < 0f) a += 360f
            return floor(a / (360f / Coverage.SEGMENTS)).toInt().mod(Coverage.SEGMENTS)
        }
    }
}
