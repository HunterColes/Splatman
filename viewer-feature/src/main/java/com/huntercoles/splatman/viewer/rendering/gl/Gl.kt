package com.huntercoles.splatman.viewer.rendering.gl

import android.opengl.GLES30
import java.nio.Buffer

/**
 * The GLES 3.0 calls [SplatRenderer] makes, behind a seam so the renderer's threading and context-loss behaviour is
 * unit tested on the JVM with a fake. [AndroidGl] forwards each to [GLES30]; constants are the GLES30 values.
 * Every method must only be called on the GL thread.
 */
@Suppress("TooManyFunctions")
interface Gl {
    fun createShader(type: Int): Int
    fun shaderSource(shader: Int, source: String)
    fun compileShader(shader: Int)
    fun getShaderiv(shader: Int, pname: Int): Int
    fun getShaderInfoLog(shader: Int): String
    fun deleteShader(shader: Int)
    fun createProgram(): Int
    fun attachShader(program: Int, shader: Int)
    fun bindAttribLocation(program: Int, index: Int, name: String)
    fun linkProgram(program: Int)
    fun getProgramiv(program: Int, pname: Int): Int
    fun getProgramInfoLog(program: Int): String
    fun deleteProgram(program: Int)
    fun useProgram(program: Int)
    fun getUniformLocation(program: Int, name: String): Int
    fun uniform1i(location: Int, v: Int)
    fun uniform1f(location: Int, v: Float)
    fun uniform2f(location: Int, x: Float, y: Float)
    fun uniform3f(location: Int, x: Float, y: Float, z: Float)
    fun uniform4f(location: Int, x: Float, y: Float, z: Float, w: Float)
    fun uniformMatrix3fv(location: Int, values: FloatArray)
    fun uniformMatrix4fv(location: Int, values: FloatArray)

    fun genTexture(): Int
    fun deleteTexture(texture: Int)
    fun activeTexture(unit: Int)
    fun bindTexture(target: Int, texture: Int)
    fun texParameteri(target: Int, pname: Int, value: Int)
    fun texImage2D(target: Int, internalFormat: Int, width: Int, height: Int, format: Int, type: Int, data: Buffer?)
    fun pixelStorei(pname: Int, value: Int)
    fun getInteger(pname: Int): Int

    fun genBuffer(): Int
    fun deleteBuffer(buffer: Int)
    fun bindBuffer(target: Int, buffer: Int)
    fun bufferData(target: Int, bytes: Int, data: Buffer?, usage: Int)
    fun genVertexArray(): Int
    fun deleteVertexArray(vao: Int)
    fun bindVertexArray(vao: Int)
    fun enableVertexAttribArray(index: Int)
    fun vertexAttribPointer(index: Int, size: Int, type: Int, stride: Int, offset: Int)

    fun genFramebuffer(): Int
    fun deleteFramebuffer(framebuffer: Int)
    fun bindFramebuffer(target: Int, framebuffer: Int)
    fun framebufferTexture2D(target: Int, attachment: Int, texTarget: Int, texture: Int)
    fun checkFramebufferStatus(target: Int): Int
    fun blitFramebuffer(srcW: Int, srcH: Int, dstW: Int, dstH: Int)

    fun viewport(width: Int, height: Int)
    fun clearColor(r: Float, g: Float, b: Float, a: Float)
    fun clear(mask: Int)
    fun enable(cap: Int)
    fun disable(cap: Int)
    fun blendFunc(src: Int, dst: Int)
    fun drawElements(mode: Int, count: Int, type: Int, offset: Int)
    fun drawArrays(mode: Int, first: Int, count: Int)
}

/** [Gl] on the device: each call is the [GLES30] call of the same name. */
@Suppress("TooManyFunctions")
object AndroidGl : Gl {
    private val one = IntArray(1)

