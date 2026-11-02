package com.huntercoles.splatman.viewer.rendering.gl

/**
 * GLSL ES 3.00 sources of the splat program (ADR 0006). One instance per splat, a 4-vertex triangle strip per
 * instance; `a_index` is the instanced `u32` from the back-to-front order buffer.
 *
 * The vertex shader fetches the two texels of [com.huntercoles.splatman.viewer.rendering.pack.PackedSplats], moves
 * the world covariance into the GL camera frame (`W Sigma W^T`), projects it with the EWA Jacobian of
 * `u = fx x / d, v = fy y / d` (`d = -z`, pixels, y up), adds 0.3 px^2 to the diagonal and spans the quad over +-3
 * sigma along the eigenvectors (each half-axis capped at 1024 px). Splats behind the near plane or with a centre
 * outside 1.2x the frustum are culled. `v_pos` is the fragment's offset in sigma units along the two axes, so the
 * fragment's Gaussian is `exp(-0.5 |v_pos|^2)` even when the cap shortened an axis.
 */
object SplatShaders {
    const val ATTR_CORNER = 0
    const val ATTR_INDEX = 1

    val VERTEX = """
        #version 300 es
        precision highp float;
        precision highp int;
        precision highp usampler2D;

        uniform usampler2D u_splats;
        uniform mat4 u_view;
        uniform mat4 u_proj;
        uniform vec2 u_focal;
        uniform vec2 u_viewport;

        layout(location = 0) in vec2 a_corner;
        layout(location = 1) in uint a_index;

        out vec4 v_color;
        out vec2 v_pos;

        void cull() {
            gl_Position = vec4(0.0, 0.0, 2.0, 1.0);
            v_color = vec4(0.0);
            v_pos = vec2(0.0);
        }

        void main() {
            uint texel = a_index * 2u;
            ivec2 t0 = ivec2(int(texel & 2047u), int(texel >> 11u));
            uvec4 c = texelFetch(u_splats, t0, 0);
            uvec4 s = texelFetch(u_splats, ivec2(t0.x + 1, t0.y), 0);

            vec4 cam = u_view * vec4(uintBitsToFloat(c.xyz), 1.0);
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
            vec2 offsetPx = a_corner.x * r1 * axis + a_corner.y * r2 * vec2(-axis.y, axis.x);
            vec2 ndc = clip.xy / clip.w + offsetPx * 2.0 / u_viewport;
            gl_Position = vec4(ndc, clip.z / clip.w, 1.0);

            v_pos = a_corner * vec2(r1 / sigma1, r2 / sigma2);
            v_color = vec4(
                float(c.w & 255u), float((c.w >> 8u) & 255u), float((c.w >> 16u) & 255u), float(c.w >> 24u)
            ) / 255.0;
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

    /** Triangle-strip corners of the unit quad, in units of the quad's half-axes. */
    val QUAD = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
}
