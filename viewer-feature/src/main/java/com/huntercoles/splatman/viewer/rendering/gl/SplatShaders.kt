package com.huntercoles.splatman.viewer.rendering.gl

/**
 * GLSL ES 3.00 sources of the splat program (ADR 0006). Four vertices per splat, no instancing: the renderer
 * draws [CHUNK] splats per indexed draw ([chunkIndices]: two triangles per quad) with `u_base` the chunk's first
 * slot, so `gl_VertexID` (the element index) is `4 * (slot - u_base) + corner`. The splat drawn in a slot is read
 * from `u_order`, the back-to-front order as an R32UI texture [ORDER_WIDTH] texels wide (slot `s` at
 * `(s % ORDER_WIDTH, s / ORDER_WIDTH)`). Instancing is avoided because SwiftShader (the emulator's GL) runs each
 * instance as a draw of its own: 450k splats took about 10 s a frame at any size (#262).
 *
 * The vertex shader fetches the two texels of [com.huntercoles.splatman.viewer.rendering.pack.PackedSplats], moves
 * the world covariance into the GL camera frame (`W Sigma W^T`), projects it with the EWA Jacobian of
 * `u = fx x / d, v = fy y / d` (`d = -z`, pixels, y up), adds 0.3 px^2 to the diagonal and spans the quad over +-3
 * sigma along the eigenvectors (each half-axis capped at 1024 px). Splats behind the near plane or with a centre
 * outside 1.2x the frustum are culled. `v_pos` is the fragment's offset in sigma units along the two axes, so the
 * fragment's Gaussian is `exp(-0.5 |v_pos|^2)` even when the cap shortened an axis.
 *
 * Crop box (ADR 0011): `u_cropAxes` holds the box's local axes in W as columns, so `(p - u_cropCentre) * u_cropAxes`
 * is the centre in box coordinates. A splat whose centre is not inside (`|local| <= u_cropHalf` per axis, boundary
 * inclusive, NaN outside, the same test as `CropBox.contains`) has its alpha multiplied by `u_cropDim`; with no box
 * the renderer sets `u_cropDim = 1.0` and the test is skipped, so the output is unchanged.
 *
 * Erase mask (ADR 0014 rule 4): with `u_maskOn == 1`, `u_mask` is an R8UI texture [MASK_WIDTH] texels wide, one per
 * splat index (splat `i` at `(i % MASK_WIDTH, i / MASK_WIDTH)`). A nonzero texel mixes `u_eraseTint` into the colour
 * at `u_eraseMix` and multiplies alpha by `u_eraseAlpha`. With `u_maskOn == 0` nothing is fetched. The mask and the
 * crop dim never apply together: with the mask on, the crop test is skipped.
 */
object SplatShaders {
    /** Splats per indexed draw: the largest whose four vertices per splat fit `GL_UNSIGNED_SHORT` indices. */
    const val CHUNK = 16384

    /** Texels per row of the order texture (`slot & 2047`, `slot >> 11` in [VERTEX]). */
    const val ORDER_WIDTH = 2048

    /** Texels per row of the erase mask texture (`splat & 2047`, `splat >> 11` in [VERTEX]). */
    const val MASK_WIDTH = 2048

    /** Indices per splat in [chunkIndices]. */
    const val INDICES_PER_SPLAT = 6

    /** The element indices of one chunk: splat `s` is the triangles (4s, 4s+1, 4s+2) and (4s+2, 4s+1, 4s+3). */
    fun chunkIndices(): ShortArray = ShortArray(CHUNK * INDICES_PER_SPLAT) { i ->
        val v = 4 * (i / INDICES_PER_SPLAT)
        (v + CORNERS[i % INDICES_PER_SPLAT]).toShort()
    }

    private val CORNERS = intArrayOf(0, 1, 2, 2, 1, 3)

