package com.huntercoles.splatman.splat.gpu.raster

import com.huntercoles.splatman.splat.train.raster.Rasterizer

/**
 * The binning kernels of [GpuBinning] and the scan and radix-sort kernels of [GpuScan] and [GpuRadixSort].
 * Keys are `uvec2(tile, depth bits)` ([SortKeys]); every count, offset and value is a `uint`.
 */
object BinShaders {
    /** Invocations per workgroup of every kernel here. */
    const val LOCAL = 256

    private const val HEADER = "#version 310 es\nlayout(local_size_x = $LOCAL) in;\n"

    /** `Rasterizer.tileRect` of splat i: x0, x1, y0, y1 (end exclusive), clamped to the tile grid. */
    private val TILE_RECT = """
uniform int tilesX;
uniform int tilesY;
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
"""

    /** Tiles touched per splat into counts[0..n-1], and counts[n] = 0, so an exclusive scan of n + 1 ends in the total. */
    val COUNT = HEADER + """
layout(std430, binding = 0) readonly buffer Radii { int radii[]; };
layout(std430, binding = 1) readonly buffer Means { float means2d[]; };
layout(std430, binding = 2) writeonly buffer Counts { uint counts[]; };
uniform uint n;
""" + TILE_RECT + """
void main() {
    uint i = gl_GlobalInvocationID.x;
    if (i > n) return;
    if (i == n) {
        counts[i] = 0u;
        return;
    }
    if (radii[i] <= 0) {
        counts[i] = 0u;
        return;
    }
    ivec4 t = tileRect(i);
    counts[i] = uint(max(t.y - t.x, 0) * max(t.w - t.z, 0));
}
"""

    /** One (tile, depth bits) key and the splat index per tile a visible splat touches, at its scanned offset. */
    val EMIT = HEADER + """
layout(std430, binding = 0) readonly buffer Radii { int radii[]; };
layout(std430, binding = 1) readonly buffer Means { float means2d[]; };
layout(std430, binding = 2) readonly buffer Depths { float depths[]; };
layout(std430, binding = 3) readonly buffer Offsets { uint offsets[]; };
layout(std430, binding = 4) writeonly buffer Keys { uvec2 keys[]; };
layout(std430, binding = 5) writeonly buffer Vals { uint vals[]; };
uniform uint n;
""" + TILE_RECT + """
void main() {
    uint i = gl_GlobalInvocationID.x;
    if (i >= n) return;
    if (radii[i] <= 0) return;
    ivec4 t = tileRect(i);
    uint o = offsets[i];
    uint db = floatBitsToUint(depths[i]);
    for (int y = t.z; y < t.w; y++) {
        for (int x = t.x; x < t.y; x++) {
            keys[o] = uvec2(uint(y * tilesX + x), db);
            vals[o] = i;
            o++;
        }
    }
}
"""

    /** tileStart[t] = the first sorted entry whose tile is >= t (a lower bound), for t in 0..tileCount. */
    const val TILE_START = HEADER + """
layout(std430, binding = 0) readonly buffer Keys { uvec2 keys[]; };
layout(std430, binding = 1) writeonly buffer Starts { int tileStart[]; };
uniform uint total;
uniform uint tileCount;
void main() {
    uint t = gl_GlobalInvocationID.x;
    if (t > tileCount) return;
    uint lo = 0u;
    uint hi = total;
    while (lo < hi) {
        uint mid = (lo + hi) / 2u;
        if (keys[mid].x < t) {
            lo = mid + 1u;
        } else {
            hi = mid;
        }
    }
    tileStart[t] = int(lo);
}
"""

    /** The scan steps unrolled: barrier() stays out of loops (GLSL ES 3.10 allows it only in uniform control flow). */
    private val HILLIS_STEELE = (0 until Integer.numberOfTrailingZeros(LOCAL)).joinToString("") { step ->
        val off = 1 shl step
        """
    {
        uint add = l >= ${off}u ? part[l - ${off}u] : 0u;
        memoryBarrierShared();
        barrier();
        part[l] += add;
        memoryBarrierShared();
        barrier();
    }"""
    }

