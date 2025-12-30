package com.huntercoles.splatman.library.presentation.components

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import com.huntercoles.splatman.library.domain.model.GaussianSplat
import com.huntercoles.splatman.library.domain.model.SplatScene
import com.huntercoles.splatman.viewer.rendering.GaussianCameraController
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Simple OpenGL ES 2.0 renderer for Gaussian point clouds
 * More reliable than Filament - no external material compilation needed
 */
class SimpleGLPointCloudRenderer(
    private val context: Context,
    private val cameraController: GaussianCameraController
) : GLSurfaceView.Renderer {
    
    private var program = 0
    private var vao = 0
    private var vbo = 0
    private var vertexCount = 0
    
    private val mvpMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    
    private var currentScene: SplatScene? = null
    
    // Simple vertex shader with vertex colors
    private val vertexShaderCode = """
        attribute vec4 vPosition;
        attribute vec4 vColor;
        uniform mat4 uMVPMatrix;
        varying vec4 fColor;
        
        void main() {
            gl_Position = uMVPMatrix * vPosition;
            gl_PointSize = 2.0;
            fColor = vColor;
        }
    """.trimIndent()
    
    // Simple fragment shader
    private val fragmentShaderCode = """
        precision mediump float;
        varying vec4 fColor;
        
        void main() {
            gl_FragColor = fColor;
        }
    """.trimIndent()
    
    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.05f, 0.03f, 0.08f, 1.0f) // SplatBlack
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        
        // Compile shaders
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)
        
        // Create program
        program = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vertexShader)
            GLES20.glAttachShader(it, fragmentShader)
            GLES20.glLinkProgram(it)
            
            val linkStatus = IntArray(1)
            GLES20.glGetProgramiv(it, GLES20.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] == 0) {
                Timber.e("Failed to link program: ${GLES20.glGetProgramInfoLog(it)}")
                throw RuntimeException("Failed to link OpenGL program")
            }
        }
        
        Timber.d("OpenGL renderer initialized successfully")
        
        // Load scene if available
        currentScene?.let { loadScene(it) }
    }
    
    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        
        val ratio = width.toFloat() / height
        cameraController.setAspectRatio(ratio)
        Matrix.frustumM(projectionMatrix, 0, -ratio, ratio, -1f, 1f, 1f, 100f)
    }
    
    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        
        if (vertexCount == 0) return
        
        GLES20.glUseProgram(program)
        
        // Update camera
        val viewMat = cameraController.getViewMatrix()
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, viewMat.values, 0)
        
        val mvpHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix")
        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvpMatrix, 0)
        
        // Draw points
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, vertexCount)
        
        checkGLError("onDrawFrame")
    }
    
    fun loadScene(scene: SplatScene) {
        currentScene = scene
        
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
        
        // Upload to GPU
        val vbos = IntArray(1)
        GLES20.glGenBuffers(1, vbos, 0)
        vbo = vbos[0]
        
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glBufferData(
            GLES20.GL_ARRAY_BUFFER,
            vertexData.capacity() * 4,
            vertexData,
            GLES20.GL_STATIC_DRAW
        )
        
        // Setup vertex attributes
        val positionHandle = GLES20.glGetAttribLocation(program, "vPosition")
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 7 * 4, 0)
        
        val colorHandle = GLES20.glGetAttribLocation(program, "vColor")
        GLES20.glEnableVertexAttribArray(colorHandle)
        GLES20.glVertexAttribPointer(colorHandle, 4, GLES20.GL_FLOAT, false, 7 * 4, 3 * 4)
        
        checkGLError("loadScene")
        
        Timber.d("Scene loaded successfully")
    }
    
    private fun loadShader(type: Int, shaderCode: String): Int {
        return GLES20.glCreateShader(type).also { shader ->
            GLES20.glShaderSource(shader, shaderCode)
            GLES20.glCompileShader(shader)
            
            val compileStatus = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
            if (compileStatus[0] == 0) {
                Timber.e("Shader compilation failed: ${GLES20.glGetShaderInfoLog(shader)}")
                throw RuntimeException("Shader compilation failed")
            }
        }
    }
    
    private fun checkGLError(op: String) {
        val error = GLES20.glGetError()
        if (error != GLES20.GL_NO_ERROR) {
            Timber.e("$op: glError $error")
        }
    }
    
    fun destroy() {
        if (vbo != 0) {
            GLES20.glDeleteBuffers(1, intArrayOf(vbo), 0)
            vbo = 0
        }
        
        if (program != 0) {
            GLES20.glDeleteProgram(program)
            program = 0
        }
    }
}
