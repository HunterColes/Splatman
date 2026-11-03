package com.huntercoles.splatman.viewer.rendering.gl

import java.nio.Buffer
import java.nio.IntBuffer
import java.util.concurrent.CopyOnWriteArrayList

/** A [Gl] that records each call with its thread; names are handed out from 1 up, compiles and links succeed. */
@Suppress("TooManyFunctions")
class FakeGl(private val maxTextureSize: Int = 4096) : Gl {
    data class Call(val name: String, val thread: Thread, val args: List<Any?>)

    val calls = CopyOnWriteArrayList<Call>()
    private var nextName = 1

    /** The order uploaded by each `bufferData` of `count * 4` bytes into an order VBO, decoded. */
    val orderUploads = CopyOnWriteArrayList<Pair<Int, IntArray>>()
    private var boundArray = 0

    private fun rec(name: String, vararg args: Any?) {
        calls += Call(name, Thread.currentThread(), args.toList())
    }

    private fun gen(name: String): Int = nextName++.also { rec(name, it) }

    fun count(name: String) = calls.count { it.name == name }
    fun named(name: String) = calls.filter { it.name == name }

    override fun createShader(type: Int) = gen("createShader")
    override fun shaderSource(shader: Int, source: String) = rec("shaderSource", shader)
    override fun compileShader(shader: Int) = rec("compileShader", shader)
    override fun getShaderiv(shader: Int, pname: Int) = 1.also { rec("getShaderiv", shader, pname) }
    override fun getShaderInfoLog(shader: Int) = "".also { rec("getShaderInfoLog") }
    override fun deleteShader(shader: Int) = rec("deleteShader", shader)
    override fun createProgram() = gen("createProgram")
    override fun attachShader(program: Int, shader: Int) = rec("attachShader", program, shader)
    override fun bindAttribLocation(program: Int, index: Int, name: String) = rec("bindAttribLocation", index, name)
    override fun linkProgram(program: Int) = rec("linkProgram", program)
    override fun getProgramiv(program: Int, pname: Int) = 1.also { rec("getProgramiv", program, pname) }
    override fun getProgramInfoLog(program: Int) = "".also { rec("getProgramInfoLog") }
    override fun deleteProgram(program: Int) = rec("deleteProgram", program)
    override fun useProgram(program: Int) = rec("useProgram", program)
    override fun getUniformLocation(program: Int, name: String) = name.hashCode().also { rec("getUniformLocation") }
    override fun uniform1i(location: Int, v: Int) = rec("uniform1i", location, v)
    override fun uniform1f(location: Int, v: Float) = rec("uniform1f", location, v)
    override fun uniform2f(location: Int, x: Float, y: Float) = rec("uniform2f", location, x, y)
    override fun uniform3f(location: Int, x: Float, y: Float, z: Float) = rec("uniform3f", location, x, y, z)
    override fun uniform4f(location: Int, x: Float, y: Float, z: Float, w: Float) =
        rec("uniform4f", location, x, y, z, w)
    override fun uniformMatrix3fv(location: Int, values: FloatArray) =
        rec("uniformMatrix3fv", location, values.copyOf())
    override fun uniformMatrix4fv(location: Int, values: FloatArray) = rec("uniformMatrix4fv", location)
    override fun genTexture() = gen("genTexture")
    override fun deleteTexture(texture: Int) = rec("deleteTexture", texture)
    override fun activeTexture(unit: Int) = rec("activeTexture", unit)
    override fun bindTexture(target: Int, texture: Int) = rec("bindTexture", texture)
    override fun texParameteri(target: Int, pname: Int, value: Int) = rec("texParameteri", pname, value)
    override fun texImage2D(
        target: Int,
        internalFormat: Int,
        width: Int,
        height: Int,
        format: Int,
        type: Int,
        data: Buffer?,
    ) = rec("texImage2D", internalFormat, width, height, data?.remaining())
    override fun pixelStorei(pname: Int, value: Int) = rec("pixelStorei", pname, value)
    override fun getInteger(pname: Int) =
        (if (pname == GlConst.MAX_TEXTURE_SIZE) maxTextureSize else 0).also { rec("getInteger", pname) }
    override fun genBuffer() = gen("genBuffer")
    override fun deleteBuffer(buffer: Int) = rec("deleteBuffer", buffer)
    override fun bindBuffer(target: Int, buffer: Int) {
        boundArray = buffer
        rec("bindBuffer", buffer)
    }
    override fun bufferData(target: Int, bytes: Int, data: Buffer?, usage: Int) {
        rec("bufferData", boundArray, bytes, usage)
        if (usage == GlConst.DYNAMIC_DRAW && data is IntBuffer) {
            val d = data.duplicate()
            orderUploads += boundArray to IntArray(d.remaining()).also { d.get(it) }
        }
    }
    override fun genVertexArray() = gen("genVertexArray")
    override fun deleteVertexArray(vao: Int) = rec("deleteVertexArray", vao)
    override fun bindVertexArray(vao: Int) = rec("bindVertexArray", vao)
    override fun enableVertexAttribArray(index: Int) = rec("enableVertexAttribArray", index)
    override fun vertexAttribPointer(index: Int, size: Int, type: Int, stride: Int, offset: Int) =
        rec("vertexAttribPointer", index)
    override fun vertexAttribIPointer(index: Int, size: Int, type: Int, stride: Int, offset: Int) =
        rec("vertexAttribIPointer", index, boundArray)
    override fun vertexAttribDivisor(index: Int, divisor: Int) = rec("vertexAttribDivisor", index, divisor)
    override fun genFramebuffer() = gen("genFramebuffer")
    override fun deleteFramebuffer(framebuffer: Int) = rec("deleteFramebuffer", framebuffer)
    override fun bindFramebuffer(target: Int, framebuffer: Int) = rec("bindFramebuffer", target, framebuffer)
    override fun framebufferTexture2D(target: Int, attachment: Int, texTarget: Int, texture: Int) =
        rec("framebufferTexture2D", texture)
    override fun checkFramebufferStatus(target: Int) =
        GlConst.FRAMEBUFFER_COMPLETE.also { rec("checkFramebufferStatus") }
    override fun blitFramebuffer(srcW: Int, srcH: Int, dstW: Int, dstH: Int) =
        rec("blitFramebuffer", srcW, srcH, dstW, dstH)
    override fun viewport(width: Int, height: Int) = rec("viewport", width, height)
    override fun clearColor(r: Float, g: Float, b: Float, a: Float) = rec("clearColor")
    override fun clear(mask: Int) = rec("clear", mask)
    override fun enable(cap: Int) = rec("enable", cap)
    override fun disable(cap: Int) = rec("disable", cap)
    override fun blendFunc(src: Int, dst: Int) = rec("blendFunc", src, dst)
    override fun drawArraysInstanced(mode: Int, first: Int, count: Int, instances: Int) =
        rec("drawArraysInstanced", mode, count, instances)
    override fun drawArrays(mode: Int, first: Int, count: Int) = rec("drawArrays", mode, first, count)
}
