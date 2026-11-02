package com.huntercoles.splatman.splat.gpu

import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLES31

/**
 * A linked compute program. Made by [GlesCompute.program]; every method runs on the GL thread. [dispatch] ends with
 * a shader-storage barrier, so the next dispatch (and an [Ssbo] download) sees its writes.
 */
class ComputeProgram private constructor(private val compute: GlesCompute, val id: Int) : GlResource, AutoCloseable {
    private var released = false
    private val locations = HashMap<String, Int>()

    fun use() {
        live()
        GLES20.glUseProgram(id)
    }

    fun setInt(name: String, value: Int) {
        use()
        GLES20.glUniform1i(location(name), value)
    }

    fun setUInt(name: String, value: Int) {
        use()
        GLES30.glUniform1ui(location(name), value)
    }

    fun setFloat(name: String, value: Float) {
        use()
        GLES20.glUniform1f(location(name), value)
    }

    /** Runs groupsX * groupsY * groupsZ workgroups; nothing when any is 0. */
    fun dispatch(groupsX: Int, groupsY: Int = 1, groupsZ: Int = 1) {
        use()
        require(groupsX >= 0 && groupsY >= 0 && groupsZ >= 0) { "groups $groupsX x $groupsY x $groupsZ" }
        if (groupsX == 0 || groupsY == 0 || groupsZ == 0) return
        GLES31.glDispatchCompute(groupsX, groupsY, groupsZ)
        GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT)
        compute.checkError("glDispatchCompute($groupsX, $groupsY, $groupsZ)")
    }

    /** One invocation per item: [n] items in workgroups of `local_size_x = [local]`. */
    fun dispatchFor(n: Int, local: Int) = dispatch(Dispatch.groups(n, local))

    override fun close() {
        compute.requireGlThread()
        release()
    }

    override fun release() {
        if (released) return
        released = true
        GLES20.glDeleteProgram(id)
        compute.untrack(this)
    }

    private fun location(name: String): Int = locations.getOrPut(name) {
        GLES20.glGetUniformLocation(id, name).also { require(it >= 0) { "no active uniform '$name'" } }
    }

    private fun live() {
        compute.requireGlThread()
        check(!released) { "program $id is closed" }
    }

    internal companion object {
        fun compile(compute: GlesCompute, source: String): ComputeProgram {
            compute.requireGlThread()
            val shader = GLES20.glCreateShader(GLES31.GL_COMPUTE_SHADER)
            if (shader == 0) throw GpuException("glCreateShader(GL_COMPUTE_SHADER) failed", "GL error 0x${err()}")
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                val log = GLES20.glGetShaderInfoLog(shader).orEmpty()
                GLES20.glDeleteShader(shader)
                throw GpuException("compute shader failed to compile", log.ifBlank { NO_LOG })
            }
            val program = GLES20.glCreateProgram()
            if (program == 0) {
                GLES20.glDeleteShader(shader)
                throw GpuException("glCreateProgram failed", "GL error 0x${err()}")
            }
            GLES20.glAttachShader(program, shader)
            GLES20.glLinkProgram(program)
            GLES20.glDetachShader(program, shader)
            GLES20.glDeleteShader(shader)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
            if (status[0] == 0) {
                val log = GLES20.glGetProgramInfoLog(program).orEmpty()
                GLES20.glDeleteProgram(program)
                throw GpuException("compute program failed to link", log.ifBlank { NO_LOG })
            }
            return ComputeProgram(compute, program).also { compute.track(it) }
        }

        private const val NO_LOG = "(the driver gave no info log)"

        private fun err() = Integer.toHexString(GLES20.glGetError())
    }
}

/** Pure workgroup arithmetic. */
object Dispatch {
    /** GL_MAX_COMPUTE_WORK_GROUP_COUNT is at least this in every dimension (GLES 3.1). */
    const val MAX_GROUPS = 65_535

    /** Workgroups of [local] invocations that cover [n] items: ceil(n / local), 0 for no items. */
    fun groups(n: Int, local: Int): Int {
        require(n >= 0) { "n must be >= 0, was $n" }
        require(local > 0) { "local size must be > 0, was $local" }
        val g = n / local + if (n % local == 0) 0 else 1
        require(g <= MAX_GROUPS) { "$n items in groups of $local need $g workgroups, over $MAX_GROUPS" }
        return g
    }
}