    override fun createShader(type: Int) = GLES30.glCreateShader(type)
    override fun shaderSource(shader: Int, source: String) = GLES30.glShaderSource(shader, source)
    override fun compileShader(shader: Int) = GLES30.glCompileShader(shader)
    override fun getShaderiv(shader: Int, pname: Int): Int {
        GLES30.glGetShaderiv(shader, pname, one, 0)
        return one[0]
    }
    override fun getShaderInfoLog(shader: Int): String = GLES30.glGetShaderInfoLog(shader)
    override fun deleteShader(shader: Int) = GLES30.glDeleteShader(shader)
    override fun createProgram() = GLES30.glCreateProgram()
    override fun attachShader(program: Int, shader: Int) = GLES30.glAttachShader(program, shader)
    override fun bindAttribLocation(program: Int, index: Int, name: String) =
        GLES30.glBindAttribLocation(program, index, name)
    override fun linkProgram(program: Int) = GLES30.glLinkProgram(program)
    override fun getProgramiv(program: Int, pname: Int): Int {
        GLES30.glGetProgramiv(program, pname, one, 0)
        return one[0]
    }
    override fun getProgramInfoLog(program: Int): String = GLES30.glGetProgramInfoLog(program)
    override fun deleteProgram(program: Int) = GLES30.glDeleteProgram(program)
    override fun useProgram(program: Int) = GLES30.glUseProgram(program)
    override fun getUniformLocation(program: Int, name: String) = GLES30.glGetUniformLocation(program, name)
    override fun uniform1i(location: Int, v: Int) = GLES30.glUniform1i(location, v)
    override fun uniform1f(location: Int, v: Float) = GLES30.glUniform1f(location, v)
    override fun uniform2f(location: Int, x: Float, y: Float) = GLES30.glUniform2f(location, x, y)
    override fun uniform3f(location: Int, x: Float, y: Float, z: Float) = GLES30.glUniform3f(location, x, y, z)
    override fun uniform4f(location: Int, x: Float, y: Float, z: Float, w: Float) =
        GLES30.glUniform4f(location, x, y, z, w)
    override fun uniformMatrix3fv(location: Int, values: FloatArray) =
        GLES30.glUniformMatrix3fv(location, 1, false, values, 0)
    override fun uniformMatrix4fv(location: Int, values: FloatArray) =
        GLES30.glUniformMatrix4fv(location, 1, false, values, 0)

    override fun genTexture(): Int {
        GLES30.glGenTextures(1, one, 0)
        return one[0]
    }
    override fun deleteTexture(texture: Int) {
        one[0] = texture
        GLES30.glDeleteTextures(1, one, 0)
    }
    override fun activeTexture(unit: Int) = GLES30.glActiveTexture(unit)
    override fun bindTexture(target: Int, texture: Int) = GLES30.glBindTexture(target, texture)
    override fun texParameteri(target: Int, pname: Int, value: Int) = GLES30.glTexParameteri(target, pname, value)
    override fun texImage2D(
        target: Int,
        internalFormat: Int,
        width: Int,
        height: Int,
        format: Int,
        type: Int,
        data: Buffer?,
    ) = GLES30.glTexImage2D(target, 0, internalFormat, width, height, 0, format, type, data)
    override fun pixelStorei(pname: Int, value: Int) = GLES30.glPixelStorei(pname, value)
    override fun getInteger(pname: Int): Int {
        GLES30.glGetIntegerv(pname, one, 0)
        return one[0]
    }

    override fun genBuffer(): Int {
        GLES30.glGenBuffers(1, one, 0)
        return one[0]
    }
    override fun deleteBuffer(buffer: Int) {
        one[0] = buffer
        GLES30.glDeleteBuffers(1, one, 0)
    }
    override fun bindBuffer(target: Int, buffer: Int) = GLES30.glBindBuffer(target, buffer)
    override fun bufferData(target: Int, bytes: Int, data: Buffer?, usage: Int) =
        GLES30.glBufferData(target, bytes, data, usage)
    override fun genVertexArray(): Int {
        GLES30.glGenVertexArrays(1, one, 0)
        return one[0]
    }
    override fun deleteVertexArray(vao: Int) {
        one[0] = vao
        GLES30.glDeleteVertexArrays(1, one, 0)
    }
    override fun bindVertexArray(vao: Int) = GLES30.glBindVertexArray(vao)
    override fun enableVertexAttribArray(index: Int) = GLES30.glEnableVertexAttribArray(index)
    override fun vertexAttribPointer(index: Int, size: Int, type: Int, stride: Int, offset: Int) =
        GLES30.glVertexAttribPointer(index, size, type, false, stride, offset)

