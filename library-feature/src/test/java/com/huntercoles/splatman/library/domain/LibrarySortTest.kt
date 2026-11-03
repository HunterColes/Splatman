package com.huntercoles.splatman.library.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.text.Collator
import kotlin.random.Random

/**
 * #310: Library sort per ADR 0013 point 10. NEWEST/OLDEST by createdAtMs; NAME natural (digit runs as numbers,
 * text runs by Collator at PRIMARY for the default locale); SIZE by sizeBytes descending, unsized (null) last.
 * Ties: newest first, then id.
 *
 * Shape pinned here: `enum class LibrarySort { NEWEST, OLDEST, NAME, SIZE }` with
 * `fun <T> comparator(name: (T) -> String, createdAtMs: (T) -> Long, sizeBytes: (T) -> Long?, id: (T) -> String)`,
 * and `object NaturalNameComparator : Comparator<String>`.
 */
class LibrarySortTest {

    private data class Item(val id: String, val name: String, val createdAtMs: Long, val sizeBytes: Long?)

    private fun List<Item>.sortedBy(sort: LibrarySort): List<Item> =
        sortedWith(sort.comparator({ it.name }, { it.createdAtMs }, { it.sizeBytes }, { it.id }))

    private fun List<Item>.ids(sort: LibrarySort): List<String> = sortedBy(sort).map { it.id }

    private fun assertSameOrderBothWays(expected: List<String>, items: List<Item>, sort: LibrarySort) {
        assertEquals(expected, items.ids(sort), "$sort, input forwards")
        assertEquals(expected, items.reversed().ids(sort), "$sort, input reversed")
        assertEquals(expected, items.shuffled(Random(7)).ids(sort), "$sort, input shuffled")
    }

    // a and b share a timestamp (tie -> id); c is unsized; d is the largest.
    private val items = listOf(
        Item("b", "Bravo", createdAtMs = 300, sizeBytes = 10),
        Item("a", "alpha", createdAtMs = 300, sizeBytes = 10),
        Item("c", "Charlie", createdAtMs = 100, sizeBytes = null),
        Item("d", "Delta", createdAtMs = 200, sizeBytes = 99),
        Item("e", "Echo", createdAtMs = 400, sizeBytes = null),
    )

    @Test
    fun `A1 the four sorts exist in order`() {
        assertEquals(listOf("NEWEST", "OLDEST", "NAME", "SIZE"), LibrarySort.entries.map { it.name })
    }

    @Test
    fun `A1 NEWEST is createdAtMs descending, ties by id`() {
        assertSameOrderBothWays(listOf("e", "a", "b", "d", "c"), items, LibrarySort.NEWEST)
    }

    @Test
    fun `A1 OLDEST is createdAtMs ascending, ties by id`() {
        assertSameOrderBothWays(listOf("c", "d", "a", "b", "e"), items, LibrarySort.OLDEST)
    }

    @Test
    fun `A1 NAME ignores case`() {
        assertSameOrderBothWays(listOf("a", "b", "c", "d", "e"), items, LibrarySort.NAME)
    }

    @Test
    fun `A1 SIZE is largest first, equal sizes newest then id, unsized last whichever way the list comes in`() {
        // Sized: d (99), then a and b (10, same time -> id). Unsized: e (newer) before c.
        assertSameOrderBothWays(listOf("d", "a", "b", "e", "c"), items, LibrarySort.SIZE)
    }

    @Test
    fun `A1 SIZE puts a zero-byte item before the unsized ones`() {
        val list = listOf(
            Item("x", "x", 1, null),
            Item("z", "z", 1, 0),
            Item("y", "y", 2, 5),
        )
        assertSameOrderBothWays(listOf("y", "z", "x"), list, LibrarySort.SIZE)
    }

    @Test
    fun `A2 natural order puts Scan 2 before Scan 10 before scan 11`() {
        val c = NaturalNameComparator
        assertTrue(c.compare("Scan 2", "Scan 10") < 0)
        assertTrue(c.compare("Scan 10", "scan 11") < 0)
        assertTrue(c.compare("Scan 2", "scan 11") < 0)
        assertTrue(c.compare("Scan 10", "Scan 2") > 0)
        val list = listOf(
            Item("1", "scan 11", 1, null),
            Item("2", "Scan 2", 1, null),
            Item("3", "Scan 10", 1, null),
        )
        assertSameOrderBothWays(listOf("2", "3", "1"), list, LibrarySort.NAME)
    }

    @Test
    fun `A2 Cafe with an accent sorts beside Cafe`() {
        assertEquals(0, NaturalNameComparator.compare("Café", "Cafe"))
        assertEquals(0, NaturalNameComparator.compare("Café", "CAFE"))
        val list = listOf(
            Item("dog", "Dog", 1, null),
            Item("cafe", "Cafe", 1, null),
            Item("bistro", "Bistro", 1, null),
            Item("cafe2", "Café", 2, null),
        )
        // Café and Cafe tie on name, so the newer (cafe2) comes first.
        assertSameOrderBothWays(listOf("bistro", "cafe2", "cafe", "dog"), list, LibrarySort.NAME)
    }

    @Test
    fun `A2 leading zeros tie and fall back to newest then id`() {
        assertEquals(0, NaturalNameComparator.compare("Scan 02", "Scan 2"))
        val list = listOf(
            Item("p", "Scan 2", 5, null),
            Item("q", "Scan 02", 9, null),
            Item("r", "Scan 002", 5, null),
            Item("s", "Scan 3", 9, null),
        )
        assertSameOrderBothWays(listOf("q", "p", "r", "s"), list, LibrarySort.NAME)
    }

