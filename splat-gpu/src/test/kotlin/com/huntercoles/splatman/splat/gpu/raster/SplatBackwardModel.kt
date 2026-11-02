package com.huntercoles.splatman.splat.gpu.raster

import com.huntercoles.splatman.splat.frames.FrameConversions
import com.huntercoles.splatman.splat.model.CameraIntrinsics
import com.huntercoles.splatman.splat.model.CameraPose
import com.huntercoles.splatman.splat.model.SplatCloud
import com.huntercoles.splatman.splat.projection.Covariance
import com.huntercoles.splatman.splat.sh.ShEval
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * A pure-Kotlin model of [BackwardSplatShaders.SPLAT], line for line in fp32, writing a [GradsLayout] buffer of
 * capacity `cloud.count`. Its uniforms are computed as [GpuRasterizer.backward] sets them.
 */
internal object SplatBackwardModel {
    private const val E = Grads2dLayout.ENTRY
    private val DILATION = Covariance.DILATION.toFloat()
    private val C0 = ShEval.C0.toFloat()

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    fun run(cloud: SplatCloud, radii: IntArray, grads2d: FloatArray, k: CameraIntrinsics, pose: CameraPose): FloatArray {
        val n = cloud.count
        val cv = FrameConversions.glToCv(pose)
        val view = cv.rotation.normalized().toMat3().transpose().values
        val camX = cv.translationM.x
        val camY = cv.translationM.y
        val camZ = cv.translationM.z
        val fx = k.fx
        val fy = k.fy
        val halfW = k.width / 2f
        val halfH = k.height / 2f
        val out = FloatArray(GradsLayout.FLOATS_PER_SPLAT * maxOf(1, n))
        for (i in 0 until n) {
            val oPos = GradsLayout.POSITIONS * n + 3 * i
            val oScale = GradsLayout.LOG_SCALES * n + 3 * i
            val oRot = GradsLayout.ROTATIONS * n + 4 * i
            val oOpacity = GradsLayout.OPACITY_LOGITS * n + i
            val oSh = GradsLayout.SH_DC * n + 3 * i
            val oNorm = GradsLayout.VIEW_GRAD_NORMS * n + i
            if (radii[i] <= 0) continue
            val g = E * i
            val gu = grads2d[g + Grads2dLayout.U]
            val gv = grads2d[g + Grads2dLayout.V]
            out[oNorm] = sqrt((gu * halfW) * (gu * halfW) + (gv * halfH) * (gv * halfH))
            for (ch in 0 until 3) {
                val raw = 0.5f + C0 * cloud.shDc[3 * i + ch]
                out[oSh + ch] = if (raw > 0f) C0 * grads2d[g + Grads2dLayout.R + ch] else 0f
            }
            val op = 1f / (1f + exp(-cloud.opacityLogits[i]))
            out[oOpacity] = grads2d[g + Grads2dLayout.OP] * op * (1f - op)

            val px = cloud.positions[3 * i] - camX
            val py = cloud.positions[3 * i + 1] - camY
            val pz = cloud.positions[3 * i + 2] - camZ
            val tx = view[0] * px + view[3] * py + view[6] * pz
            val ty = view[1] * px + view[4] * py + view[7] * pz
            val tz = view[2] * px + view[5] * py + view[8] * pz

            val qw0 = cloud.rotations[4 * i]
            val qx0 = cloud.rotations[4 * i + 1]
            val qy0 = cloud.rotations[4 * i + 2]
            val qz0 = cloud.rotations[4 * i + 3]
            val qn = sqrt(qw0 * qw0 + qx0 * qx0 + qy0 * qy0 + qz0 * qz0)
            val qw = if (qn > 0f) qw0 / qn else 1f
            val qx = if (qn > 0f) qx0 / qn else 0f
            val qy = if (qn > 0f) qy0 / qn else 0f
            val qz = if (qn > 0f) qz0 / qn else 0f
            val rot = floatArrayOf(
                1 - 2 * (qy * qy + qz * qz), 2 * (qx * qy - qw * qz), 2 * (qx * qz + qw * qy),
                2 * (qx * qy + qw * qz), 1 - 2 * (qx * qx + qz * qz), 2 * (qy * qz - qw * qx),
                2 * (qx * qz - qw * qy), 2 * (qy * qz + qw * qx), 1 - 2 * (qx * qx + qy * qy),
            )
            val scale = FloatArray(3) { exp(cloud.logScales[3 * i + it]) }
            val m = FloatArray(9) { rot[it] * scale[it % 3] }
            val sigma = FloatArray(9) {
                val r = it / 3
                val c = it % 3
                m[r * 3] * m[c * 3] + m[r * 3 + 1] * m[c * 3 + 1] + m[r * 3 + 2] * m[c * 3 + 2]
            }

            val tz2 = tz * tz
            val j00 = fx / tz
            val j02 = -fx * tx / tz2
            val j11 = fy / tz
            val j12 = -fy * ty / tz2
            val tm = FloatArray(6)
            for (c in 0 until 3) {
                tm[c] = j00 * view[c * 3] + j02 * view[c * 3 + 2]
                tm[3 + c] = j11 * view[c * 3 + 1] + j12 * view[c * 3 + 2]
            }
            val st0 = FloatArray(3) { r -> sigma[r * 3] * tm[0] + sigma[r * 3 + 1] * tm[1] + sigma[r * 3 + 2] * tm[2] }
            val st1 = FloatArray(3) { r -> sigma[r * 3] * tm[3] + sigma[r * 3 + 1] * tm[4] + sigma[r * 3 + 2] * tm[5] }
            val a = tm[0] * st0[0] + tm[1] * st0[1] + tm[2] * st0[2] + DILATION
            val b = tm[3] * st0[0] + tm[4] * st0[1] + tm[5] * st0[2]
            val c = tm[3] * st1[0] + tm[4] * st1[1] + tm[5] * st1[2] + DILATION

            val gA = grads2d[g + Grads2dLayout.CA]
            val gB = grads2d[g + Grads2dLayout.CB]
            val gC = grads2d[g + Grads2dLayout.CC]
            val det = a * c - b * b
            val inv = 1f / det
            val ga = (-c * c * gA + b * c * gB - b * b * gC) * inv * inv
            val gb = (2 * b * c * gA - (det + 2 * b * b) * gB + 2 * a * b * gC) * inv * inv
            val gc = (-b * b * gA + a * b * gB - a * a * gC) * inv * inv

            val gSigma = FloatArray(9) {
                val r = it / 3
                val col = it % 3
                ga * tm[r] * tm[col] + 0.5f * gb * (tm[r] * tm[3 + col] + tm[3 + r] * tm[col]) +
                    gc * tm[3 + r] * tm[3 + col]
            }
            var gj00 = 0f
            var gj02 = 0f
            var gj11 = 0f
            var gj12 = 0f
            for (col in 0 until 3) {
                val gT0 = 2 * ga * st0[col] + gb * st1[col]
                val gT1 = 2 * gc * st1[col] + gb * st0[col]
                gj00 += gT0 * view[col * 3]
                gj02 += gT0 * view[col * 3 + 2]
                gj11 += gT1 * view[col * 3 + 1]
                gj12 += gT1 * view[col * 3 + 2]
            }

            val tz3 = tz2 * tz
            val gtx = gu * fx / tz - gj02 * fx / tz2
            val gty = gv * fy / tz - gj12 * fy / tz2
            val gtz = -gu * fx * tx / tz2 - gv * fy * ty / tz2 - gj00 * fx / tz2 + gj02 * 2 * fx * tx / tz3 -
                gj11 * fy / tz2 + gj12 * 2 * fy * ty / tz3
            for (j in 0 until 3) out[oPos + j] = view[j * 3] * gtx + view[j * 3 + 1] * gty + view[j * 3 + 2] * gtz

            val gR = FloatArray(9)
            val gScale = FloatArray(3)
            for (r in 0 until 3) for (col in 0 until 3) {
                val gm = 2 * (gSigma[r * 3] * m[col] + gSigma[r * 3 + 1] * m[3 + col] + gSigma[r * 3 + 2] * m[6 + col])
                gR[r * 3 + col] = gm * scale[col]
                gScale[col] += gm * rot[r * 3 + col]
            }
            for (j in 0 until 3) out[oScale + j] = gScale[j] * scale[j]

            if (!(qn > 0f)) continue
            val dw = 2 * (-qz * gR[1] + qy * gR[2] + qz * gR[3] - qx * gR[5] - qy * gR[6] + qx * gR[7])
            val dx = 2 * (qy * gR[1] + qz * gR[2] + qy * gR[3] - 2 * qx * gR[4] - qw * gR[5] + qz * gR[6] + qw * gR[7] -
                2 * qx * gR[8])
            val dy = 2 * (-2 * qy * gR[0] + qx * gR[1] + qw * gR[2] + qx * gR[3] + qz * gR[5] - qw * gR[6] + qz * gR[7] -
                2 * qy * gR[8])
            val dz = 2 * (-2 * qz * gR[0] - qw * gR[1] + qx * gR[2] + qw * gR[3] - 2 * qz * gR[4] + qy * gR[5] +
                qx * gR[6] + qy * gR[7])
            val along = qw * dw + qx * dx + qy * dy + qz * dz
            out[oRot] = (dw - qw * along) / qn
            out[oRot + 1] = (dx - qx * along) / qn
            out[oRot + 2] = (dy - qy * along) / qn
            out[oRot + 3] = (dz - qz * along) / qn
        }
        return out
    }
}
