package com.huntercoles.splatman.library.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** #310: `LibraryText.key` per ADR 0013 point 2: trim, NFD, drop combining marks, lowercase(Locale.ROOT). */
class LibraryTextTest {

    @Test
    fun `A4 Cafe with an accent and CAFE give the same key`() {
        assertEquals(LibraryText.key("CAFE"), LibraryText.key("Café"))
        assertEquals("cafe", LibraryText.key("Café"))
        // Precomposed é (U+00E9) and e + combining acute (U+0301) key alike.
        assertEquals(LibraryText.key("Café"), LibraryText.key("Café"))
    }

    @Test
    fun `A4 emoji Hebrew and Arabic survive unchanged`() {
        assertEquals("🌵🏠", LibraryText.key("🌵🏠"))
        assertEquals("👩‍🚀 scan", LibraryText.key("👩‍🚀 Scan"))
        assertEquals("שלום", LibraryText.key("שלום"))
        assertEquals("مرحبا", LibraryText.key("مرحبا"))
    }

    @Test
    fun `A4 surrounding spaces drop and inner ones stay`() {
        assertEquals("desk plant", LibraryText.key("  Desk plant \t"))
        assertEquals("שלום", LibraryText.key(" שלום "))
    }

    @Test
    fun `H4 an empty or blank name keys to the empty string`() {
        assertEquals("", LibraryText.key(""))
        assertEquals("", LibraryText.key("   "))
    }
}
