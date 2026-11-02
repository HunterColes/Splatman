package com.huntercoles.splatman.splat.gpu.raster

import com.huntercoles.splatman.splat.projection.Covariance
import com.huntercoles.splatman.splat.sh.ShEval

/**
 * The projection kernel: `Rasterizer.project` and `Covariance.world` / `Covariance.project` in fp32, one invocation
 * per splat. Same culls in the same order (near plane, lens factor, determinant, non-finite centre, zero radius,
 * wholly off the image), same EWA footprint with the 0.3 px low-pass and the 3-sigma radius, SH degree 0 colour and
 * sigmoid opacity. Writes the [GpuRenderBuffers] per-splat arrays in `RenderBuffers`' layout; a culled splat gets
 * radius 0 and its depth, its other fields are left as they were (as on the CPU).
 *
 * Bindings: 0 the [GpuCloud] buffer ([CloudLayout]), 1 radii (int), 2 depths, 3 means2d (2 floats), 4 conics
 * (3 floats), 5 colours (3 floats), 6 opacities. Uniforms: `n`, `capacity`, the world -> Ccv rotation column-major
 * `r0..r8`, the camera centre `camX..camZ`, the lens-scaled `fx`, `fy`, the principal point `cx`, `cy`, the lens
 * `k1`, and `maxU`, `maxV` (width - 1, height - 1).
 */
object ProjectShaders {
    const val LOCAL = 256

