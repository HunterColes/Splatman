package com.huntercoles.splatman.splat.gpu.raster

import com.huntercoles.splatman.splat.gpu.Dispatch
import com.huntercoles.splatman.splat.train.raster.Rasterizer

/**
 * The first half of the GPU backward pass (ADR 0010), stages 1 and 2 of `RasterizerBackward` in three kernels:
 *
 * - [PACK]: one invocation per splat copies its centre, conic, colour and opacity into one [Grads2dLayout] record, so
 *   the replay fits in the 8 SSBO bindings the probe guarantees.
 * - [REPLAY]: one workgroup per 16x16 px tile, one invocation per pixel. Every pixel replays the forward pass's list
 *   back to front from its finalT and contributor count, re-deciding each alpha exactly as [CompositeShaders] did
 *   (no power cut there, so none here) and computing the [Grads2dLayout.ENTRY] screen-space partials of each entry
 *   (dL / d u, v, conic a, b, c, colour r, g, b, opacity, as `SplatGrads.entryGrads`). The pixels walk the entries in
 *   lockstep; per entry the 256 pixel partials are summed in shared memory in a fixed order ([LANES] lanes of
 *   [PER_LANE] pixels, then the lanes), so no float atomics and a deterministic result. Entries no pixel of the tile
 *   reached get zeros.
 * - [GATHER]: one invocation per splat walks its tile rect in tile order (the rect `GpuBinning` emitted it over),
 *   finds its entry in each tile list by binary search on (depth bits, index), the order of the binning sort, and sums
 *   the partials into its [Grads2dLayout.ENTRY] floats (`SplatGrads.splatGrads2d`). No atomics; a culled splat gets 0.
 *
 * Bindings and uniforms are listed on each kernel; dispatch with [replayGroups] and `dispatchFor(n, LOCAL)`.
 */
object BackwardTileShaders {
    /** Workgroup side of [REPLAY]: one invocation per pixel of a tile. */
    const val TILE = Rasterizer.TILE

    /** Pixels (invocations) per [REPLAY] workgroup. */
    const val PIXELS = TILE * TILE

    /** Partial sums per screen-space value in the [REPLAY] reduction, and pixels each lane sums. */
    const val LANES = 16
    const val PER_LANE = PIXELS / LANES

    /** Invocations per workgroup of [PACK] and [GATHER]. */
    const val LOCAL = 256

    /** Shared memory [REPLAY] declares, in bytes (GLES 3.1 guarantees 16384). */
    const val REPLAY_SHARED_BYTES = 4 * (Grads2dLayout.ENTRY * PIXELS + Grads2dLayout.ENTRY * LANES + Grads2dLayout.SPLAT + 1)

    /** Workgroups (x, y) of [REPLAY] for a [width] x [height] image: one per tile. */
    fun replayGroups(width: Int, height: Int): Pair<Int, Int> {
        require(width > 0 && height > 0) { "image size must be positive, got ${width}x$height" }
        return Dispatch.groups(width, TILE) to Dispatch.groups(height, TILE)
    }

    /** A float constant as GLSL that yields exactly its bits (no decimal round trip). */
    private fun bits(f: Float): String = "uintBitsToFloat(0x${Integer.toHexString(java.lang.Float.floatToRawIntBits(f))}u)"

    private const val E = Grads2dLayout.ENTRY

