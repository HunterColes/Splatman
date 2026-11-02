package com.huntercoles.splatman.splat.gpu

import android.opengl.GLES20
import android.opengl.GLES31

/** Whether this device can run the GPU kernels; when not, training falls back to the CPU (ADR 0010). */
sealed interface GpuSupport {
    data object Supported : GpuSupport

    data class Unsupported(val reason: String) : GpuSupport

    companion object {
        const val MIN_WORKGROUP_INVOCATIONS = 256
        const val MIN_SSBO_BINDINGS = 8
        private val VERSION = Regex("""OpenGL ES (\d+)\.(\d+)""")

        /**
         * Creates an EGL ES 3 context on its own GL thread and checks GL_VERSION >= 3.1 and the limits the kernels
         * need. Never throws: any failure (no EGL, no ES 3 config, a stub android.jar) is [Unsupported] with the
         * reason. The caller waits while the GL thread works; GL never runs on the calling thread.
         */
        @Suppress("TooGenericExceptionCaught") // "never throws" includes Errors from native code
        fun probe(): GpuSupport = try {
            GlesCompute.create().use { gl ->
                gl.run {
                    evaluate(
                        GLES20.glGetString(GLES20.GL_VERSION),
                        getInt(GLES31.GL_MAX_COMPUTE_WORK_GROUP_INVOCATIONS),
                        getInt(GLES31.GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS),
                        getInt(GLES31.GL_MAX_COMPUTE_SHADER_STORAGE_BLOCKS),
                    )
                }
            }
        } catch (t: Throwable) {
            Unsupported("no GLES 3 context: ${t.message ?: t.javaClass.simpleName}")
        }

        /** The pure decision behind [probe], from the GL_VERSION string and the limits. */
        fun evaluate(glVersion: String?, workgroupInvocations: Int, ssboBindings: Int, computeSsboBlocks: Int): GpuSupport {
            val (major, minor) = parseVersion(glVersion) ?: return Unsupported("unreadable GL_VERSION: $glVersion")
            return when {
                major < 3 || (major == 3 && minor < 1) -> Unsupported("GLES $major.$minor is below 3.1")
                workgroupInvocations < MIN_WORKGROUP_INVOCATIONS ->
                    Unsupported("max compute workgroup invocations $workgroupInvocations < $MIN_WORKGROUP_INVOCATIONS")
                ssboBindings < MIN_SSBO_BINDINGS -> Unsupported("max SSBO bindings $ssboBindings < $MIN_SSBO_BINDINGS")
                computeSsboBlocks < MIN_SSBO_BINDINGS ->
                    Unsupported("max compute SSBO blocks $computeSsboBlocks < $MIN_SSBO_BINDINGS")
                else -> Supported
            }
        }

        /** (major, minor) from a GL_VERSION such as "OpenGL ES 3.2 V@0502.0"; null when it is not an ES version. */
        fun parseVersion(glVersion: String?): Pair<Int, Int>? {
            val m = VERSION.find(glVersion ?: return null) ?: return null
            return m.groupValues[1].toInt() to m.groupValues[2].toInt()
        }
    }
}
