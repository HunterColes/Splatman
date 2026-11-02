package com.huntercoles.splatman.splat.gpu

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GpuSupportTest {
    @Test
    fun `parses GL_VERSION strings`() {
        assertEquals(3 to 2, GpuSupport.parseVersion("OpenGL ES 3.2 V@0502.0 (GIT@...)"))
        assertEquals(3 to 1, GpuSupport.parseVersion("OpenGL ES 3.1 Mesa 24.0"))
        assertNull(GpuSupport.parseVersion(null))
        assertNull(GpuSupport.parseVersion("4.6.0 NVIDIA"))
    }

    @Test
    fun `3_1 with the limits is supported`() {
        assertEquals(GpuSupport.Supported, GpuSupport.evaluate("OpenGL ES 3.1", 256, 8, 8))
        assertEquals(GpuSupport.Supported, GpuSupport.evaluate("OpenGL ES 3.2", 1024, 24, 8))
    }

    @Test
    fun `old versions and small limits are unsupported with a reason`() {
        assertUnsupported(GpuSupport.evaluate("OpenGL ES 3.0", 1024, 24, 8), "3.0")
        assertUnsupported(GpuSupport.evaluate("OpenGL ES 2.0", 1024, 24, 8), "2.0")
        assertUnsupported(GpuSupport.evaluate("OpenGL ES 3.1", 128, 24, 8), "invocations 128")
        assertUnsupported(GpuSupport.evaluate("OpenGL ES 3.1", 256, 4, 8), "bindings 4")
        assertUnsupported(GpuSupport.evaluate("OpenGL ES 3.1", 256, 24, 4), "compute SSBO blocks 4")
        assertUnsupported(GpuSupport.evaluate(null, 256, 8, 8), "GL_VERSION")
    }

    @Test
    fun `probe never throws, even without a GPU`() {
        // The unit-test android.jar stubs every EGL call: probe must turn that into Unsupported.
        val r = GpuSupport.probe()
        assertTrue(r is GpuSupport.Unsupported, "$r")
    }

    private fun assertUnsupported(r: GpuSupport, mention: String) {
        assertTrue(r is GpuSupport.Unsupported && mention in r.reason, "$r should mention $mention")
    }
}