    /**
     * Bindings: 0 means2d (2 floats), 1 conics (3 floats), 2 colours (3 floats), 3 opacities, 4 the packed records
     * ([Grads2dLayout.SPLAT] floats per splat). Uniform `n`.
     */
    val PACK = """#version 310 es
layout(local_size_x = $LOCAL) in;
layout(std430, binding = 0) readonly buffer Means { float means2d[]; };
layout(std430, binding = 1) readonly buffer Conics { float conics[]; };
layout(std430, binding = 2) readonly buffer Colors { float colors[]; };
layout(std430, binding = 3) readonly buffer Opacities { float opacities[]; };
layout(std430, binding = 4) writeonly buffer Packed { float packed[]; };
uniform uint n;
void main() {
    uint i = gl_GlobalInvocationID.x;
    if (i >= n) return;
    uint o = ${Grads2dLayout.SPLAT}u * i;
    packed[o + ${Grads2dLayout.U}u] = means2d[2u * i];
    packed[o + ${Grads2dLayout.V}u] = means2d[2u * i + 1u];
    packed[o + ${Grads2dLayout.CA}u] = conics[3u * i];
    packed[o + ${Grads2dLayout.CB}u] = conics[3u * i + 1u];
    packed[o + ${Grads2dLayout.CC}u] = conics[3u * i + 2u];
    packed[o + ${Grads2dLayout.R}u] = colors[3u * i];
    packed[o + ${Grads2dLayout.R + 1}u] = colors[3u * i + 1u];
    packed[o + ${Grads2dLayout.R + 2}u] = colors[3u * i + 2u];
    packed[o + ${Grads2dLayout.OP}u] = opacities[i];
}
"""