    val VERTEX = """
        #version 300 es
        precision highp float;
        precision highp int;
        precision highp usampler2D;

        uniform usampler2D u_splats;
        uniform usampler2D u_order;
        uniform int u_base;
        uniform mat4 u_view;
        uniform mat4 u_proj;
        uniform vec2 u_focal;
        uniform vec2 u_viewport;
        uniform float u_cropDim;
        uniform vec3 u_cropCentre;
        uniform mat3 u_cropAxes;
        uniform vec3 u_cropHalf;
        uniform usampler2D u_mask;
        uniform int u_maskOn;
        uniform vec3 u_eraseTint;
        uniform float u_eraseMix;
        uniform float u_eraseAlpha;

        out vec4 v_color;
        out vec2 v_pos;

        void cull() {
            gl_Position = vec4(0.0, 0.0, 2.0, 1.0);
            v_color = vec4(0.0);
            v_pos = vec2(0.0);
        }

        void main() {
            int corner = gl_VertexID & 3;
            int slot = u_base + (gl_VertexID >> 2);
            vec2 cornerXy = vec2((corner & 1) == 0 ? -1.0 : 1.0, (corner & 2) == 0 ? -1.0 : 1.0);
            uint splat = texelFetch(u_order, ivec2(slot & 2047, slot >> 11), 0).r;
            uint texel = splat * 2u;
            ivec2 t0 = ivec2(int(texel & 2047u), int(texel >> 11u));
            uvec4 c = texelFetch(u_splats, t0, 0);
            uvec4 s = texelFetch(u_splats, ivec2(t0.x + 1, t0.y), 0);

            vec3 centre = uintBitsToFloat(c.xyz);
            vec4 cam = u_view * vec4(centre, 1.0);
            vec4 clip = u_proj * cam;
            float d = -cam.z;
            float bound = 1.2 * clip.w;
            if (clip.w <= 0.0 || clip.z < -clip.w || abs(clip.x) > bound || abs(clip.y) > bound) {
                cull();
                return;
            }

            vec2 xxXy = unpackHalf2x16(s.x);
            vec2 xzYy = unpackHalf2x16(s.y);
            vec2 yzZz = unpackHalf2x16(s.z);
            mat3 sigma = mat3(
                xxXy.x, xxXy.y, xzYy.x,
                xxXy.y, xzYy.y, yzZz.x,
                xzYy.x, yzZz.x, yzZz.y
            );
            mat3 w = mat3(u_view);
            mat3 sigmaCam = w * sigma * transpose(w);
            mat3 j = mat3(
                u_focal.x / d, 0.0, 0.0,
                0.0, u_focal.y / d, 0.0,
                u_focal.x * cam.x / (d * d), u_focal.y * cam.y / (d * d), 0.0
            );
            mat3 cov = j * sigmaCam * transpose(j);
            float a = cov[0][0] + 0.3;
            float b = cov[0][1];
            float e = cov[1][1] + 0.3;

            float mid = 0.5 * (a + e);
            float radius = length(vec2(0.5 * (a - e), b));
            float lambda1 = mid + radius;
            float lambda2 = mid - radius;
            if (lambda2 <= 0.0) {
                cull();
                return;
            }
            vec2 axis = vec2(b, lambda1 - a);
            float len = length(axis);
            axis = len > 1e-12 ? axis / len : (a >= e ? vec2(1.0, 0.0) : vec2(0.0, 1.0));

            float sigma1 = sqrt(lambda1);
            float sigma2 = sqrt(lambda2);
            float r1 = min(3.0 * sigma1, 1024.0);
            float r2 = min(3.0 * sigma2, 1024.0);
            vec2 offsetPx = cornerXy.x * r1 * axis + cornerXy.y * r2 * vec2(-axis.y, axis.x);
            vec2 ndc = clip.xy / clip.w + offsetPx * 2.0 / u_viewport;
            gl_Position = vec4(ndc, clip.z / clip.w, 1.0);

            v_pos = cornerXy * vec2(r1 / sigma1, r2 / sigma2);
            v_color = vec4(
                float(c.w & 255u), float((c.w >> 8u) & 255u), float((c.w >> 16u) & 255u), float(c.w >> 24u)
            ) / 255.0;
            if (u_maskOn == 1) {
                uint marked = texelFetch(u_mask, ivec2(int(splat & 2047u), int(splat >> 11u)), 0).r;
                if (marked != 0u) {
                    v_color.rgb = mix(v_color.rgb, u_eraseTint, u_eraseMix);
                    v_color.a *= u_eraseAlpha;
                }
            } else if (u_cropDim < 1.0) {
                vec3 local = abs((centre - u_cropCentre) * u_cropAxes);
                if (!all(lessThanEqual(local, u_cropHalf))) v_color.a *= u_cropDim;
            }
        }
    """.trimIndent()

    val FRAGMENT = """
        #version 300 es
        precision highp float;

        in vec4 v_color;
        in vec2 v_pos;
        out vec4 fragColor;

        void main() {
            float alpha = v_color.a * exp(-0.5 * dot(v_pos, v_pos));
            if (alpha < 1.0 / 255.0) discard;
            fragColor = vec4(v_color.rgb * alpha, alpha);
        }
    """.trimIndent()
}
