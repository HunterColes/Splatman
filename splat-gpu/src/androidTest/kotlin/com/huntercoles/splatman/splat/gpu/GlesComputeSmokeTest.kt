package com.huntercoles.splatman.splat.gpu

import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

/** On-device: skips (assumeTrue) without GLES 3.1. QA: ./gradlew :splat-gpu:connectedDebugAndroidTest */
@RunWith(AndroidJUnit4::class)
class GlesComputeSmokeTest {
    private var gl: GlesCompute? = null

    @Before
    fun setUp() {
        val support = GpuSupport.probe()
        assumeTrue("GPU unsupported: $support", support is GpuSupport.Supported)
        gl = GlesCompute.create()
    }

    @After
    fun tearDown() {
        gl?.close()
    }

    @Test
    fun doublesOneMillionFloatsExactly() {
        val n = 1 shl 20
        val rnd = Random(42)
        val input = FloatArray(n) { rnd.nextFloat() * 2000f - 1000f }
        val out = gl!!.run {
            val buf = ssbo(Std430.arrayBytes(Std430Type.FLOAT, n))
            buf.upload(input)
            val p = program(DOUBLE)
            buf.bind(0)
            p.setUInt("n", n)
            p.dispatchFor(n, LOCAL)
            buf.downloadFloats(n)
        }
        assertEquals(n, out.size)
        for (i in 0 until n) {
            if ((input[i] * 2f).toRawBits() != out[i].toRawBits()) {
                throw AssertionError("element $i: ${input[i]} * 2 = ${input[i] * 2f}, GPU gave ${out[i]}")
            }
        }
    }

    @Test
    fun intsRoundTripAndResizeKeepsWorking() {
        val ints = IntArray(1000) { it * 7 - 3000 }
        val back = gl!!.run {
            val buf = ssbo(16)
            buf.resize(Std430.arrayBytes(Std430Type.INT, ints.size))
            buf.upload(ints)
            buf.downloadInts(ints.size)
        }
        assertTrue(ints.contentEquals(back))
    }

    @Test
    fun brokenShaderThrowsGpuExceptionWithItsLog() {
        val e = assertThrows(GpuException::class.java) { gl!!.run { program(BROKEN) } }
        assertTrue("empty info log", e.infoLog.isNotBlank())
        assertTrue(e.message!!.contains(e.infoLog))
    }

    @Test
    fun glRunsOffTheMainThreadAndClosesCleanly() {
        val onMain = gl!!.run { Looper.myLooper() == Looper.getMainLooper() }
        assertFalse(onMain)
        assertEquals(GlesCompute.THREAD_NAME, gl!!.run { Thread.currentThread().name })
        gl!!.close()
        assertThrows(IllegalStateException::class.java) { gl!!.run { } }
        gl!!.close()
    }

    private companion object {
        const val LOCAL = 256

        const val DOUBLE = """#version 310 es
layout(local_size_x = 256) in;
layout(std430, binding = 0) buffer Data { float v[]; };
uniform uint n;
void main() {
    uint i = gl_GlobalInvocationID.x;
    if (i < n) v[i] = v[i] * 2.0;
}
"""

        const val BROKEN = """#version 310 es
layout(local_size_x = 64) in;
void main() {
    this is not glsl;
}
"""
    }
}
