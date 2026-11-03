package com.huntercoles.splatman.capture.presentation.processing

import com.huntercoles.splatman.capture.processing.ProcessingState
import com.huntercoles.splatman.splat.math.Quat
import com.huntercoles.splatman.splat.math.Vec3
import com.huntercoles.splatman.splat.model.SplatCloud
import com.huntercoles.splatman.splat.train.still.SplatStill
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

/** What the preview square draws (docs/design/screens/processing.md, ticket #173). */
sealed interface PreviewSource {
    /** The capture's last camera frame (blurred while building, dimmed on failure), or nothing. */
    data object Placeholder : PreviewSource

    /** A trainer snapshot drawn with [SplatStill]; [turning] while the run goes, still once it is done or held. */
    data class Cloud(val cloud: SplatCloud, val turning: Boolean) : PreviewSource

    /** The scan's saved still (`thumbnail.png`): Done with no snapshot seen on this screen. */
    data class Still(val file: File) : PreviewSource
}

/**
 * The newest snapshot while running (turning) or held for heat (still), the finished splat on Done (the last
 * snapshot, else the scan's thumbnail), and the placeholder until the first snapshot and after a failure.
 */
fun ProcessingUiState.previewSource(): PreviewSource {
    val cloud = snapshot
    return when {
        missing || failed -> PreviewSource.Placeholder
        state is ProcessingState.Done -> when {
            cloud != null -> PreviewSource.Cloud(cloud, turning = false)
            thumbnail != null -> PreviewSource.Still(thumbnail)
            else -> PreviewSource.Placeholder
        }
        cloud == null -> PreviewSource.Placeholder
        else -> PreviewSource.Cloud(cloud, turning = state !is ProcessingState.Paused)
    }
}

/**
 * The snapshot the preview holds after [state]: the trainer's newest one while running or held for heat, none once a
 * run (re)starts (keyframes loading, no progress yet), else [previous] (so Done keeps the finished splat).
 */
fun heldSnapshot(previous: SplatCloud?, state: ProcessingState): SplatCloud? = when (state) {
    is ProcessingState.Running -> if (state.progress == null) null else state.progress.snapshot ?: previous
    is ProcessingState.Paused -> state.progress.snapshot ?: previous
    else -> previous
}

/** Draws a snapshot turned [yawDeg] round world up (+Y), so the preview slowly turns; the CPU [SplatStill]. */
object LivePreview {
    /** The preview is square. */
    const val SIZE = 320

    /** Degrees turned per frame, and the time between frames: a slow turn that leaves the trainer most of the CPU. */
    const val TURN_STEP_DEG = 6f
    const val TURN_FRAME_MS = 1_000L

    /** ARGB pixels, `SIZE x SIZE`, of [cloud] turned [yawDeg] about its centre, over [background]. */
    fun render(cloud: SplatCloud, yawDeg: Float, background: Int): IntArray =
        SplatStill.render(turned(cloud, yawDeg), SIZE, SIZE, background)

    /**
     * [cloud] rotated [yawDeg] about the vertical line through its mean: positions and splat orientations turn, the
     * colours (degree 0 only; the still drops higher bands) do not. The input is not changed.
     */
    fun turned(cloud: SplatCloud, yawDeg: Float): SplatCloud {
        if (yawDeg % FULL_TURN == 0f || cloud.count == 0) return cloud
        val rad = Math.toRadians(yawDeg.toDouble())
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        val (cx, cz) = centre(cloud)
        val positions = cloud.positions.copyOf()
        val rotations = cloud.rotations.copyOf()
        val yaw = Quat.fromAxisAngle(Vec3.UNIT_Y, rad.toFloat())
        for (i in 0 until cloud.count) {
            val x = positions[3 * i] - cx
            val z = positions[3 * i + 2] - cz
            // Right-handed rotation about +Y: x' = c x + s z, z' = -s x + c z.
            positions[3 * i] = cx + c * x + s * z
            positions[3 * i + 2] = cz - s * x + c * z
            val q = yaw * Quat(rotations[4 * i], rotations[4 * i + 1], rotations[4 * i + 2], rotations[4 * i + 3])
            rotations[4 * i] = q.w
            rotations[4 * i + 1] = q.x
            rotations[4 * i + 2] = q.y
            rotations[4 * i + 3] = q.z
        }
        return SplatCloud(
            cloud.count, 0, positions, cloud.logScales, rotations, cloud.opacityLogits, cloud.shDc, FloatArray(0),
        )
    }

    /** Mean x and z of the finite positions (0, 0 when none). */
    private fun centre(cloud: SplatCloud): Pair<Float, Float> {
        var sx = 0.0
        var sz = 0.0
        var n = 0
        for (i in 0 until cloud.count) {
            val x = cloud.positions[3 * i]
            val z = cloud.positions[3 * i + 2]
            if (x.isFinite() && z.isFinite()) {
                sx += x
                sz += z
                n++
            }
        }
        return if (n == 0) 0f to 0f else (sx / n).toFloat() to (sz / n).toFloat()
    }

    private const val FULL_TURN = 360f
}
