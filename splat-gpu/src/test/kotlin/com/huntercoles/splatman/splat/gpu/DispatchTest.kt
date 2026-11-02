package com.huntercoles.splatman.splat.gpu

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class DispatchTest {
    @Test
    fun `no items need no groups`() {
        assertEquals(0, Dispatch.groups(0, 256))
    }

    @Test
    fun `groups round up`() {
        assertEquals(1, Dispatch.groups(1, 256))
        assertEquals(1, Dispatch.groups(256, 256))
        assertEquals(2, Dispatch.groups(257, 256))
        assertEquals(4096, Dispatch.groups(1 shl 20, 256))
        assertEquals(7, Dispatch.groups(7, 1))
    }

    @Test
    fun `the largest n does not overflow`() {
        assertEquals(Dispatch.MAX_GROUPS, Dispatch.groups(Dispatch.MAX_GROUPS * 32_768, 32_768))
        assertEquals(1, Dispatch.groups(Int.MAX_VALUE, Int.MAX_VALUE))
    }

    @Test
    fun `more groups than GLES guarantees is rejected`() {
        assertThrows<IllegalArgumentException> { Dispatch.groups(Dispatch.MAX_GROUPS * 256 + 1, 256) }
        assertThrows<IllegalArgumentException> { Dispatch.groups(Int.MAX_VALUE, 256) }
    }

    @Test
    fun `bad arguments are rejected`() {
        assertThrows<IllegalArgumentException> { Dispatch.groups(-1, 256) }
        assertThrows<IllegalArgumentException> { Dispatch.groups(10, 0) }
        assertThrows<IllegalArgumentException> { Dispatch.groups(10, -4) }
    }
}
