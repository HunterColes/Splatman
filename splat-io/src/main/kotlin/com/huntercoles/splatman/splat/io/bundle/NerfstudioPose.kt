package com.huntercoles.splatman.splat.io.bundle

import com.huntercoles.splatman.splat.math.Quat
import com.huntercoles.splatman.splat.math.Vec3
import com.huntercoles.splatman.splat.model.CameraPose
import kotlin.math.sqrt

/**
 * Inverse of `FrameConversions.nerfstudioMatrix`: an NS `transform_matrix` is already `T_W_Cgl` (ADR 0003 rule 7,
 * no axis change), so this only reads the rotation block back into a unit quaternion (Shepperd's method, in
 * doubles) with `w >= 0`, and the last column as the translation.
 */
internal fun poseFromNerfstudioMatrix(rows: List<List<Float>>, where: String): CameraPose {
    if (rows.size != 4 || rows.any { it.size != 4 }) {
        throw CaptureBundleException("$where: transform_matrix must be 4 rows of 4")
    }
    if (rows.flatten().any { !it.isFinite() }) throw CaptureBundleException("$where: transform_matrix is not finite")
    fun m(r: Int, c: Int): Double = rows[r][c].toDouble()
    val trace = m(0, 0) + m(1, 1) + m(2, 2)
    val q = when {
        trace > 0 -> {
            val s = sqrt(trace + 1.0) * 2
            doubleArrayOf(0.25 * s, (m(2, 1) - m(1, 2)) / s, (m(0, 2) - m(2, 0)) / s, (m(1, 0) - m(0, 1)) / s)
        }
        m(0, 0) > m(1, 1) && m(0, 0) > m(2, 2) -> {
            val s = sqrt(1.0 + m(0, 0) - m(1, 1) - m(2, 2)) * 2
            doubleArrayOf((m(2, 1) - m(1, 2)) / s, 0.25 * s, (m(0, 1) + m(1, 0)) / s, (m(0, 2) + m(2, 0)) / s)
        }
        m(1, 1) > m(2, 2) -> {
            val s = sqrt(1.0 + m(1, 1) - m(0, 0) - m(2, 2)) * 2
            doubleArrayOf((m(0, 2) - m(2, 0)) / s, (m(0, 1) + m(1, 0)) / s, 0.25 * s, (m(1, 2) + m(2, 1)) / s)
        }
        else -> {
            val s = sqrt(1.0 + m(2, 2) - m(0, 0) - m(1, 1)) * 2
            doubleArrayOf((m(1, 0) - m(0, 1)) / s, (m(0, 2) + m(2, 0)) / s, (m(1, 2) + m(2, 1)) / s, 0.25 * s)
        }
    }
    val n = sqrt(q.sumOf { it * it })
    if (!(n > 0.0) || !n.isFinite()) throw CaptureBundleException("$where: transform_matrix has no rotation")
    val k = (if (q[0] < 0) -1.0 else 1.0) / n
    return CameraPose(
        Quat((q[0] * k).toFloat(), (q[1] * k).toFloat(), (q[2] * k).toFloat(), (q[3] * k).toFloat()),
        Vec3(rows[0][3], rows[1][3], rows[2][3]),
    )
}