    /**
     * Bindings (7): 0 tileStart (int), 1 tileSplats (uint), 2 the [PACK] records, 3 the image ([FrameLayout]; finalT
     * is read), 4 contributors (int), 5 dL/dColor (3 floats per pixel, as `RenderBuffers.color`), 6 entryGrads
     * ([Grads2dLayout.ENTRY] floats per tile entry, written). Uniforms `tilesX`, `width`, `height`. Dispatch:
     * [replayGroups].
     */
    val REPLAY = """#version 310 es
layout(local_size_x = $TILE, local_size_y = $TILE) in;
layout(std430, binding = 0) readonly buffer TileStart { int tileStart[]; };
layout(std430, binding = 1) readonly buffer TileSplats { uint tileSplats[]; };
layout(std430, binding = 2) readonly buffer Packed { float packed[]; };
layout(std430, binding = 3) readonly buffer Image { float image[]; };
layout(std430, binding = 4) readonly buffer Contributors { int contributors[]; };
layout(std430, binding = 5) readonly buffer DLdColor { float dLdColor[]; };
layout(std430, binding = 6) writeonly buffer EntryGrads { float entryGrads[]; };
uniform uint tilesX;
uniform uint width;
uniform uint height;
const uint PIXELS = ${PIXELS}u;
const uint LANES = ${LANES}u;
const uint PER_LANE = ${PER_LANE}u;

shared float part[${E}u * PIXELS];
shared float lanes[${E}u * LANES];
shared float sSplat[${Grads2dLayout.SPLAT}];
shared uint maxCount;

void main() {
    float alphaMax = ${bits(Rasterizer.ALPHA_MAX)};
    float alphaMin = ${bits(Rasterizer.ALPHA_MIN)};
    uint tid = gl_LocalInvocationIndex;
    uint tile = gl_WorkGroupID.y * tilesX + gl_WorkGroupID.x;
    uint x = gl_WorkGroupID.x * ${TILE}u + gl_LocalInvocationID.x;
    uint y = gl_WorkGroupID.y * ${TILE}u + gl_LocalInvocationID.y;
    float px = float(x);
    float py = float(y);
    uint begin = uint(tileStart[tile]);
    uint end = uint(tileStart[tile + 1u]);

    // A pixel outside the image, with no contributor or with a zero colour gradient adds nothing.
    uint count = 0u;
    float tr = 1.0;
    vec3 g = vec3(0.0);
    if (x < width && y < height) {
        uint p = y * width + x;
        g = vec3(dLdColor[3u * p], dLdColor[3u * p + 1u], dLdColor[3u * p + 2u]);
        if (g.x != 0.0 || g.y != 0.0 || g.z != 0.0) {
            count = uint(contributors[p]);
            tr = image[${FrameLayout.FINAL_T}u * width * height + p];
        }
    }
    if (tid == 0u) maxCount = 0u;
    memoryBarrierShared();
    barrier();
    atomicMax(maxCount, count);
    memoryBarrierShared();
    barrier();
    // The same for the whole workgroup, so the loop below and its barriers stay in uniform control flow.
    uint top = min(maxCount, end - begin);
    for (uint k = begin + top + tid; k < end; k += PIXELS) {
        for (uint j = 0u; j < ${E}u; j++) entryGrads[${E}u * k + j] = 0.0;
    }

    // Colour composited by the splats behind the current one, sum of c_j alpha_j T_j.
    vec3 behind = vec3(0.0);
    for (uint li = top; li > 0u; li--) {
        uint e = begin + li - 1u;
        if (tid < ${Grads2dLayout.SPLAT}u) sSplat[tid] = packed[${Grads2dLayout.SPLAT}u * tileSplats[e] + tid];
        memoryBarrierShared();
        barrier();

        float gu = 0.0;
        float gv = 0.0;
        float gca = 0.0;
        float gcb = 0.0;
        float gcc = 0.0;
        vec3 gc = vec3(0.0);
        float gop = 0.0;
        if (li <= count) {
            float ca = sSplat[${Grads2dLayout.CA}];
            float cb = sSplat[${Grads2dLayout.CB}];
            float cc = sSplat[${Grads2dLayout.CC}];
            float op = sSplat[${Grads2dLayout.OP}];
            float dx = sSplat[${Grads2dLayout.U}] - px;
            float dy = sSplat[${Grads2dLayout.V}] - py;
            // The forward pass's decision, in its own arithmetic (CompositeShaders).
            float power = -0.5 * (ca * dx * dx + cc * dy * dy) - cb * dx * dy;
            if (power <= 0.0) {
                float gauss = exp(power);
                float raw = op * gauss;
                float alpha = min(alphaMax, raw);
                if (alpha >= alphaMin) {
                    bool capped = raw > alphaMax;
                    float inv = 1.0 / (1.0 - alpha);
                    tr *= inv;
                    float w = alpha * tr;
                    vec3 c = vec3(sSplat[${Grads2dLayout.R}], sSplat[${Grads2dLayout.R + 1}], sSplat[${Grads2dLayout.R + 2}]);
                    float dLdAlpha = dot(g, c * tr - behind * inv);
                    behind += c * w;
                    gc = w * g;
                    if (!capped) {
                        gop = gauss * dLdAlpha;
                        float dLdPower = dLdAlpha * alpha;
                        gca = -0.5 * dx * dx * dLdPower;
                        gcb = -dx * dy * dLdPower;
                        gcc = -0.5 * dy * dy * dLdPower;
                        gu = -(ca * dx + cb * dy) * dLdPower;
                        gv = -(cc * dy + cb * dx) * dLdPower;
                    }
                }
            }
        }
        part[${Grads2dLayout.U}u * PIXELS + tid] = gu;
        part[${Grads2dLayout.V}u * PIXELS + tid] = gv;
        part[${Grads2dLayout.CA}u * PIXELS + tid] = gca;
        part[${Grads2dLayout.CB}u * PIXELS + tid] = gcb;
        part[${Grads2dLayout.CC}u * PIXELS + tid] = gcc;
        part[${Grads2dLayout.R}u * PIXELS + tid] = gc.x;
        part[${Grads2dLayout.R + 1}u * PIXELS + tid] = gc.y;
        part[${Grads2dLayout.R + 2}u * PIXELS + tid] = gc.z;
        part[${Grads2dLayout.OP}u * PIXELS + tid] = gop;
        memoryBarrierShared();
        barrier();

        if (tid < ${E}u * LANES) {
            uint v = tid / LANES;
            uint lane = tid - v * LANES;
            uint from = v * PIXELS + lane * PER_LANE;
            float s = 0.0;
            for (uint k = 0u; k < PER_LANE; k++) s += part[from + k];
            lanes[tid] = s;
        }
        memoryBarrierShared();
        barrier();

        if (tid < ${E}u) {
            float s = 0.0;
            for (uint lane = 0u; lane < LANES; lane++) s += lanes[tid * LANES + lane];
            entryGrads[${E}u * e + tid] = s;
        }
    }
}
"""

