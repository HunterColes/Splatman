package com.huntercoles.splatman.splat.train.raster

import com.huntercoles.splatman.splat.frames.FrameConversions
import com.huntercoles.splatman.splat.model.CameraIntrinsics
import com.huntercoles.splatman.splat.model.CameraPose
import com.huntercoles.splatman.splat.model.SplatCloud
import com.huntercoles.splatman.splat.projection.Covariance
import com.huntercoles.splatman.splat.sh.ShEval
import com.huntercoles.splatman.splat.train.raster.SplatGrads.Companion.CA
import com.huntercoles.splatman.splat.train.raster.SplatGrads.Companion.CAMERA
import com.huntercoles.splatman.splat.train.raster.SplatGrads.Companion.CB
import com.huntercoles.splatman.splat.train.raster.SplatGrads.Companion.CC
import com.huntercoles.splatman.splat.train.raster.SplatGrads.Companion.ENTRY
import com.huntercoles.splatman.splat.train.raster.SplatGrads.Companion.OP
import com.huntercoles.splatman.splat.train.raster.SplatGrads.Companion.R
import com.huntercoles.splatman.splat.train.raster.SplatGrads.Companion.U
import com.huntercoles.splatman.splat.train.raster.SplatGrads.Companion.V
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The backward pass of [Rasterizer] (ADR 0005), as in 3DGS / gsplat, in three stages:
 *
 * 1. per tile (in parallel), per pixel, the forward pass's list replayed back to front from its saved final
 *    transmittance and contributor count; each contributor's alpha is re-decided with the forward pass's exact Float
 *    arithmetic (so the same splats count), its value and gradients are computed in Double and summed per tile-list
 *    entry;
 * 2. the entries summed per splat, in list order (deterministic for any thread count);
 * 3. per splat (in parallel), the chain rule through the projection, recomputed in Double from the cloud: conic ->
 *    2D covariance -> EWA Jacobian and 3D covariance -> camera-space mean, scales and normalised quaternion; with a
 *    [Lens], also its focal scale and k1, per splat, then summed in index order (deterministic).
 */
internal object RasterizerBackward {

    private const val CHUNK = 1024

    @Suppress("LongParameterList")
    fun run(
        raster: Rasterizer, cloud: SplatCloud, k: CameraIntrinsics, pose: CameraPose,
        fwd: RenderBuffers, dLdColor: FloatArray, grads: SplatGrads, lens: Lens? = null,
    ) {
        require(fwd.width == k.width && fwd.height == k.height) {
            "buffers ${fwd.width}x${fwd.height} do not match intrinsics ${k.width}x${k.height}"
        }
        require(fwd.splatCount == cloud.count) {
            "buffers hold a forward pass of ${fwd.splatCount} splats, the cloud has ${cloud.count}"
        }
        require(dLdColor.size == 3 * fwd.width * fwd.height) {
            "dLdColor needs ${3 * fwd.width * fwd.height} floats (3 per pixel), got ${dLdColor.size}"
        }
        val n = cloud.count
        grads.ensureSplats(n)
        val entries = fwd.tileStart[fwd.tileCount]
        grads.ensureEntries(entries)
        val scratch = ThreadLocal.withInitial { DoubleArray(0) }
        raster.parallelFor(fwd.tileCount) { t ->
            val len = ENTRY * (fwd.tileStart[t + 1] - fwd.tileStart[t])
            var acc = scratch.get()
            if (acc.size < len) {
                acc = DoubleArray(len)
                scratch.set(acc)
            }
            acc.fill(0.0, 0, len)
            replayTile(t, fwd, dLdColor, acc)
            val base = ENTRY * fwd.tileStart[t]
            for (j in 0 until len) grads.entryGrads[base + j] = acc[j].toFloat()
        }

        val per = grads.splatGrads2d
        val list = fwd.tileSplats
        val entryGrads = grads.entryGrads
        for (e in 0 until entries) {
            val s = ENTRY * list[e]
            val src = ENTRY * e
            for (j in 0 until ENTRY) per[s + j] += entryGrads[src + j].toDouble()
        }

        val cvPose = FrameConversions.glToCv(pose)
        val w = cvPose.rotation.normalized().toMat3().transpose().values
        val view = DoubleArray(12)
        for (j in 0 until 9) view[j] = w[j].toDouble()
        view[9] = cvPose.translationM.x.toDouble()
        view[10] = cvPose.translationM.y.toDouble()
        view[11] = cvPose.translationM.z.toDouble()
        raster.parallelFor((n + CHUNK - 1) / CHUNK) { chunk ->
            for (i in chunk * CHUNK until min(n, (chunk + 1) * CHUNK)) {
                if (fwd.radii[i] > 0) splatBackward(cloud, i, k, lens, view, grads)
            }
        }
        if (lens != null) {
            val cam = grads.splatCamera
            var gs = 0.0
            var gk = 0.0
            for (i in 0 until n) {
                gs += cam[CAMERA * i]
                gk += cam[CAMERA * i + 1]
            }
            grads.focalScale = gs
            grads.k1 = gk
        }
    }

