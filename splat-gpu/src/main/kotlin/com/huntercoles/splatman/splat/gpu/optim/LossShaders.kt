package com.huntercoles.splatman.splat.gpu.optim

/**
 * The L1 loss on the GPU (ADR 0010), the same sum as `CpuBackend.l1`: mean |rendered - target| over pixels and
 * channels, and dL/dColor = sign(rendered - target) / size. Two passes, no atomics, so the sum is deterministic:
 * [PARTIALS] sums each workgroup's [LOCAL] elements by a fixed tree in shared memory, [TOTAL] sums the partials in one
 * workgroup (each invocation strides over them in order, then the same tree).
 *
 * `rendered` is the interleaved RGB colour of `RenderBuffers.color`; the target is one packed RGBA8 `uint` per pixel
 * (r in the low byte, [GpuTargets]), turned into a float through a 256-entry table that Kotlin fills with `b / 255f`,
 * so the GPU target equals the CPU's bit for bit.
 */
internal object LossShaders {
    const val LOCAL = 256

    const val B_RENDERED = 0
    const val B_TARGET = 1
    const val B_LUT = 2
    const val B_DLDCOLOR = 3
    const val B_PARTIALS = 4
    const val B_TOTAL = 5

    /** Shared-memory tree over the workgroup's `a`s, the same order every run; leaves the sum in `sums[0]`. */
    private const val TREE = """
    sums[t] = a;
    memoryBarrierShared();
    barrier();
    for (uint s = 128u; s > 0u; s >>= 1u) {
        if (t < s) sums[t] = sums[t] + sums[t + s];
        memoryBarrierShared();
        barrier();
    }
"""

    const val PARTIALS = """#version 310 es
precision highp float;
precision highp int;
layout(local_size_x = 256) in;
layout(std430, binding = 0) readonly buffer Rendered { float rendered[]; };
layout(std430, binding = 1) readonly buffer Target { uint target[]; };
layout(std430, binding = 2) readonly buffer Lut { float lut[]; };
layout(std430, binding = 3) writeonly buffer DLdColor { float dLdColor[]; };
layout(std430, binding = 4) writeonly buffer Partials { float partials[]; };
uniform uint size;
uniform float scale;
shared float sums[256];
void main() {
    uint j = gl_GlobalInvocationID.x;
    uint t = gl_LocalInvocationID.x;
    float a = 0.0;
    if (j < size) {
        uint px = target[j / 3u];
        float tv = lut[(px >> (8u * (j % 3u))) & 0xFFu];
        float d = rendered[j] - tv;
        a = abs(d);
        dLdColor[j] = d > 0.0 ? scale : (d < 0.0 ? -scale : 0.0);
    }
$TREE
    if (t == 0u) partials[gl_WorkGroupID.x] = sums[0];
}
"""

    const val TOTAL = """#version 310 es
precision highp float;
precision highp int;
layout(local_size_x = 256) in;
layout(std430, binding = 4) readonly buffer Partials { float partials[]; };
layout(std430, binding = 5) writeonly buffer Total { float total[]; };
uniform uint count;
shared float sums[256];
void main() {
    uint t = gl_LocalInvocationID.x;
    float a = 0.0;
    for (uint i = t; i < count; i += 256u) a += partials[i];
$TREE
    if (t == 0u) total[0] = sums[0];
}
"""
}
