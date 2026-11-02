package com.huntercoles.splatman.splat.gpu.raster

import com.huntercoles.splatman.splat.projection.Covariance
import com.huntercoles.splatman.splat.sh.ShEval

/**
 * Stage 3 of the GPU backward pass (ADR 0010; `RasterizerBackward.splatBackward`, pinhole): one invocation per splat
 * turns its [Grads2dLayout.ENTRY] screen-space sums (from [BackwardTileShaders.GATHER]) into the gradients of its raw
 * parameters, in fp32, recomputing the projection from the cloud as the CPU does in Double:
 *
 * - SH dc through C0 (0 where the forward colour was clamped at 0), the opacity logit through the sigmoid;
 * - the conic (c, -b, a) / det -> 2D covariance a, b, c -> dL/dSigma = T^T G T and dL/dT = 2 G T Sigma, with
 *   T = J W the EWA Jacobian times the world -> camera rotation;
 * - the camera-space mean through u = fx tx / tz + cx, v = fy ty / tz + cy and through J, then into the world by W^T;
 * - Sigma = M M^T, M = R S: the log-scales through exp, the rotation through the quaternion and its normalisation;
 * - viewGradNorms: |dL / d mean2d| in NDC (`SplatGrads.viewGradNorms`).
 *
 * A splat the forward pass culled (radius 0) gets zeros everywhere, as on the CPU. No atomics.
 *
 * Bindings (4): 0 the [GpuCloud] buffer ([CloudLayout], capacity `capacity`), 1 radii (int), 2 splatGrads2d
 * ([Grads2dLayout]), 3 the [GpuGrads] buffer ([GradsLayout], capacity `outCapacity`, written). Uniforms: `n`,
 * `capacity`, `outCapacity`, the world -> Ccv rotation column-major `r0..r8`, the camera centre `camX..camZ`, `fx`,
 * `fy`, and `halfW`, `halfH` (width / 2, height / 2).
 */
object BackwardSplatShaders {
    const val LOCAL = 256

    private const val E = Grads2dLayout.ENTRY

