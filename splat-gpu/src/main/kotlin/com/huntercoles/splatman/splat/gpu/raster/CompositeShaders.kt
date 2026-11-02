package com.huntercoles.splatman.splat.gpu.raster

import com.huntercoles.splatman.splat.gpu.Dispatch
import com.huntercoles.splatman.splat.train.raster.Rasterizer

/**
 * The compositing kernel of [GpuRasterizer]: `Rasterizer.compositeTile` with one workgroup per 16x16 px tile and one
 * invocation per pixel (ADR 0010). The tile's front-to-back list is walked in batches of [BATCH] entries: each
 * invocation loads one splat's centre, conic, opacity and colour into shared memory, then every pixel composites the
 * batch in list order. Same rules as the CPU: a positive exponent is skipped, alpha is `min(0.99, opacity * exp)`,
 * alphas under 1/255 are skipped, a pixel stops before its transmittance would drop under 1e-4, black background.
 * A pixel outside the image (the last tile row or column may be partial) only helps load. The workgroup leaves the
 * list early once every pixel has stopped.
 *
 * Outputs, per pixel `p = y * width + x`: the [FrameLayout] sections of the image buffer (colour r, g, b
 * premultiplied, alpha `1 - finalT`, finalT) and the contributor count (entries walked up to and including the last
 * that contributed, as `RenderBuffers.contributors`).
 *
 * Bindings (8, the probe's floor): 0 tileStart (int), 1 tileSplats (uint), 2 means2d (2 floats), 3 conics
 * (3 floats), 4 colours (3 floats), 5 opacities, 6 the image (floats, [FrameLayout]), 7 contributors (int).
 * Uniforms: `tilesX`, `width`, `height`. Dispatch: [groups].
 */
object CompositeShaders {
    /** Workgroup side: one invocation per pixel of a tile. */
    const val LOCAL = Rasterizer.TILE

    /** Splats loaded into shared memory per batch: one per invocation. */
    const val BATCH = LOCAL * LOCAL

    /** Workgroups (x, y) for a [width] x [height] image: one per tile, the tile grid of `RenderBuffers`. */
    fun groups(width: Int, height: Int): Pair<Int, Int> {
        require(width > 0 && height > 0) { "image size must be positive, got ${width}x$height" }
        return Dispatch.groups(width, LOCAL) to Dispatch.groups(height, LOCAL)
    }

    /** A float constant as GLSL that yields exactly its bits (no decimal round trip). */
    private fun bits(f: Float): String = "uintBitsToFloat(0x${Integer.toHexString(java.lang.Float.floatToRawIntBits(f))}u)"

    val COMPOSITE = """#version 310 es
layout(local_size_x = $LOCAL, local_size_y = $LOCAL) in;
layout(std430, binding = 0) readonly buffer TileStart { int tileStart[]; };
layout(std430, binding = 1) readonly buffer TileSplats { uint tileSplats[]; };
layout(std430, binding = 2) readonly buffer Means { float means2d[]; };
layout(std430, binding = 3) readonly buffer Conics { float conics[]; };
layout(std430, binding = 4) readonly buffer Colors { float colors[]; };
layout(std430, binding = 5) readonly buffer Opacities { float opacities[]; };
layout(std430, binding = 6) writeonly buffer Image { float image[]; };
layout(std430, binding = 7) writeonly buffer Contributors { int contributors[]; };
uniform uint tilesX;
uniform uint width;
uniform uint height;
const uint BATCH = ${BATCH}u;

shared vec2 sMean[BATCH];
shared vec4 sConicOpacity[BATCH];
shared vec3 sColor[BATCH];
shared uint doneCount;

void main() {
    float alphaMax = ${bits(Rasterizer.ALPHA_MAX)};
    float alphaMin = ${bits(Rasterizer.ALPHA_MIN)};
    float tMin = ${bits(Rasterizer.T_MIN)};
    uint tid = gl_LocalInvocationIndex;
    uint tile = gl_WorkGroupID.y * tilesX + gl_WorkGroupID.x;
    uint x = gl_WorkGroupID.x * ${LOCAL}u + gl_LocalInvocationID.x;
    uint y = gl_WorkGroupID.y * ${LOCAL}u + gl_LocalInvocationID.y;
    bool inside = x < width && y < height;
    float px = float(x);
    float py = float(y);
    // The same for the whole workgroup, so the loop below and its barriers stay in uniform control flow.
    uint begin = uint(tileStart[tile]);
    uint end = uint(tileStart[tile + 1u]);

    float tr = 1.0;
    float r = 0.0;
    float g = 0.0;
    float b = 0.0;
    uint last = 0u;
    bool done = !inside;
    if (tid == 0u) doneCount = 0u;
    memoryBarrierShared();
    barrier();
    for (uint base = begin; base < end; base += BATCH) {
        if (done) atomicAdd(doneCount, 1u);
        uint k = base + tid;
        if (k < end) {
            uint s = tileSplats[k];
            sMean[tid] = vec2(means2d[2u * s], means2d[2u * s + 1u]);
            sConicOpacity[tid] = vec4(conics[3u * s], conics[3u * s + 1u], conics[3u * s + 2u], opacities[s]);
            sColor[tid] = vec3(colors[3u * s], colors[3u * s + 1u], colors[3u * s + 2u]);
        }
        memoryBarrierShared();
        barrier();
        uint finished = doneCount;
        memoryBarrierShared();
        barrier();
        if (finished == BATCH) break;
        // Everyone has read it; the next adds come after the barrier at the end of this batch.
        if (tid == 0u) doneCount = 0u;
        uint m = min(BATCH, end - base);
        for (uint j = 0u; j < m && !done; j++) {
            vec2 mean = sMean[j];
            vec4 co = sConicOpacity[j];
            float dx = mean.x - px;
            float dy = mean.y - py;
            float power = -0.5 * (co.x * dx * dx + co.z * dy * dy) - co.y * dx * dy;
            if (power > 0.0) continue;
            float alpha = min(alphaMax, co.w * exp(power));
            if (alpha < alphaMin) continue;
            float next = tr * (1.0 - alpha);
            if (next < tMin) {
                done = true;
                break;
            }
            float w = alpha * tr;
            vec3 c = sColor[j];
            r += c.x * w;
            g += c.y * w;
            b += c.z * w;
            tr = next;
            last = base - begin + j + 1u;
        }
        memoryBarrierShared();
        barrier();
    }
    if (!inside) return;
    uint p = y * width + x;
    uint pixels = width * height;
    image[${FrameLayout.COLOR}u * pixels + 3u * p] = r;
    image[${FrameLayout.COLOR}u * pixels + 3u * p + 1u] = g;
    image[${FrameLayout.COLOR}u * pixels + 3u * p + 2u] = b;
    image[${FrameLayout.ALPHA}u * pixels + p] = 1.0 - tr;
    image[${FrameLayout.FINAL_T}u * pixels + p] = tr;
    contributors[p] = int(last);
}
"""
}
