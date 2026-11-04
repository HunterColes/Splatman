package com.huntercoles.splatman.splat.edit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** #328: EraseMask states 0 kept / 1 earlier stroke / 2 last stroke, undo depth one (ADR 0014 rules 3, 6). */
class EraseMaskTest {

    @Test
    fun `A2 mark beginStroke mark undo leaves only the first stroke's marks`() {
        val mask = EraseMask(5)
        mask.mark(0)
        mask.mark(3)
        mask.beginStroke()
        mask.mark(1)
        mask.mark(4)
        assertTrue(mask.undo())
        assertEquals(listOf(true, false, false, true, false), (0 until 5).map { mask.isMarked(it) })
        assertEquals(2, mask.marked)
        assertEquals(0, mask.lastMarked)
    }

    @Test
    fun `A2 undo twice changes nothing the second time`() {
        val mask = EraseMask(4)
        mask.mark(0)
        mask.beginStroke()
        mask.mark(2)
        assertTrue(mask.undo())
        val version = mask.version
        assertFalse(mask.undo())
        assertEquals(version, mask.version)
        assertEquals(listOf(true, false, false, false), (0 until 4).map { mask.isMarked(it) })
        assertEquals(1, mask.marked)
        assertEquals(0, mask.lastMarked)
    }

    @Test
    fun `A2 counts and version follow every change`() {
        val mask = EraseMask(6)
        assertEquals(6, mask.count)
        assertEquals(0, mask.marked)
        assertEquals(0, mask.lastMarked)
        var v = mask.version

        mask.mark(1)
        assertEquals(1, mask.marked)
        assertEquals(1, mask.lastMarked)
        assertTrue(mask.version > v, "mark grows version")
        v = mask.version

        mask.mark(2)
        assertEquals(2, mask.marked)
        assertEquals(2, mask.lastMarked)
        assertTrue(mask.version > v, "second mark grows version")
        v = mask.version

        mask.beginStroke()
        assertEquals(2, mask.marked)
        assertEquals(0, mask.lastMarked)
        assertTrue(mask.version > v, "beginStroke over marks grows version")
        v = mask.version

        mask.mark(5)
        assertEquals(3, mask.marked)
        assertEquals(1, mask.lastMarked)
        assertTrue(mask.version > v, "mark in a new stroke grows version")
        v = mask.version

        assertTrue(mask.undo())
        assertEquals(2, mask.marked)
        assertEquals(0, mask.lastMarked)
        assertTrue(mask.version > v, "undo grows version")
    }

    @Test
    fun `A2 a splat marked earlier is not moved to the last stroke by a new mark`() {
        val mask = EraseMask(3)
        mask.mark(0)
        mask.beginStroke()
        mask.mark(0)
        assertEquals(1, mask.marked)
        assertEquals(0, mask.lastMarked)
        assertFalse(mask.undo(), "nothing in the last stroke to undo")
        assertTrue(mask.isMarked(0))
        assertEquals(1, mask.marked)
    }

    @Test
    fun `A2 marking the same splat twice in one stroke counts once`() {
        val mask = EraseMask(3)
        mask.mark(2)
        mask.mark(2)
        assertEquals(1, mask.marked)
        assertEquals(1, mask.lastMarked)
        assertTrue(mask.undo())
        assertFalse(mask.isMarked(2))
        assertEquals(0, mask.marked)
    }

    @Test
    fun `H2 an 8M mask is one 8 MB ByteArray`() {
        val n = 8_000_000
        val mask = EraseMask(n)
        mask.mark(n - 1)
        assertTrue(mask.isMarked(n - 1))
        // Every array the mask holds, by reflection: exactly one ByteArray of n bytes and nothing else sized by n.
        val arrays = mutableListOf<Any>()
        var c: Class<*>? = mask.javaClass
        while (c != null && c != Any::class.java) {
            for (f in c.declaredFields) {
                if (java.lang.reflect.Modifier.isStatic(f.modifiers) || !f.type.isArray) continue
                f.isAccessible = true
                f.get(mask)?.let { arrays += it }
            }
            c = c.superclass
        }
        val bytes = arrays.filterIsInstance<ByteArray>()
        assertEquals(1, bytes.size, "one ByteArray")
        assertEquals(n, bytes[0].size)
        val others = arrays.filter { it !is ByteArray }.sumOf { java.lang.reflect.Array.getLength(it) }
        assertTrue(others < 1024, "no other per-splat array ($others elements)")
    }
}