    /**
     * Bindings (7): 0 radii (int), 1 means2d (2 floats), 2 depths, 3 tileStart (int), 4 tileSplats (uint),
     * 5 entryGrads, 6 splatGrads2d ([Grads2dLayout.ENTRY] floats per splat, written). Uniforms `n`, `tilesX`, `tilesY`.
     */
    val GATHER = """#version 310 es
layout(local_size_x = $LOCAL) in;
layout(std430, binding = 0) readonly buffer Radii { int radii[]; };
layout(std430, binding = 1) readonly buffer Means { float means2d[]; };
layout(std430, binding = 2) readonly buffer Depths { float depths[]; };
layout(std430, binding = 3) readonly buffer TileStart { int tileStart[]; };
layout(std430, binding = 4) readonly buffer TileSplats { uint tileSplats[]; };
layout(std430, binding = 5) readonly buffer EntryGrads { float entryGrads[]; };
layout(std430, binding = 6) writeonly buffer SplatGrads { float splatGrads[]; };
uniform uint n;
uniform int tilesX;
uniform int tilesY;

// BinShaders' tileRect (Rasterizer.tileRect): x0, x1, y0, y1, end exclusive, clamped to the tile grid.
ivec4 tileRect(uint i) {
    float r = float(radii[i]);
    float u = means2d[2u * i];
    float v = means2d[2u * i + 1u];
    float tx = float(tilesX);
    float ty = float(tilesY);
    return ivec4(
        int(clamp(floor((u - r) / ${Rasterizer.TILE}.0), 0.0, tx)),
        int(clamp(floor((u + r) / ${Rasterizer.TILE}.0) + 1.0, 0.0, tx)),
        int(clamp(floor((v - r) / ${Rasterizer.TILE}.0), 0.0, ty)),
        int(clamp(floor((v + r) / ${Rasterizer.TILE}.0) + 1.0, 0.0, ty)));
}

void main() {
    uint i = gl_GlobalInvocationID.x;
    if (i >= n) return;
    float sum[${E}];
    for (int j = 0; j < $E; j++) sum[j] = 0.0;
    if (radii[i] > 0) {
        ivec4 t = tileRect(i);
        uint key = floatBitsToUint(depths[i]);
        for (int ty = t.z; ty < t.w; ty++) {
            for (int tx = t.x; tx < t.y; tx++) {
                uint tile = uint(ty * tilesX + tx);
                uint lo = uint(tileStart[tile]);
                uint end = uint(tileStart[tile + 1u]);
                uint hi = end;
                // Lower bound of (key, i) in the list's (depth bits, index) order.
                while (lo < hi) {
                    uint mid = (lo + hi) / 2u;
                    uint s = tileSplats[mid];
                    uint k = floatBitsToUint(depths[s]);
                    if (k < key || (k == key && s < i)) {
                        lo = mid + 1u;
                    } else {
                        hi = mid;
                    }
                }
                if (lo < end && tileSplats[lo] == i) {
                    for (uint j = 0u; j < ${E}u; j++) sum[j] += entryGrads[${E}u * lo + j];
                }
            }
        }
    }
    for (uint j = 0u; j < ${E}u; j++) splatGrads[${E}u * i + j] = sum[j];
}
"""
}

/**
 * The screen-space gradient layout shared by [BackwardTileShaders] and [GpuGrads2d]: [ENTRY] floats per tile entry
 * (entryGrads) and per splat (splatGrads2d), in `SplatGrads.ENTRY` order; the [PACK][BackwardTileShaders.PACK] record
 * of a splat uses the same slots for its centre, conic, colour and opacity.
 */
object Grads2dLayout {
    const val ENTRY = 9
    const val U = 0
    const val V = 1
    const val CA = 2
    const val CB = 3
    const val CC = 4
    const val R = 5
    const val OP = 8

    /** Floats per packed splat record. */
    const val SPLAT = ENTRY
}