    /** Stage 1 for tile [t]: adds every pixel's screen-space gradients to [acc], [ENTRY] per entry of the tile. */
    @Suppress("CyclomaticComplexMethod", "NestedBlockDepth")
    private fun replayTile(t: Int, fwd: RenderBuffers, dLdColor: FloatArray, acc: DoubleArray) {
        val x0 = (t % fwd.tilesX) * Rasterizer.TILE
        val y0 = (t / fwd.tilesX) * Rasterizer.TILE
        val x1 = min(x0 + Rasterizer.TILE, fwd.width)
        val y1 = min(y0 + Rasterizer.TILE, fwd.height)
        val begin = fwd.tileStart[t]
        val list = fwd.tileSplats
        val means = fwd.means2d
        val conics = fwd.conics
        val colors = fwd.colors
        val opacities = fwd.opacities
        val cuts = fwd.powerCuts
        val alphaMax = Rasterizer.ALPHA_MAX.toDouble()
        for (y in y0 until y1) for (x in x0 until x1) {
            val p = y * fwd.width + x
            val count = fwd.contributors[p]
            val gr = dLdColor[3 * p].toDouble()
            val gg = dLdColor[3 * p + 1].toDouble()
            val gb = dLdColor[3 * p + 2].toDouble()
            if (count == 0 || (gr == 0.0 && gg == 0.0 && gb == 0.0)) continue
            var tr = fwd.finalT[p].toDouble()
            // Colour composited by the splats behind the current one, sum of c_j alpha_j T_j.
            var behindR = 0.0
            var behindG = 0.0
            var behindB = 0.0
            for (e in begin + count - 1 downTo begin) {
                val s = list[e]
                // The forward pass's decision, in its own Float arithmetic.
                val dxF = means[2 * s] - x
                val dyF = means[2 * s + 1] - y
                val powerF = -0.5f * (conics[3 * s] * dxF * dxF + conics[3 * s + 2] * dyF * dyF) -
                    conics[3 * s + 1] * dxF * dyF
                if (powerF > 0f || powerF < cuts[s]) continue
                val rawF = opacities[s] * exp(powerF)
                if (min(Rasterizer.ALPHA_MAX, rawF) < Rasterizer.ALPHA_MIN) continue
                val capped = rawF > Rasterizer.ALPHA_MAX

                val ca = conics[3 * s].toDouble()
                val cb = conics[3 * s + 1].toDouble()
                val cc = conics[3 * s + 2].toDouble()
                val dx = means[2 * s].toDouble() - x
                val dy = means[2 * s + 1].toDouble() - y
                val gauss = exp(-0.5 * (ca * dx * dx + cc * dy * dy) - cb * dx * dy)
                val alpha = if (capped) alphaMax else opacities[s] * gauss
                tr /= 1.0 - alpha
                val weight = alpha * tr
                val cr = colors[3 * s].toDouble()
                val cg = colors[3 * s + 1].toDouble()
                val cbl = colors[3 * s + 2].toDouble()
                val inv = 1.0 / (1.0 - alpha)
                val dLdAlpha = gr * (cr * tr - behindR * inv) + gg * (cg * tr - behindG * inv) +
                    gb * (cbl * tr - behindB * inv)
                behindR += cr * weight
                behindG += cg * weight
                behindB += cbl * weight

                val o = ENTRY * (e - begin)
                acc[o + R] += weight * gr
                acc[o + R + 1] += weight * gg
                acc[o + R + 2] += weight * gb
                if (capped) continue
                acc[o + OP] += gauss * dLdAlpha
                val dLdPower = dLdAlpha * alpha
                acc[o + CA] += -0.5 * dx * dx * dLdPower
                acc[o + CB] += -dx * dy * dLdPower
                acc[o + CC] += -0.5 * dy * dy * dLdPower
                acc[o + U] += -(ca * dx + cb * dy) * dLdPower
                acc[o + V] += -(cc * dy + cb * dx) * dLdPower
            }
        }
    }