    val SPLAT = """#version 310 es
layout(local_size_x = $LOCAL) in;
layout(std430, binding = 0) readonly buffer Cloud { float cloud[]; };
layout(std430, binding = 1) readonly buffer Radii { int radii[]; };
layout(std430, binding = 2) readonly buffer Grads2d { float grads2d[]; };
layout(std430, binding = 3) writeonly buffer Grads { float grads[]; };
uniform uint n;
uniform uint capacity;
uniform uint outCapacity;
uniform float r0, r1, r2, r3, r4, r5, r6, r7, r8;
uniform float camX, camY, camZ;
uniform float fx, fy;
uniform float halfW, halfH;
const float DILATION = ${Covariance.DILATION};
const float C0 = ${ShEval.C0};

void main() {
    uint i = gl_GlobalInvocationID.x;
    if (i >= n) return;
    uint oPos = ${GradsLayout.POSITIONS}u * outCapacity + 3u * i;
    uint oScale = ${GradsLayout.LOG_SCALES}u * outCapacity + 3u * i;
    uint oRot = ${GradsLayout.ROTATIONS}u * outCapacity + 4u * i;
    uint oOpacity = ${GradsLayout.OPACITY_LOGITS}u * outCapacity + i;
    uint oSh = ${GradsLayout.SH_DC}u * outCapacity + 3u * i;
    uint oNorm = ${GradsLayout.VIEW_GRAD_NORMS}u * outCapacity + i;
    if (radii[i] <= 0) {
        for (uint j = 0u; j < 3u; j++) {
            grads[oPos + j] = 0.0;
            grads[oScale + j] = 0.0;
            grads[oSh + j] = 0.0;
        }
        for (uint j = 0u; j < 4u; j++) grads[oRot + j] = 0.0;
        grads[oOpacity] = 0.0;
        grads[oNorm] = 0.0;
        return;
    }
    uint g = ${E}u * i;
    float gu = grads2d[g + ${Grads2dLayout.U}u];
    float gv = grads2d[g + ${Grads2dLayout.V}u];
    grads[oNorm] = sqrt((gu * halfW) * (gu * halfW) + (gv * halfH) * (gv * halfH));

    uint h = ${CloudLayout.SH_DC}u * capacity + 3u * i;
    for (uint ch = 0u; ch < 3u; ch++) {
        float raw = 0.5 + C0 * cloud[h + ch];
        grads[oSh + ch] = raw > 0.0 ? C0 * grads2d[g + ${Grads2dLayout.R}u + ch] : 0.0;
    }
    float op = 1.0 / (1.0 + exp(-cloud[${CloudLayout.OPACITY_LOGITS}u * capacity + i]));
    grads[oOpacity] = grads2d[g + ${Grads2dLayout.OP}u] * op * (1.0 - op);

    // Camera-space mean t = W (p - c); view[c * 3 + r] = W(r, c).
    float view[9] = float[9](r0, r1, r2, r3, r4, r5, r6, r7, r8);
    uint p = ${CloudLayout.POSITIONS}u * capacity + 3u * i;
    float px = cloud[p] - camX;
    float py = cloud[p + 1u] - camY;
    float pz = cloud[p + 2u] - camZ;
    float tx = r0 * px + r3 * py + r6 * pz;
    float ty = r1 * px + r4 * py + r7 * pz;
    float tz = r2 * px + r5 * py + r8 * pz;

    // Rotation (row-major, from the normalised quaternion; w = 1 when it is zero) and scales; Sigma = M M^T, M = R S.
    uint q = ${CloudLayout.ROTATIONS}u * capacity + 4u * i;
    float qw0 = cloud[q];
    float qx0 = cloud[q + 1u];
    float qy0 = cloud[q + 2u];
    float qz0 = cloud[q + 3u];
    float qn = sqrt(qw0 * qw0 + qx0 * qx0 + qy0 * qy0 + qz0 * qz0);
    float qw = qn > 0.0 ? qw0 / qn : 1.0;
    float qx = qn > 0.0 ? qx0 / qn : 0.0;
    float qy = qn > 0.0 ? qy0 / qn : 0.0;
    float qz = qn > 0.0 ? qz0 / qn : 0.0;
    float rot[9] = float[9](
        1.0 - 2.0 * (qy * qy + qz * qz), 2.0 * (qx * qy - qw * qz), 2.0 * (qx * qz + qw * qy),
        2.0 * (qx * qy + qw * qz), 1.0 - 2.0 * (qx * qx + qz * qz), 2.0 * (qy * qz - qw * qx),
        2.0 * (qx * qz - qw * qy), 2.0 * (qy * qz + qw * qx), 1.0 - 2.0 * (qx * qx + qy * qy));
    uint s = ${CloudLayout.LOG_SCALES}u * capacity + 3u * i;
    float scale[3] = float[3](exp(cloud[s]), exp(cloud[s + 1u]), exp(cloud[s + 2u]));
    float m[9];
    for (int k = 0; k < 9; k++) m[k] = rot[k] * scale[k % 3];
    float sigma[9];
    for (int k = 0; k < 9; k++) {
        int r = k / 3;
        int c = k % 3;
        sigma[k] = m[r * 3] * m[c * 3] + m[r * 3 + 1] * m[c * 3 + 1] + m[r * 3 + 2] * m[c * 3 + 2];
    }

    // T = J W (2x3, row-major), J the pinhole Jacobian at t.
    float tz2 = tz * tz;
    float j00 = fx / tz;
    float j02 = -fx * tx / tz2;
    float j11 = fy / tz;
    float j12 = -fy * ty / tz2;
    float tm[6];
    for (int c = 0; c < 3; c++) {
        tm[c] = j00 * view[c * 3] + j02 * view[c * 3 + 2];
        tm[3 + c] = j11 * view[c * 3 + 1] + j12 * view[c * 3 + 2];
    }
    float st0[3];
    float st1[3];
    for (int r = 0; r < 3; r++) {
        st0[r] = sigma[r * 3] * tm[0] + sigma[r * 3 + 1] * tm[1] + sigma[r * 3 + 2] * tm[2];
        st1[r] = sigma[r * 3] * tm[3] + sigma[r * 3 + 1] * tm[4] + sigma[r * 3 + 2] * tm[5];
    }
    float a = tm[0] * st0[0] + tm[1] * st0[1] + tm[2] * st0[2] + DILATION;
    float b = tm[3] * st0[0] + tm[4] * st0[1] + tm[5] * st0[2];
    float c = tm[3] * st1[0] + tm[4] * st1[1] + tm[5] * st1[2] + DILATION;

    // Conic (c, -b, a) / det -> 2D covariance a, b, c (1 / det twice, so det^2 cannot overflow).
    float gA = grads2d[g + ${Grads2dLayout.CA}u];
    float gB = grads2d[g + ${Grads2dLayout.CB}u];
    float gC = grads2d[g + ${Grads2dLayout.CC}u];
    float det = a * c - b * b;
    float inv = 1.0 / det;
    float ga = (-c * c * gA + b * c * gB - b * b * gC) * inv * inv;
    float gb = (2.0 * b * c * gA - (det + 2.0 * b * b) * gB + 2.0 * a * b * gC) * inv * inv;
    float gc = (-b * b * gA + a * b * gB - a * a * gC) * inv * inv;

    // dL/dSigma = T^T G T, dL/dT = 2 G T Sigma, G = [[ga, gb/2], [gb/2, gc]].
    float gSigma[9];
    for (int k = 0; k < 9; k++) {
        int r = k / 3;
        int col = k % 3;
        gSigma[k] = ga * tm[r] * tm[col] + 0.5 * gb * (tm[r] * tm[3 + col] + tm[3 + r] * tm[col]) +
            gc * tm[3 + r] * tm[3 + col];
    }
    float gj00 = 0.0;
    float gj02 = 0.0;
    float gj11 = 0.0;
    float gj12 = 0.0;
    for (int col = 0; col < 3; col++) {
        float gT0 = 2.0 * ga * st0[col] + gb * st1[col];
        float gT1 = 2.0 * gc * st1[col] + gb * st0[col];
        gj00 += gT0 * view[col * 3];
        gj02 += gT0 * view[col * 3 + 2];
        gj11 += gT1 * view[col * 3 + 1];
        gj12 += gT1 * view[col * 3 + 2];
    }

    // Camera-space mean from u, v and from J, then into the world: dL/dp = W^T dL/dt.
    float tz3 = tz2 * tz;
    float gtx = gu * fx / tz - gj02 * fx / tz2;
    float gty = gv * fy / tz - gj12 * fy / tz2;
    float gtz = -gu * fx * tx / tz2 - gv * fy * ty / tz2 - gj00 * fx / tz2 + gj02 * 2.0 * fx * tx / tz3 -
        gj11 * fy / tz2 + gj12 * 2.0 * fy * ty / tz3;
    for (int j = 0; j < 3; j++) {
        grads[oPos + uint(j)] = view[j * 3] * gtx + view[j * 3 + 1] * gty + view[j * 3 + 2] * gtz;
    }

    // Sigma = M M^T: dL/dM = 2 dL/dSigma M; M = R S.
    float gR[9];
    float gScale[3] = float[3](0.0, 0.0, 0.0);
    for (int r = 0; r < 3; r++) {
        for (int col = 0; col < 3; col++) {
            float gm = 2.0 * (gSigma[r * 3] * m[col] + gSigma[r * 3 + 1] * m[3 + col] + gSigma[r * 3 + 2] * m[6 + col]);
            gR[r * 3 + col] = gm * scale[col];
            gScale[col] += gm * rot[r * 3 + col];
        }
    }
    for (int j = 0; j < 3; j++) grads[oScale + uint(j)] = gScale[j] * scale[j];

    // dL/dR -> dL/d raw quaternion through q / |q| (0 when |q| = 0).
    if (!(qn > 0.0)) {
        for (uint j = 0u; j < 4u; j++) grads[oRot + j] = 0.0;
        return;
    }
    float dw = 2.0 * (-qz * gR[1] + qy * gR[2] + qz * gR[3] - qx * gR[5] - qy * gR[6] + qx * gR[7]);
    float dx = 2.0 * (qy * gR[1] + qz * gR[2] + qy * gR[3] - 2.0 * qx * gR[4] - qw * gR[5] + qz * gR[6] + qw * gR[7] -
        2.0 * qx * gR[8]);
    float dy = 2.0 * (-2.0 * qy * gR[0] + qx * gR[1] + qw * gR[2] + qx * gR[3] + qz * gR[5] - qw * gR[6] + qz * gR[7] -
        2.0 * qy * gR[8]);
    float dz = 2.0 * (-2.0 * qz * gR[0] - qw * gR[1] + qx * gR[2] + qw * gR[3] - 2.0 * qz * gR[4] + qy * gR[5] +
        qx * gR[6] + qy * gR[7]);
    float along = qw * dw + qx * dx + qy * dy + qz * dz;
    grads[oRot] = (dw - qw * along) / qn;
    grads[oRot + 1u] = (dx - qx * along) / qn;
    grads[oRot + 2u] = (dy - qy * along) / qn;
    grads[oRot + 3u] = (dz - qz * along) / qn;
}
"""
}
