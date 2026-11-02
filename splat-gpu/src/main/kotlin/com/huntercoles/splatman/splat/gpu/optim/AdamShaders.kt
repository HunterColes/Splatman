package com.huntercoles.splatman.splat.gpu.optim

/**
 * Adam for one parameter group and the view-gradient statistics on the GPU (ADR 0010). One invocation per element,
 * no shared state, so both are deterministic.
 *
 * [ADAM] is `Adam.step` in fp32: the scalars (lr, beta1, beta2, 1 - beta1, 1 - beta2, eps and the bias corrections) are
 * computed in Double by Kotlin ([AdamUniforms]) and rounded once to float uniforms. 1 - beta2 is passed, not formed in
 * the shader: `1.0 - 0.999` in fp32 is 4.7e-5 relative off, which would break the 1e-6 parity on v.
 *
 * [STATS] is the densifier's statistic, as `CpuBackend.step` keeps it: for each splat with radius > 0,
 * `gradSum += viewGradNorms` and `gradViews++`.
 */
internal object AdamShaders {
    const val LOCAL = 256

    const val B_PARAMS = 0
    const val B_GRADS = 1
    const val B_M = 2
    const val B_V = 3

    const val ADAM = """#version 310 es
precision highp float;
precision highp int;
layout(local_size_x = 256) in;
layout(std430, binding = 0) buffer Params { float params[]; };
layout(std430, binding = 1) readonly buffer Grads { float grads[]; };
layout(std430, binding = 2) buffer M { float m[]; };
layout(std430, binding = 3) buffer V { float v[]; };
uniform uint n;
uniform float lr;
uniform float beta1;
uniform float beta2;
uniform float oneMinusBeta1;
uniform float oneMinusBeta2;
uniform float eps;
uniform float bc1;
uniform float bc2;
void main() {
    uint j = gl_GlobalInvocationID.x;
    if (j >= n) return;
    float g = grads[j];
    float mj = beta1 * m[j] + oneMinusBeta1 * g;
    float vj = beta2 * v[j] + oneMinusBeta2 * (g * g);
    m[j] = mj;
    v[j] = vj;
    params[j] = params[j] - lr * (mj / bc1) / (sqrt(vj / bc2) + eps);
}
"""

    const val B_RADII = 0
    const val B_VIEW_GRAD_NORMS = 1
    const val B_GRAD_SUM = 2
    const val B_GRAD_VIEWS = 3

    const val STATS = """#version 310 es
precision highp float;
precision highp int;
layout(local_size_x = 256) in;
layout(std430, binding = 0) readonly buffer Radii { int radii[]; };
layout(std430, binding = 1) readonly buffer ViewGradNorms { float viewGradNorms[]; };
layout(std430, binding = 2) buffer GradSum { float gradSum[]; };
layout(std430, binding = 3) buffer GradViews { int gradViews[]; };
uniform uint n;
void main() {
    uint i = gl_GlobalInvocationID.x;
    if (i >= n) return;
    if (radii[i] > 0) {
        gradSum[i] = gradSum[i] + viewGradNorms[i];
        gradViews[i] = gradViews[i] + 1;
    }
}
"""
}
