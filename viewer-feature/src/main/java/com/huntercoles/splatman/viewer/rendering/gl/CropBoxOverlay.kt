package com.huntercoles.splatman.viewer.rendering.gl

import com.huntercoles.splatman.core.design.SplatDarkColorScheme
import com.huntercoles.splatman.splat.edit.CropBox
import com.huntercoles.splatman.splat.math.Vec3
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Draws a [CropBox] over the splats in the GL pass: translucent faces, then opaque edges, both in the `primary` token
 * of [SplatDarkColorScheme] (the Viewer is always dark). Drawn after the splats with the renderer's state (no depth
 * test, premultiplied blending), so the whole box is always visible.
 *
 * The geometry is one static VBO of the unit cube (+-1 on each axis: 36 face vertices, then 24 edge vertices); a box
 * is only a model matrix uniform ([modelOf]), so changing it uploads nothing. GL thread only; [create] again after a
 * context loss.
 */
internal class CropBoxOverlay(private val gl: Gl) {
    private var program = 0
    private var uModel = -1
    private var uView = -1
    private var uProj = -1
    private var uColor = -1
    private var vao = 0
    private var vbo = 0

    /** Builds the program and the cube buffer on a new context (old names are dropped, not deleted). */
    fun create() {
        program = buildProgram()
        uModel = gl.getUniformLocation(program, "u_model")
        uView = gl.getUniformLocation(program, "u_view")
        uProj = gl.getUniformLocation(program, "u_proj")
        uColor = gl.getUniformLocation(program, "u_color")
        vao = gl.genVertexArray()
        gl.bindVertexArray(vao)
        vbo = gl.genBuffer()
        gl.bindBuffer(GlConst.ARRAY_BUFFER, vbo)
        val bytes = ByteBuffer.allocateDirect(CUBE.size * 4).order(ByteOrder.nativeOrder())
        bytes.asFloatBuffer().put(CUBE)
        gl.bufferData(GlConst.ARRAY_BUFFER, CUBE.size * 4, bytes, GlConst.STATIC_DRAW)
        gl.enableVertexAttribArray(ATTR_POSITION)
        gl.vertexAttribPointer(ATTR_POSITION, 3, GlConst.FLOAT, 0, 0)
        gl.bindVertexArray(0)
        gl.bindBuffer(GlConst.ARRAY_BUFFER, 0)
    }

    /** Draws the box given by [model] (from [modelOf]) with the frame's [view] and [proj]. */
    fun draw(model: FloatArray, view: FloatArray, proj: FloatArray) {
        if (program == 0) return
        gl.useProgram(program)
        gl.uniformMatrix4fv(uModel, model)
        gl.uniformMatrix4fv(uView, view)
        gl.uniformMatrix4fv(uProj, proj)
        gl.bindVertexArray(vao)
        gl.uniform4f(uColor, COLOR[0], COLOR[1], COLOR[2], FACE_ALPHA)
        gl.drawArrays(GlConst.TRIANGLES, 0, FACE_VERTICES)
        gl.uniform4f(uColor, COLOR[0], COLOR[1], COLOR[2], 1f)
        gl.drawArrays(GlConst.LINES, FACE_VERTICES, EDGE_VERTICES)
        gl.bindVertexArray(0)
    }

    private fun buildProgram(): Int {
        val vs = compile(GlConst.VERTEX_SHADER, VERTEX)
        val fs = compile(GlConst.FRAGMENT_SHADER, FRAGMENT)
        val p = gl.createProgram()
        gl.attachShader(p, vs)
        gl.attachShader(p, fs)
        gl.bindAttribLocation(p, ATTR_POSITION, "a_position")
        gl.linkProgram(p)
        gl.deleteShader(vs)
        gl.deleteShader(fs)
        if (gl.getProgramiv(p, GlConst.LINK_STATUS) == 0) {
            val log = gl.getProgramInfoLog(p)
            gl.deleteProgram(p)
            error("crop box program link failed: $log")
        }
        return p
    }

    private fun compile(type: Int, source: String): Int {
        val s = gl.createShader(type)
        gl.shaderSource(s, source)
        gl.compileShader(s)
        if (gl.getShaderiv(s, GlConst.COMPILE_STATUS) == 0) {
            val log = gl.getShaderInfoLog(s)
            gl.deleteShader(s)
            error("crop box shader compile failed: $log")
        }
        return s
    }

    companion object {
        const val ATTR_POSITION = 0
        const val FACE_VERTICES = 36
        const val EDGE_VERTICES = 24

        /** Alpha of the face fill; edges are opaque. */
        const val FACE_ALPHA = 0.12f

        private val COLOR: FloatArray = SplatDarkColorScheme.primary.let { floatArrayOf(it.red, it.green, it.blue) }

        val VERTEX = """
            #version 300 es
            precision highp float;
            uniform mat4 u_model;
            uniform mat4 u_view;
            uniform mat4 u_proj;
            layout(location = 0) in vec3 a_position;
            void main() {
                gl_Position = u_proj * u_view * u_model * vec4(a_position, 1.0);
            }
        """.trimIndent()

        val FRAGMENT = """
            #version 300 es
            precision mediump float;
            uniform vec4 u_color;
            out vec4 fragColor;
            void main() {
                fragColor = vec4(u_color.rgb * u_color.a, u_color.a);
            }
        """.trimIndent()

        /** The unit cube's 12 face triangles then its 12 edges, 3 floats per vertex. */
        val CUBE: FloatArray = buildCube()

        /**
         * The column-major model matrix taking the unit cube onto [box]: column k is the box's local axis k (in W)
         * times its half extent, the translation is the centre.
         */
        fun modelOf(box: CropBox): FloatArray {
            val r = box.rotation.normalized()
            val axes = arrayOf(
                r.rotate(Vec3.UNIT_X) * box.halfExtents.x,
                r.rotate(Vec3.UNIT_Y) * box.halfExtents.y,
                r.rotate(Vec3.UNIT_Z) * box.halfExtents.z,
            )
            val m = FloatArray(16)
            for (k in 0 until 3) {
                m[4 * k] = axes[k].x
                m[4 * k + 1] = axes[k].y
                m[4 * k + 2] = axes[k].z
            }
            m[12] = box.centre.x
            m[13] = box.centre.y
            m[14] = box.centre.z
            m[15] = 1f
            return m
        }

        private fun buildCube(): FloatArray {
            fun sign(i: Int, bit: Int) = if (i and bit != 0) 1f else -1f
            fun corner(i: Int) = floatArrayOf(sign(i, 1), sign(i, 2), sign(i, 4))
            // Each face as a quad of corner indices (bit 0 = x, bit 1 = y, bit 2 = z), two triangles each.
            val faces = arrayOf(
                intArrayOf(0, 2, 6, 4), intArrayOf(1, 5, 7, 3),
                intArrayOf(0, 4, 5, 1), intArrayOf(2, 3, 7, 6),
                intArrayOf(0, 1, 3, 2), intArrayOf(4, 6, 7, 5),
            )
            val out = ArrayList<Float>((FACE_VERTICES + EDGE_VERTICES) * 3)
            for (f in faces) for (i in intArrayOf(f[0], f[1], f[2], f[0], f[2], f[3])) out += corner(i).toList()
            // Edges: every pair of corners differing in exactly one bit.
            for (a in 0 until 8) for (bit in intArrayOf(1, 2, 4)) {
                if (a and bit == 0) {
                    out += corner(a).toList()
                    out += corner(a or bit).toList()
                }
            }
            return out.toFloatArray()
        }
    }
}