    /**
     * Stage 3 for splat [i]: [view] is `W = R_Ccv_W` column-major (9) then the camera centre in W (3). Writes the
     * splat's parameter gradients and view-gradient norm into [grads].
     */
    @Suppress("LongMethod", "LongParameterList")
    private fun splatBackward(
        cloud: SplatCloud, i: Int, k: CameraIntrinsics, lens: Lens?, view: DoubleArray, grads: SplatGrads,
    ) {
        val g2 = grads.splatGrads2d
        val b2 = ENTRY * i
        val gu = g2[b2 + U]
        val gv = g2[b2 + V]
        val fx = (if (lens == null) k.fx else k.fx * lens.focalScale).toDouble()
        val fy = (if (lens == null) k.fy else k.fy * lens.focalScale).toDouble()
        grads.viewGradNorms[i] = sqrt(sq(gu * k.width / 2) + sq(gv * k.height / 2)).toFloat()

        for (ch in 0 until 3) {
            val raw = 0.5 + ShEval.C0 * cloud.shDc[3 * i + ch]
            grads.shDc[3 * i + ch] = if (raw > 0.0) (ShEval.C0 * g2[b2 + R + ch]).toFloat() else 0f
        }
        val opacity = 1.0 / (1.0 + exp(-cloud.opacityLogits[i].toDouble()))
        grads.opacityLogits[i] = (g2[b2 + OP] * opacity * (1 - opacity)).toFloat()

        // Camera-space mean t = W (p - c).
        val px = cloud.positions[3 * i] - view[9]
        val py = cloud.positions[3 * i + 1] - view[10]
        val pz = cloud.positions[3 * i + 2] - view[11]
        val tx = view[0] * px + view[3] * py + view[6] * pz
        val ty = view[1] * px + view[4] * py + view[7] * pz
        val tz = view[2] * px + view[5] * py + view[8] * pz

        // Rotation and scales, then Sigma = M M^T with M = R S (as Covariance.world, in Double).
        val q = cloud.rotations
        val qw0 = q[4 * i].toDouble()
        val qx0 = q[4 * i + 1].toDouble()
        val qy0 = q[4 * i + 2].toDouble()
        val qz0 = q[4 * i + 3].toDouble()
        val qn = sqrt(qw0 * qw0 + qx0 * qx0 + qy0 * qy0 + qz0 * qz0)
        val qw = if (qn > 0.0) qw0 / qn else 1.0
        val qx = if (qn > 0.0) qx0 / qn else 0.0
        val qy = if (qn > 0.0) qy0 / qn else 0.0
        val qz = if (qn > 0.0) qz0 / qn else 0.0
        val rot = doubleArrayOf( // row-major r(row, col) at row * 3 + col
            1 - 2 * (qy * qy + qz * qz), 2 * (qx * qy - qw * qz), 2 * (qx * qz + qw * qy),
            2 * (qx * qy + qw * qz), 1 - 2 * (qx * qx + qz * qz), 2 * (qy * qz - qw * qx),
            2 * (qx * qz - qw * qy), 2 * (qy * qz + qw * qx), 1 - 2 * (qx * qx + qy * qy),
        )
        val scale = DoubleArray(3) { exp(cloud.logScales[3 * i + it].toDouble()) }
        val m = DoubleArray(9) { rot[it] * scale[it % 3] }
        val sigma = DoubleArray(9) { idx ->
            val r = idx / 3
            val c = idx % 3
            m[r * 3] * m[c * 3] + m[r * 3 + 1] * m[c * 3 + 1] + m[r * 3 + 2] * m[c * 3 + 2]
        }

        // T = J W (2x3, row-major), with J the Jacobian of the pinhole at t; with a lens, the pinhole's at focal
        // lengths fx d, fy d (see Lens), d = 1 + k1 (xn^2 + yn^2).
        val k1 = lens?.k1?.toDouble() ?: 0.0
        val xn = tx / tz
        val yn = ty / tz
        val r2 = xn * xn + yn * yn
        val dist = 1 + k1 * r2
        val dz = dist / tz
        val j00 = if (lens == null) fx / tz else fx * dz
        val j02 = if (lens == null) -fx * tx / (tz * tz) else -fx * dz * xn
        val j11 = if (lens == null) fy / tz else fy * dz
        val j12 = if (lens == null) -fy * ty / (tz * tz) else -fy * dz * yn
        val tm = DoubleArray(6)
        for (c in 0 until 3) {
            tm[c] = j00 * view[c * 3] + j02 * view[c * 3 + 2]
            tm[3 + c] = j11 * view[c * 3 + 1] + j12 * view[c * 3 + 2]
        }
        val st0 = DoubleArray(3) { r -> sigma[r * 3] * tm[0] + sigma[r * 3 + 1] * tm[1] + sigma[r * 3 + 2] * tm[2] }
        val st1 = DoubleArray(3) { r -> sigma[r * 3] * tm[3] + sigma[r * 3 + 1] * tm[4] + sigma[r * 3 + 2] * tm[5] }
        val a = tm[0] * st0[0] + tm[1] * st0[1] + tm[2] * st0[2] + Covariance.DILATION
        val b = tm[3] * st0[0] + tm[4] * st0[1] + tm[5] * st0[2]
        val c = tm[3] * st1[0] + tm[4] * st1[1] + tm[5] * st1[2] + Covariance.DILATION

        // Conic (c, -b, a) / det -> 2D covariance a, b, c.
        val gA = g2[b2 + CA]
        val gB = g2[b2 + CB]
        val gC = g2[b2 + CC]
        val det = a * c - b * b
        val det2 = det * det
        val ga = (-c * c * gA + b * c * gB - b * b * gC) / det2
        val gb = (2 * b * c * gA - (det + 2 * b * b) * gB + 2 * a * b * gC) / det2
        val gc = (-b * b * gA + a * b * gB - a * a * gC) / det2

        // a = T0 S T0^T, b = T0 S T1^T, c = T1 S T1^T: dL/dS = T^T G T, dL/dT = 2 G T S, G = [[ga, gb/2], [gb/2, gc]].
        val gSigma = DoubleArray(9) { idx ->
            val r = idx / 3
            val col = idx % 3
            ga * tm[r] * tm[col] + 0.5 * gb * (tm[r] * tm[3 + col] + tm[3 + r] * tm[col]) + gc * tm[3 + r] * tm[3 + col]
        }
        val gT0 = DoubleArray(3) { 2 * ga * st0[it] + gb * st1[it] }
        val gT1 = DoubleArray(3) { 2 * gc * st1[it] + gb * st0[it] }
        var gj00 = 0.0
        var gj02 = 0.0
        var gj11 = 0.0
        var gj12 = 0.0
        for (col in 0 until 3) {
            gj00 += gT0[col] * view[col * 3]
            gj02 += gT0[col] * view[col * 3 + 2]
            gj11 += gT1[col] * view[col * 3 + 1]
            gj12 += gT1[col] * view[col * 3 + 2]
        }

        // Camera-space mean from u = fx tx / tz + cx, v = fy ty / tz + cy and from J.
        val gtx: Double
        val gty: Double
        val gtz: Double
        if (lens == null) {
            val tz2 = tz * tz
            val tz3 = tz2 * tz
            gtx = gu * fx / tz - gj02 * fx / tz2
            gty = gv * fy / tz - gj12 * fy / tz2
            gtz = -gu * fx * tx / tz2 - gv * fy * ty / tz2 - gj00 * fx / tz2 + gj02 * 2 * fx * tx / tz3 -
                gj11 * fy / tz2 + gj12 * 2 * fy * ty / tz3
        } else {
            // u = F xn d + cx, v = G yn d + cy; J = dz (F, 0, -F xn; 0, G, -G yn), dz = d / tz, F = s fx, G = s fy.
            val jx = gj00 - gj02 * xn
            val jy = gj11 - gj12 * yn
            val gF = gu * xn * dist + dz * jx
            val gG = gv * yn * dist + dz * jy
            val gDist = gu * fx * xn + gv * fy * yn + (fx * jx + fy * jy) / tz
            val gx = gu * fx * dist - gj02 * fx * dz + gDist * 2 * k1 * xn
            val gy = gv * fy * dist - gj12 * fy * dz + gDist * 2 * k1 * yn
            gtx = gx / tz
            gty = gy / tz
            gtz = -dz / tz * (fx * jx + fy * jy) - (gx * xn + gy * yn) / tz
            grads.splatCamera[CAMERA * i] = gF * k.fx + gG * k.fy
            grads.splatCamera[CAMERA * i + 1] = gDist * r2
        }
        for (j in 0 until 3) {
            grads.positions[3 * i + j] =
                (view[j * 3] * gtx + view[j * 3 + 1] * gty + view[j * 3 + 2] * gtz).toFloat()
        }

        // Sigma = M M^T: dL/dM = 2 dL/dSigma M; M = R S.
        val gR = DoubleArray(9)
        val gScale = DoubleArray(3)
        for (r in 0 until 3) for (col in 0 until 3) {
            val gm = 2 * (gSigma[r * 3] * m[col] + gSigma[r * 3 + 1] * m[3 + col] + gSigma[r * 3 + 2] * m[6 + col])
            gR[r * 3 + col] = gm * scale[col]
            gScale[col] += gm * rot[r * 3 + col]
        }
        for (j in 0 until 3) grads.logScales[3 * i + j] = (gScale[j] * scale[j]).toFloat()

        quatBackward(qw, qx, qy, qz, qn, gR, grads.rotations, 4 * i)
    }