    val PROJECT = """#version 310 es
layout(local_size_x = $LOCAL) in;
layout(std430, binding = 0) readonly buffer Cloud { float cloud[]; };
layout(std430, binding = 1) writeonly buffer Radii { int radii[]; };
layout(std430, binding = 2) writeonly buffer Depths { float depths[]; };
layout(std430, binding = 3) writeonly buffer Means { float means2d[]; };
layout(std430, binding = 4) writeonly buffer Conics { float conics[]; };
layout(std430, binding = 5) writeonly buffer Colors { float colors[]; };
layout(std430, binding = 6) writeonly buffer Opacities { float opacities[]; };
uniform uint n;
uniform uint capacity;
uniform float r0, r1, r2, r3, r4, r5, r6, r7, r8;
uniform float camX, camY, camZ;
uniform float fx, fy, cx, cy, k1;
uniform float maxU, maxV;
const float NEAR_Z = ${Covariance.NEAR_Z};
const float DILATION = ${Covariance.DILATION};
const float SIGMA_RADIUS = ${Covariance.SIGMA_RADIUS};
const float DISC_FLOOR = 0.1;
const float C0 = ${ShEval.C0};
const float INT_LIMIT = 2147483648.0;

void main() {
    uint i = gl_GlobalInvocationID.x;
    if (i >= n) return;
    uint p = ${CloudLayout.POSITIONS}u * capacity + 3u * i;
    float px = cloud[p] - camX;
    float py = cloud[p + 1u] - camY;
    float pz = cloud[p + 2u] - camZ;
    float mx = r0 * px + r3 * py + r6 * pz;
    float my = r1 * px + r4 * py + r7 * pz;
    float mz = r2 * px + r5 * py + r8 * pz;
    radii[i] = 0;
    depths[i] = mz;
    if (!(mz > NEAR_Z)) return;
    float xn = mx / mz;
    float yn = my / mz;
    float d = 1.0 + k1 * (xn * xn + yn * yn);
    if (!(d > 0.0)) return;

    // Covariance.world: R S S^T R^T, R from the normalised quaternion (w = 1 when it is zero).
    uint q = ${CloudLayout.ROTATIONS}u * capacity + 4u * i;
    float w = cloud[q];
    float x = cloud[q + 1u];
    float y = cloud[q + 2u];
    float z = cloud[q + 3u];
    float qn = sqrt(w * w + x * x + y * y + z * z);
    if (qn > 0.0) {
        w /= qn; x /= qn; y /= qn; z /= qn;
    } else {
        w = 1.0;
    }
    float q00 = 1.0 - 2.0 * (y * y + z * z); float q01 = 2.0 * (x * y - w * z); float q02 = 2.0 * (x * z + w * y);
    float q10 = 2.0 * (x * y + w * z); float q11 = 1.0 - 2.0 * (x * x + z * z); float q12 = 2.0 * (y * z - w * x);
    float q20 = 2.0 * (x * z - w * y); float q21 = 2.0 * (y * z + w * x); float q22 = 1.0 - 2.0 * (x * x + y * y);
    uint s = ${CloudLayout.LOG_SCALES}u * capacity + 3u * i;
    float s0 = exp(cloud[s]);
    float s1 = exp(cloud[s + 1u]);
    float s2 = exp(cloud[s + 2u]);
    float m00 = q00 * s0; float m01 = q01 * s1; float m02 = q02 * s2;
    float m10 = q10 * s0; float m11 = q11 * s1; float m12 = q12 * s2;
    float m20 = q20 * s0; float m21 = q21 * s1; float m22 = q22 * s2;
    float sxx = m00 * m00 + m01 * m01 + m02 * m02;
    float sxy = m00 * m10 + m01 * m11 + m02 * m12;
    float sxz = m00 * m20 + m01 * m21 + m02 * m22;
    float syy = m10 * m10 + m11 * m11 + m12 * m12;
    float syz = m10 * m20 + m11 * m21 + m12 * m22;
    float szz = m20 * m20 + m21 * m21 + m22 * m22;

    // Covariance.project with focal lengths fx d, fy d: J W Sigma W^T J^T + 0.3 I.
    float fxd = fx * d;
    float fyd = fy * d;
    float j00 = fxd / mz; float j02 = -fxd * mx / (mz * mz);
    float j11 = fyd / mz; float j12 = -fyd * my / (mz * mz);
    float t00 = j00 * r0 + j02 * r2; float t01 = j00 * r3 + j02 * r5; float t02 = j00 * r6 + j02 * r8;
    float t10 = j11 * r1 + j12 * r2; float t11 = j11 * r4 + j12 * r5; float t12 = j11 * r7 + j12 * r8;
    float u0 = t00 * sxx + t01 * sxy + t02 * sxz;
    float u1 = t00 * sxy + t01 * syy + t02 * syz;
    float u2 = t00 * sxz + t01 * syz + t02 * szz;
    float v0 = t10 * sxx + t11 * sxy + t12 * sxz;
    float v1 = t10 * sxy + t11 * syy + t12 * syz;
    float v2 = t10 * sxz + t11 * syz + t12 * szz;
    float a = u0 * t00 + u1 * t01 + u2 * t02 + DILATION;
    float b = u0 * t10 + u1 * t11 + u2 * t12;
    float c = v0 * t10 + v1 * t11 + v2 * t12 + DILATION;
    float mid = 0.5 * (a + c);
    // mid^2 - det, written without the cancellation so fp32 stays near the CPU's fp64.
    float hd = 0.5 * (a - c);
    float lambdaMax = mid + sqrt(max(DISC_FLOOR, hd * hd + b * b));
    float radius = ceil(SIGMA_RADIUS * sqrt(lambdaMax));

    // Rasterizer.projectOne from here.
    float det = a * c - b * b;
    if (!(det > 0.0)) return;
    float u = fx * mx / mz * d + cx;
    float v = fy * my / mz * d + cy;
    if (isnan(u) || isinf(u) || isnan(v) || isinf(v) || !(radius > 0.0)) return;
    if (u + radius < 0.0 || v + radius < 0.0 || u - radius > maxU || v - radius > maxV) return;
    radii[i] = radius >= INT_LIMIT ? 2147483647 : int(radius);
    means2d[2u * i] = u;
    means2d[2u * i + 1u] = v;
    conics[3u * i] = c / det;
    conics[3u * i + 1u] = -b / det;
    conics[3u * i + 2u] = a / det;
    uint h = ${CloudLayout.SH_DC}u * capacity + 3u * i;
    colors[3u * i] = max(0.0, 0.5 + C0 * cloud[h]);
    colors[3u * i + 1u] = max(0.0, 0.5 + C0 * cloud[h + 1u]);
    colors[3u * i + 2u] = max(0.0, 0.5 + C0 * cloud[h + 2u]);
    opacities[i] = 1.0 / (1.0 + exp(-cloud[${CloudLayout.OPACITY_LOGITS}u * capacity + i]));
}
"""
}
