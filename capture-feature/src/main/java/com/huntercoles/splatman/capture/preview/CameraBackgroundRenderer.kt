package com.huntercoles.splatman.capture.preview

import android.opengl.GLES11Ext
import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Draws the tracker's external OES camera texture as a full-screen quad. The quad's corners are [QUAD_NDC]; the
 * texture coordinates for them come from the tracker (ARCore `Frame.transformCoordinates2d`, which accounts for the
 * display rotation and the view's aspect). Build, draw and [release] on the thread whose GL context is current.
 */
class CameraBackgroundRenderer {
    private val program: Int = linkProgram(VERTEX_SHADER, FRAGMENT_SHADER)
    private val positionAttrib = GLES20.glGetAttribLocation(program, "a_Position")
    private val texCoordAttrib = GLES20.glGetAttribLocation(program, "a_TexCoord")
    private val textureUniform = GLES20.glGetUniformLocation(program, "u_Texture")
    private val positions = floatBuffer(QUAD_NDC.size).apply { put(QUAD_NDC).position(0) }
    private val texCoords = floatBuffer(QUAD_NDC.size)

    /**
     * Draws [cameraTexture] over a [width] x [height] viewport with [quadTexCoords] (8 floats, u, v per corner of
     * [QUAD_NDC], texture-normalised).
     */
    fun draw(cameraTexture: Int, quadTexCoords: FloatArray, width: Int, height: Int) {
        require(quadTexCoords.size == QUAD_NDC.size) { "need ${QUAD_NDC.size} texture coordinates" }
        texCoords.position(0)
        texCoords.put(quadTexCoords).position(0)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(false)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexture)
        GLES20.glUniform1i(textureUniform, 0)
        GLES20.glVertexAttribPointer(positionAttrib, 2, GLES20.GL_FLOAT, false, 0, positions)
        GLES20.glVertexAttribPointer(texCoordAttrib, 2, GLES20.GL_FLOAT, false, 0, texCoords)
        GLES20.glEnableVertexAttribArray(positionAttrib)
        GLES20.glEnableVertexAttribArray(texCoordAttrib)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, QUAD_NDC.size / 2)
        GLES20.glDisableVertexAttribArray(positionAttrib)
        GLES20.glDisableVertexAttribArray(texCoordAttrib)
        GLES20.glDepthMask(true)
    }

    /** Deletes the program. */
    fun release() {
        GLES20.glDeleteProgram(program)
    }

    companion object {
        /** The full-screen quad, a triangle strip, x, y per corner in normalised device coordinates. */
        val QUAD_NDC = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)

        private const val VERTEX_SHADER = """
            attribute vec4 a_Position;
            attribute vec2 a_TexCoord;
            varying vec2 v_TexCoord;
            void main() {
                gl_Position = a_Position;
                v_TexCoord = a_TexCoord;
            }
        """

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 v_TexCoord;
            uniform samplerExternalOES u_Texture;
            void main() {
                gl_FragColor = texture2D(u_Texture, v_TexCoord);
            }
        """

        private fun floatBuffer(count: Int): FloatBuffer =
            ByteBuffer.allocateDirect(count * Float.SIZE_BYTES).order(ByteOrder.nativeOrder()).asFloatBuffer()

        private fun compile(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source.trimIndent())
            GLES20.glCompileShader(shader)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) {
                val log = GLES20.glGetShaderInfoLog(shader)
                GLES20.glDeleteShader(shader)
                error("camera background shader: $log")
            }
            return shader
        }

        private fun linkProgram(vertex: String, fragment: String): Int {
            val vs = compile(GLES20.GL_VERTEX_SHADER, vertex)
            val fs = compile(GLES20.GL_FRAGMENT_SHADER, fragment)
            val program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, vs)
            GLES20.glAttachShader(program, fs)
            GLES20.glLinkProgram(program)
            GLES20.glDeleteShader(vs)
            GLES20.glDeleteShader(fs)
            val ok = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, ok, 0)
            if (ok[0] == 0) {
                val log = GLES20.glGetProgramInfoLog(program)
                GLES20.glDeleteProgram(program)
                error("camera background program: $log")
            }
            return program
        }
    }
}