    /** dL/dR (row-major) -> dL/d raw quaternion at `out[at..+3]`, through `q / |q|` (0 when |q| = 0). */
    @Suppress("LongParameterList")
    private fun quatBackward(
        w: Double, x: Double, y: Double, z: Double, norm: Double, g: DoubleArray, out: FloatArray, at: Int,
    ) {
        if (!(norm > 0.0)) return
        val gw = 2 * (-z * g[1] + y * g[2] + z * g[3] - x * g[5] - y * g[6] + x * g[7])
        val gx = 2 * (y * g[1] + z * g[2] + y * g[3] - 2 * x * g[4] - w * g[5] + z * g[6] + w * g[7] - 2 * x * g[8])
        val gy = 2 * (-2 * y * g[0] + x * g[1] + w * g[2] + x * g[3] + z * g[5] - w * g[6] + z * g[7] - 2 * y * g[8])
        val gz = 2 * (-2 * z * g[0] - w * g[1] + x * g[2] + w * g[3] - 2 * z * g[4] + y * g[5] + x * g[6] + y * g[7])
        val dot = w * gw + x * gx + y * gy + z * gz
        out[at] = ((gw - w * dot) / norm).toFloat()
        out[at + 1] = ((gx - x * dot) / norm).toFloat()
        out[at + 2] = ((gy - y * dot) / norm).toFloat()
        out[at + 3] = ((gz - z * dot) / norm).toFloat()
    }

    private fun sq(v: Double) = v * v
}