    /**
     * Exclusive scan of each block of [ScanPlan.BLOCK] uints in place (each invocation sums [ScanPlan.PER_THREAD]
     * in a row, then a Hillis-Steele scan of the thread sums in shared memory); the block's total goes to sums[block].
     */
    val SCAN_BLOCKS = HEADER + """
layout(std430, binding = 0) buffer Data { uint data[]; };
layout(std430, binding = 1) writeonly buffer Sums { uint sums[]; };
uniform uint n;
shared uint part[$LOCAL];
void main() {
    uint l = gl_LocalInvocationID.x;
    uint base = gl_WorkGroupID.x * ${ScanPlan.BLOCK}u + l * ${ScanPlan.PER_THREAD}u;
    uint v[${ScanPlan.PER_THREAD}];
    uint sum = 0u;
    for (uint k = 0u; k < ${ScanPlan.PER_THREAD}u; k++) {
        uint idx = base + k;
        v[k] = idx < n ? data[idx] : 0u;
        sum += v[k];
    }
    part[l] = sum;
    memoryBarrierShared();
    barrier();
$HILLIS_STEELE
    uint run = part[l] - sum;
    for (uint k = 0u; k < ${ScanPlan.PER_THREAD}u; k++) {
        uint idx = base + k;
        if (idx < n) data[idx] = run;
        run += v[k];
    }
    if (l == ${LOCAL - 1}u) sums[gl_WorkGroupID.x] = part[l];
}
"""

    /** Adds each block's scanned offset to its elements. */
    val SCAN_ADD = HEADER + """
layout(std430, binding = 0) buffer Data { uint data[]; };
layout(std430, binding = 1) readonly buffer Sums { uint sums[]; };
uniform uint n;
void main() {
    uint i = gl_GlobalInvocationID.x;
    if (i < n) data[i] += sums[i / ${ScanPlan.BLOCK}u];
}
"""

    private const val DIGIT = "((word == 0u ? k.x : k.y) >> shift) & ${SortKeys.BUCKETS - 1}u"

    /** Per block of [SortKeys.BLOCK] keys, how many have each digit, digit-major: hist[digit * blocks + block]. */
    val HISTOGRAM = HEADER + """
layout(std430, binding = 0) readonly buffer Keys { uvec2 keys[]; };
layout(std430, binding = 1) writeonly buffer Hist { uint hist[]; };
uniform uint n;
uniform uint blocks;
uniform uint word;
uniform uint shift;
shared uint counts[${SortKeys.BUCKETS}];
void main() {
    uint l = gl_LocalInvocationID.x;
    uint b = gl_WorkGroupID.x;
    counts[l] = 0u;
    memoryBarrierShared();
    barrier();
    uint idx = b * ${SortKeys.BLOCK}u + l;
    if (idx < n) {
        uvec2 k = keys[idx];
        atomicAdd(counts[$DIGIT], 1u);
    }
    memoryBarrierShared();
    barrier();
    hist[l * blocks + b] = counts[l];
}
"""

    /**
     * Stable scatter of one radix pass: an entry goes to the scanned hist of its (digit, block) plus the number of
     * entries before it in its block with the same digit.
     */
    val SCATTER = HEADER + """
layout(std430, binding = 0) readonly buffer InKeys { uvec2 inKeys[]; };
layout(std430, binding = 1) readonly buffer InVals { uint inVals[]; };
layout(std430, binding = 2) writeonly buffer OutKeys { uvec2 outKeys[]; };
layout(std430, binding = 3) writeonly buffer OutVals { uint outVals[]; };
layout(std430, binding = 4) readonly buffer Hist { uint hist[]; };
uniform uint n;
uniform uint blocks;
uniform uint word;
uniform uint shift;
shared uint digits[${SortKeys.BLOCK}];
void main() {
    uint l = gl_LocalInvocationID.x;
    uint b = gl_WorkGroupID.x;
    uint idx = b * ${SortKeys.BLOCK}u + l;
    uvec2 k = uvec2(0u);
    uint val = 0u;
    uint digit = ${SortKeys.BUCKETS}u;
    if (idx < n) {
        k = inKeys[idx];
        val = inVals[idx];
        digit = $DIGIT;
    }
    digits[l] = digit;
    memoryBarrierShared();
    barrier();
    if (idx < n) {
        uint rank = 0u;
        for (uint j = 0u; j < l; j++) {
            if (digits[j] == digit) rank++;
        }
        uint dst = hist[digit * blocks + b] + rank;
        outKeys[dst] = k;
        outVals[dst] = val;
    }
}
"""
}