    override fun genFramebuffer(): Int {
        GLES30.glGenFramebuffers(1, one, 0)
        return one[0]
    }
    override fun deleteFramebuffer(framebuffer: Int) {
        one[0] = framebuffer
        GLES30.glDeleteFramebuffers(1, one, 0)
    }
    override fun bindFramebuffer(target: Int, framebuffer: Int) = GLES30.glBindFramebuffer(target, framebuffer)
    override fun framebufferTexture2D(target: Int, attachment: Int, texTarget: Int, texture: Int) =
        GLES30.glFramebufferTexture2D(target, attachment, texTarget, texture, 0)
    override fun checkFramebufferStatus(target: Int) = GLES30.glCheckFramebufferStatus(target)
    override fun blitFramebuffer(srcW: Int, srcH: Int, dstW: Int, dstH: Int) = GLES30.glBlitFramebuffer(
        0, 0, srcW, srcH, 0, 0, dstW, dstH, GLES30.GL_COLOR_BUFFER_BIT, GLES30.GL_LINEAR,
    )

    override fun viewport(width: Int, height: Int) = GLES30.glViewport(0, 0, width, height)
    override fun clearColor(r: Float, g: Float, b: Float, a: Float) = GLES30.glClearColor(r, g, b, a)
    override fun clear(mask: Int) = GLES30.glClear(mask)
    override fun enable(cap: Int) = GLES30.glEnable(cap)
    override fun disable(cap: Int) = GLES30.glDisable(cap)
    override fun blendFunc(src: Int, dst: Int) = GLES30.glBlendFunc(src, dst)
    override fun drawElements(mode: Int, count: Int, type: Int, offset: Int) =
        GLES30.glDrawElements(mode, count, type, offset)
    override fun drawArrays(mode: Int, first: Int, count: Int) = GLES30.glDrawArrays(mode, first, count)
}

/**
 * The GLES 3.0 enum values the renderer uses, as plain constants: the `GLES30.GL_*` fields are compile-time
 * constants too, but naming them here keeps the renderer free of android.opengl so its JVM tests load.
 */
object GlConst {
    const val VERTEX_SHADER = 0x8B31
    const val FRAGMENT_SHADER = 0x8B30
    const val COMPILE_STATUS = 0x8B81
    const val LINK_STATUS = 0x8B82
    const val TEXTURE_2D = 0x0DE1
    const val TEXTURE0 = 0x84C0
    const val TEXTURE1 = 0x84C1
    const val TEXTURE_MIN_FILTER = 0x2801
    const val TEXTURE_MAG_FILTER = 0x2800
    const val TEXTURE_WRAP_S = 0x2802
    const val TEXTURE_WRAP_T = 0x2803
    const val NEAREST = 0x2600
    const val LINEAR = 0x2601
    const val CLAMP_TO_EDGE = 0x812F
    const val RGBA32UI = 0x8D70
    const val RGBA_INTEGER = 0x8D99
    const val R32UI = 0x8236
    const val RED_INTEGER = 0x8D94
    const val RGBA8 = 0x8058
    const val RGBA = 0x1908
    const val UNSIGNED_INT = 0x1405
    const val UNSIGNED_BYTE = 0x1401
    const val UNSIGNED_SHORT = 0x1403
    const val FLOAT = 0x1406
    const val UNPACK_ALIGNMENT = 0x0CF5
    const val MAX_TEXTURE_SIZE = 0x0D33
    const val ARRAY_BUFFER = 0x8892
    const val ELEMENT_ARRAY_BUFFER = 0x8893
    const val STATIC_DRAW = 0x88E4
    const val DYNAMIC_DRAW = 0x88E8
    const val FRAMEBUFFER = 0x8D40
    const val READ_FRAMEBUFFER = 0x8CA8
    const val DRAW_FRAMEBUFFER = 0x8CA9
    const val COLOR_ATTACHMENT0 = 0x8CE0
    const val FRAMEBUFFER_COMPLETE = 0x8CD5
    const val COLOR_BUFFER_BIT = 0x4000
    const val BLEND = 0x0BE2
    const val DEPTH_TEST = 0x0B71
    const val CULL_FACE = 0x0B44
    const val ONE = 1
    const val ONE_MINUS_SRC_ALPHA = 0x0303
    const val TRIANGLE_STRIP = 0x0005
    const val TRIANGLES = 0x0004
    const val LINES = 0x0001
}