    @Test
    fun `H4 empty names sort first by NAME and tie among themselves`() {
        val list = listOf(
            Item("n2", "Desk", 1, null),
            Item("e1", "", 1, null),
            Item("e2", "", 3, null),
        )
        assertSameOrderBothWays(listOf("e2", "e1", "n2"), list, LibrarySort.NAME)
    }

    @Test
    fun `H5 equal names fall back to newest then id`() {
        val list = listOf(
            Item("k3", "Desk", 10, 1),
            Item("k1", "Desk", 10, 1),
            Item("k2", "desk", 20, 1),
        )
        assertSameOrderBothWays(listOf("k2", "k1", "k3"), list, LibrarySort.NAME)
        assertSameOrderBothWays(listOf("k2", "k1", "k3"), list, LibrarySort.SIZE)
        assertSameOrderBothWays(listOf("k2", "k1", "k3"), list, LibrarySort.NEWEST)
        assertSameOrderBothWays(listOf("k1", "k3", "k2"), list, LibrarySort.OLDEST)
    }

    @Test
    fun `H2 emoji and RTL names sort without error and tie only with themselves`() {
        val list = listOf(
            Item("h", "שלום", 1, null),
            Item("ar", "مرحبا", 1, null),
            Item("em", "🌵", 1, null),
            Item("la", "Desk", 1, null),
        )
        val sorted = list.ids(LibrarySort.NAME)
        assertEquals(list.map { it.id }.toSet(), sorted.toSet())
        assertEquals(sorted, list.reversed().ids(LibrarySort.NAME))
    }

    // --- H3 / Proof: 500 generated names, sorted and filtered, checked against an independent oracle. ---

    /** Words with no digits, none a prefix of another; [hasCafe] is whether its key contains "cafe". */
    private data class Word(val text: String, val hasCafe: Boolean)

    private val words = listOf(
        Word("Café", true),
        Word("CAFE", true),
        Word("Bistro café", true),
        Word("Desk", false),
        Word("garden", false),
        Word("Ögon", false),
        Word("Zebra", false),
        Word("kitchen", false),
        Word("שלום", false),
        Word("مرحبا", false),
        Word("🌵", false),
        Word("👩‍🚀 trip", false),
    )

    private data class Gen(val item: Item, val word: Word, val number: Int)

    private fun generate(): List<Gen> {
        val rnd = Random(310)
        return List(500) { i ->
            val word = words[rnd.nextInt(words.size)]
            val number = rnd.nextInt(0, 40)
            val digits = if (rnd.nextInt(5) == 0) "00$number" else "$number" // some leading zeros
            val size = if (rnd.nextInt(4) == 0) null else rnd.nextLong(0, 50)
            Gen(Item("id-%03d".format(i), "${word.text} $digits", rnd.nextLong(0, 60), size), word, number)
        }
    }

    private val tie: Comparator<Gen> =
        compareByDescending<Gen> { it.item.createdAtMs }.thenBy { it.item.id }

    private fun oracle(sort: LibrarySort): Comparator<Gen> {
        val collator = Collator.getInstance().apply { strength = Collator.PRIMARY }
        return when (sort) {
            LibrarySort.NEWEST -> tie
            LibrarySort.OLDEST -> compareBy<Gen> { it.item.createdAtMs }.thenBy { it.item.id }
            LibrarySort.NAME -> Comparator<Gen> { a, b -> collator.compare(a.word.text, b.word.text) }
                .thenBy { it.number }
                .then(tie)
            LibrarySort.SIZE -> compareBy<Gen> { it.item.sizeBytes == null }
                .thenByDescending { it.item.sizeBytes ?: 0 }
                .then(tie)
        }
    }

    @Test
    fun `H3 500 generated names sort by every rule`() {
        val gen = generate()
        val byItem = gen.associateBy { it.item }
        for (sort in LibrarySort.entries) {
            val expected = gen.sortedWith(oracle(sort)).map { it.item.id }
            val actual = gen.map { it.item }.sortedBy(sort)
            assertEquals(expected, actual.map { it.id }, "$sort over 500 items")
            assertEquals(expected, gen.reversed().map { it.item }.sortedBy(sort).map { it.id }, "$sort reversed")
            if (sort == LibrarySort.SIZE) {
                val firstUnsized = actual.indexOfFirst { byItem.getValue(it).item.sizeBytes == null }
                assertTrue(actual.drop(firstUnsized).all { it.sizeBytes == null }, "unsized last")
            }
        }
    }

    @Test
    fun `H3 500 generated names filter then keep the chosen sort`() {
        val gen = generate()
        val sorted = gen.map { it.item }.sortedBy(LibrarySort.NAME)
        val hits = LibrarySearch.filter(sorted, "cafe") { it.name }
        val wanted = gen.filter { it.word.hasCafe }.map { it.item.id }.toSet()
        assertEquals(sorted.filter { it.id in wanted }, hits)
        assertTrue(hits.isNotEmpty())
        assertEquals(gen.count { it.word.text == "שלום" }, LibrarySearch.filter(sorted, "שלום") { it.name }.size)
        assertEquals(gen.count { it.word.text == "🌵" }, LibrarySearch.filter(sorted, "🌵") { it.name }.size)
        assertEquals(emptyList<Item>(), LibrarySearch.filter(sorted, "no such scan") { it.name })
        assertEquals(sorted, LibrarySearch.filter(sorted, "  ") { it.name })
    }
}
