package com.huntercoles.splatman.library.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * #310: Library search per ADR 0013 point 11: a name matches when `key(query)` is a substring of `key(name)`;
 * a blank query filters nothing. `LibrarySearch.filter` keeps the input order.
 */
class LibrarySearchTest {

    private data class Named(val id: Int, val name: String)

    private val names = listOf("Café corner", "Desk plant", "🌵 cactus", "שלום עולם", "مرحبا", "Garden 2", "")
    private val items = names.mapIndexed { i, n -> Named(i, n) }

    private fun search(query: String): List<String> = LibrarySearch.filter(items, query) { it.name }.map { it.name }

    @Test
    fun `A3 a blank or spaces-only query keeps everything in order`() {
        assertEquals(names, search(""))
        assertEquals(names, search("   "))
        assertEquals(names, search("\t "))
        assertTrue(LibrarySearch.matches("", "anything"))
        assertTrue(LibrarySearch.matches("  ", "anything"))
    }

    @Test
    fun `A3 cafe finds Cafe with an accent`() {
        assertEquals(listOf("Café corner"), search("cafe"))
        assertEquals(listOf("Café corner"), search("CAFÉ"))
        assertEquals(listOf("Café corner"), search("  fé cor "))
        assertTrue(LibrarySearch.matches("cafe", "Café"))
    }

    @Test
    fun `A3 an emoji query finds the emoji name`() {
        assertEquals(listOf("🌵 cactus"), search("🌵"))
        assertTrue(LibrarySearch.matches("👩‍🚀", "Trip 👩‍🚀 one"))
    }

    @Test
    fun `A3 a Hebrew query finds the Hebrew name and an Arabic one the Arabic name`() {
        assertEquals(listOf("שלום עולם"), search("שלום"))
        assertEquals(listOf("שלום עולם"), search("עולם"))
        assertEquals(listOf("مرحبا"), search("رحب"))
    }

    @Test
    fun `A3 H1 no match gives an empty list`() {
        assertEquals(emptyList<String>(), search("zebra"))
        assertEquals(emptyList<String>(), search("🦓"))
        assertEquals(emptyList<Named>(), LibrarySearch.filter(emptyList<Named>(), "desk") { it.name })
        assertFalse(LibrarySearch.matches("zebra", "Desk plant"))
    }

    @Test
    fun `H4 an empty name matches only a blank query`() {
        assertTrue(LibrarySearch.matches("", ""))
        assertTrue(LibrarySearch.matches("  ", ""))
        assertFalse(LibrarySearch.matches("a", ""))
    }

    @Test
    fun `H5 equal names all match and keep their order`() {
        val same = listOf(Named(3, "Desk"), Named(1, "Desk"), Named(2, "desk"))
        assertEquals(listOf(3, 1, 2), LibrarySearch.filter(same, "DESK") { it.name }.map { it.id })
    }
}
