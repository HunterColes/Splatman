package com.huntercoles.splatman.library.presentation.components

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import com.huntercoles.splatman.library.domain.model.GaussianSplat
import com.huntercoles.splatman.library.domain.model.SplatScene
import com.huntercoles.splatman.viewer.rendering.GaussianCameraController
import com.huntercoles.splatman.viewer.rendering.math.Matrix4
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * OpenGL ES 3.0 renderer for Gaussian point clouds
 * Modern implementation with VAOs, UBOs, and GLSL 3.0
 */
class SimpleGLPointCloudRenderer(
    private val context: Context,
    private val cameraController: GaussianCameraController
) : GLSurfaceView.Renderer {

    private var program = 0
    private var vao = 0
    private var vbo = 0
    private var ubo = 0
    private var vertexCount = 0

    private val mvpMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)

    private var pendingScene: SplatScene? = null

    // GLSL 3.0 vertex shader with modern syntax
    private val vertexShaderCode = """
        #version 300 es
        precision highp float;

        layout(location = 0) in vec4 vPosition;
        layout(location = 1) in vec4 vColor;

        uniform mat4 uMVPMatrix;

        out vec4 fColor;

        void main() {
            gl_Position = uMVPMatrix * vPosition;
            gl_PointSize = 2.0;
            fColor = vColor;
        }
    """.trimIndent()

    // GLSL 3.0 fragment shader with modern syntax
    private val fragmentShaderCode = """
        #version 300 es
        precision mediump float;

        in vec4 fColor;
        out vec4 fragColor;

        void main() {
            fragColor = fColor;
        }
    """.trimIndent()
    
    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0.05f, 0.03f, 0.08f, 1.0f) // SplatBlack
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)

        // Create VAO
        val vaos = IntArray(1)
        GLES30.glGenVertexArrays(1, vaos, 0)
        vao = vaos[0]
        GLES30.glBindVertexArray(vao)

        // Compile shaders
        val vertexShader = loadShader(GLES30.GL_VERTEX_SHADER, vertexShaderCode)
        val fragmentShader = loadShader(GLES30.GL_FRAGMENT_SHADER, fragmentShaderCode)

        // Create program
        program = GLES30.glCreateProgram().also {
            GLES30.glAttachShader(it, vertexShader)
            GLES30.glAttachShader(it, fragmentShader)
            GLES30.glLinkProgram(it)

            val linkStatus = IntArray(1)
            GLES30.glGetProgramiv(it, GLES30.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] == 0) {
                Timber.e("Failed to link program: ${GLES30.glGetProgramInfoLog(it)}")
                throw RuntimeException("Failed to link OpenGL program")
            }
        }

        // Create UBO for matrices
        val ubos = IntArray(1)
        GLES30.glGenBuffers(1, ubos, 0)
        ubo = ubos[0]

        Timber.d("OpenGL ES 3.0 renderer initialized successfully")

        // Load pending scene if available
        pendingScene?.let { 
            loadScene(it)
            pendingScene = null
        }
    }
    
    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)

        val ratio = width.toFloat() / height
        cameraController.setAspectRatio(ratio)
        
        // Use camera controller's projection matrix
        val projMat = cameraController.getProjectionMatrix()
        projMat.values.copyInto(projectionMatrix, 0, 0, 16)
    }
    
    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        if (vertexCount == 0) return

        GLES30.glBindVertexArray(vao)
        GLES30.glUseProgram(program)

        // Update camera matrices
        val viewMat = cameraController.getViewMatrix()
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, viewMat.values, 0)

        val mvpHandle = GLES30.glGetUniformLocation(program, "uMVPMatrix")
        GLES30.glUniformMatrix4fv(mvpHandle, 1, false, mvpMatrix, 0)

        // Draw points
        GLES30.glDrawArrays(GLES30.GL_POINTS, 0, vertexCount)

        checkGLError("onDrawFrame")
    }
    
    fun loadScene(scene: SplatScene) {
        if (scene.gaussians.isEmpty()) {
            Timber.w("Cannot load empty scene")
            return
        }

        Timber.d("Loading scene with ${scene.gaussians.size} gaussians")

        // Build interleaved vertex buffer: [x, y, z, r, g, b, a] per vertex
        val vertexData = FloatBuffer.allocate(scene.gaussians.size * 7)

        scene.gaussians.forEach { gaussian ->
            vertexData.put(gaussian.position[0])
            vertexData.put(gaussian.position[1])
            vertexData.put(gaussian.position[2])
            vertexData.put(gaussian.shCoefficients[0]) // R
            vertexData.put(gaussian.shCoefficients[1]) // G
            vertexData.put(gaussian.shCoefficients[2]) // B
            vertexData.put(gaussian.opacity) // A
        }

        vertexData.position(0)
        vertexCount = scene.gaussians.size

        // Bind VAO for setup
        GLES30.glBindVertexArray(vao)

        // Create and setup VBO
        val vbos = IntArray(1)
        GLES30.glGenBuffers(1, vbos, 0)
        vbo = vbos[0]

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER,
            vertexData.capacity() * 4,
            vertexData,
            GLES30.GL_STATIC_DRAW
        )

        // Setup vertex attributes with explicit locations
        GLES30.glEnableVertexAttribArray(0) // vPosition
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 7 * 4, 0)

        GLES30.glEnableVertexAttribArray(1) // vColor
        GLES30.glVertexAttribPointer(1, 4, GLES30.GL_FLOAT, false, 7 * 4, 3 * 4)

        // Unbind VAO
        GLES30.glBindVertexArray(0)

        checkGLError("loadScene")

        Timber.d("Scene loaded successfully with OpenGL ES 3.0")
    }
    
    fun setPendingScene(scene: SplatScene?) {
        pendingScene = scene
        // If surface is already created, load immediately
        if (program != 0) {
            pendingScene?.let { loadScene(it) }
            pendingScene = null
        }
    }
    
    fun updateProjection() {
        val projMat = cameraController.getProjectionMatrix()
        projMat.values.copyInto(projectionMatrix, 0, 0, 16)
    }
    
    private fun loadShader(type: Int, shaderCode: String): Int {
        return GLES30.glCreateShader(type).also { shader ->
            GLES30.glShaderSource(shader, shaderCode)
            GLES30.glCompileShader(shader)

            val compileStatus = IntArray(1)
            GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compileStatus, 0)
            if (compileStatus[0] == 0) {
                Timber.e("Shader compilation failed: ${GLES30.glGetShaderInfoLog(shader)}")
                throw RuntimeException("Shader compilation failed")
            }
        }
    }
    
    private fun checkGLError(op: String) {
        val error = GLES30.glGetError()
        if (error != GLES30.GL_NO_ERROR) {
            Timber.e("$op: glError $error")
        }
    }
    
    fun destroy() {
        if (vbo != 0) {
            GLES30.glDeleteBuffers(1, intArrayOf(vbo), 0)
            vbo = 0
        }

        if (ubo != 0) {
            GLES30.glDeleteBuffers(1, intArrayOf(ubo), 0)
            ubo = 0
        }

        if (vao != 0) {
            GLES30.glDeleteVertexArrays(1, intArrayOf(vao), 0)
            vao = 0
        }

        if (program != 0) {
            GLES30.glDeleteProgram(program)
            program = 0
        }
    }
}
