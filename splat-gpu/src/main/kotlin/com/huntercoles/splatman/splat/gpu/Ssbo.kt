package com.huntercoles.splatman.splat.gpu

import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLES31
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A shader storage buffer of [sizeBytes] bytes holding std430 fp32 / uint32 data (ADR 0010). Made by
 * [GlesCompute.ssbo]; every method runs on the GL thread. [close] (or [GlesCompute.close]) deletes it.
 */
class Ssbo internal constructor(private val compute: GlesCompute, sizeBytes: Int) : GlResource, AutoCloseable {
    val id: Int
    var sizeBytes: Int = 0
        private set
    private var released = false

    init {
        compute.requireGlThread()
        require(sizeBytes > 0) { "SSBO size must be > 0, was $sizeBytes" }
        val ids = IntArray(1)
        GLES20.glGenBuffers(1, ids, 0)
        id = ids[0]
        if (id == 0) throw GpuException("glGenBuffers failed")
        compute.track(this)
        resize(sizeBytes)
    }

    /** Reallocates to [bytes] bytes; the old contents are discarded. */
    fun resize(bytes: Int) {
        live()
        require(bytes > 0) { "SSBO size must be > 0, was $bytes" }
        GLES20.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, id)
        GLES20.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, bytes, null, GLES30.GL_DYNAMIC_COPY)
        compute.checkError("glBufferData($bytes)")
        sizeBytes = bytes
    }

    fun upload(data: FloatArray, byteOffset: Int = 0) {
        val buf = direct(data.size * Std430.SCALAR_BYTES)
        buf.asFloatBuffer().put(data)
        write(buf, byteOffset)
    }

    fun upload(data: IntArray, byteOffset: Int = 0) {
        val buf = direct(data.size * Std430.SCALAR_BYTES)
        buf.asIntBuffer().put(data)
        write(buf, byteOffset)
    }

    fun downloadFloats(count: Int = sizeBytes / Std430.SCALAR_BYTES, byteOffset: Int = 0): FloatArray {
        val out = FloatArray(count)
        read(count * Std430.SCALAR_BYTES, byteOffset) { it.asFloatBuffer().get(out) }
        return out
    }

    fun downloadInts(count: Int = sizeBytes / Std430.SCALAR_BYTES, byteOffset: Int = 0): IntArray {
        val out = IntArray(count)
        read(count * Std430.SCALAR_BYTES, byteOffset) { it.asIntBuffer().get(out) }
        return out
    }

    /** Binds to `layout(std430, binding = [binding])`. */
    fun bind(binding: Int) {
        live()
        GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, binding, id)
    }

    override fun close() {
        compute.requireGlThread()
        release()
    }

    override fun release() {
        if (released) return
        released = true
        GLES20.glDeleteBuffers(1, intArrayOf(id), 0)
        compute.untrack(this)
    }

    private fun write(buf: ByteBuffer, byteOffset: Int) {
        live()
        checkRange(buf.capacity(), byteOffset)
        if (buf.capacity() == 0) return
        GLES20.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, id)
        GLES20.glBufferSubData(GLES31.GL_SHADER_STORAGE_BUFFER, byteOffset, buf.capacity(), buf)
        compute.checkError("glBufferSubData")
    }

    private inline fun read(bytes: Int, byteOffset: Int, copy: (ByteBuffer) -> Unit) {
        live()
        checkRange(bytes, byteOffset)
        if (bytes == 0) return
        // Earlier compute writes become visible to the mapping.
        GLES31.glMemoryBarrier(GLES31.GL_BUFFER_UPDATE_BARRIER_BIT)
        GLES20.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, id)
        val mapped = GLES30.glMapBufferRange(
            GLES31.GL_SHADER_STORAGE_BUFFER, byteOffset, bytes, GLES30.GL_MAP_READ_BIT,
        ) as ByteBuffer?
        if (mapped == null) {
            compute.checkError("glMapBufferRange")
            throw GpuException("glMapBufferRange returned null")
        }
        try {
            copy(mapped.order(ByteOrder.nativeOrder()))
        } finally {
            if (!GLES30.glUnmapBuffer(GLES31.GL_SHADER_STORAGE_BUFFER)) throw GpuException("SSBO contents lost on unmap")
        }
    }

    private fun checkRange(bytes: Int, byteOffset: Int) {
        require(byteOffset >= 0 && byteOffset % Std430.SCALAR_BYTES == 0) { "bad byte offset $byteOffset" }
        require(bytes.toLong() + byteOffset <= sizeBytes) { "$bytes bytes at $byteOffset overrun a $sizeBytes-byte SSBO" }
    }

    private fun live() {
        compute.requireGlThread()
        check(!released) { "SSBO $id is closed" }
    }

    private fun direct(bytes: Int): ByteBuffer = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
}

/** A std430 type: the [bytes] it occupies and its base [align]ment (GLSL ES 3.10, 7.6.2.2, std430 rules). */
enum class Std430Type(val bytes: Int, val align: Int) {
    FLOAT(4, 4),
    INT(4, 4),
    UINT(4, 4),
    VEC2(8, 8),
    UVEC2(8, 8),
    VEC3(12, 16),
    VEC4(16, 16),
    UVEC4(16, 16),
    ;

    /** Stride of an array of this type: std430 does not round scalar or vec2 arrays up to 16 as std140 does. */
    val arrayStride: Int get() = Std430.roundUp(bytes, align)
}

/** Pure std430 layout arithmetic for SSBO sizes and struct offsets. */
object Std430 {
    const val SCALAR_BYTES = 4

    /** One struct member; [count] > 1 makes it a fixed-size array. */
    data class Member(val name: String, val type: Std430Type, val count: Int = 1)

    /** Offsets by member name, the struct's [align]ment and its [size], which is also its array stride. */
    data class StructLayout(val offsets: Map<String, Int>, val align: Int, val size: Int)

    fun roundUp(x: Int, multiple: Int): Int {
        require(x >= 0 && multiple > 0) { "roundUp($x, $multiple)" }
        return Math.addExact(x, multiple - 1) / multiple * multiple
    }

    /** Bytes of a `T[count]` buffer member (a struct-of-arrays channel). */
    fun arrayBytes(type: Std430Type, count: Int): Int {
        require(count >= 0) { "count must be >= 0, was $count" }
        return Math.multiplyExact(type.arrayStride, count)
    }

    fun struct(vararg members: Member): StructLayout {
        require(members.isNotEmpty()) { "empty struct" }
        require(members.map { it.name }.toSet().size == members.size) { "duplicate member names" }
        var offset = 0
        val offsets = LinkedHashMap<String, Int>()
        for (m in members) {
            require(m.count >= 1) { "${m.name}: count must be >= 1, was ${m.count}" }
            offset = roundUp(offset, m.type.align)
            offsets[m.name] = offset
            offset += if (m.count == 1) m.type.bytes else m.type.arrayStride * m.count
        }
        val align = members.maxOf { it.type.align }
        return StructLayout(offsets, align, roundUp(offset, align))
    }
}
