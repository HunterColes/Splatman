package com.huntercoles.splatman.library.domain

import java.text.Collator

/**
 * The Library's sort choice (ADR 0013 point 10). Every order ends on the same tie-break: newest first, then id.
 * SIZE puts unsized items (null, e.g. scans not READY) after every sized one.
 */
enum class LibrarySort {
    NEWEST,
    OLDEST,
    NAME,
    SIZE,
    ;

    fun <T> comparator(
        name: (T) -> String,
        createdAtMs: (T) -> Long,
        sizeBytes: (T) -> Long?,
        id: (T) -> String,
    ): Comparator<T> {
        val tie = compareByDescending<T> { createdAtMs(it) }.thenBy { id(it) }
        return when (this) {
            NEWEST -> tie
            OLDEST -> compareBy<T> { createdAtMs(it) }.thenBy { id(it) }
            NAME -> Comparator<T> { a, b -> NaturalNameComparator.compare(name(a), name(b)) }.then(tie)
            SIZE -> compareBy<T> { sizeBytes(it) == null }
                .thenByDescending { sizeBytes(it) ?: 0L }
                .then(tie)
        }
    }
}

/**
 * Natural name order: digit runs compare as numbers (leading zeros ignored, so "Scan 02" equals "Scan 2"), text runs
 * by [Collator] at PRIMARY strength for the default locale (case and accents ignored). A digit run sorts before a
 * text run at the same position; a name that runs out first sorts first.
 */
object NaturalNameComparator : Comparator<String> {

    private val collator: ThreadLocal<Collator> = ThreadLocal.withInitial {
        Collator.getInstance().apply { strength = Collator.PRIMARY }
    }

    override fun compare(a: String, b: String): Int {
        val left = runs(a)
        val right = runs(b)
        val textCollator = collator.get()
        for (i in 0 until minOf(left.size, right.size)) {
            val x = left[i]
            val y = right[i]
            val xDigits = x[0].isAsciiDigit()
            val yDigits = y[0].isAsciiDigit()
            val result = when {
                xDigits && yDigits -> compareNumbers(x, y)
                xDigits -> -1
                yDigits -> 1
                else -> textCollator.compare(x, y)
            }
            if (result != 0) return result
        }
        return left.size.compareTo(right.size)
    }

    private fun Char.isAsciiDigit() = this in '0'..'9'

    private fun runs(s: String): List<String> {
        val out = ArrayList<String>()
        var start = 0
        while (start < s.length) {
            val digits = s[start].isAsciiDigit()
            var end = start + 1
            while (end < s.length && s[end].isAsciiDigit() == digits) end++
            out += s.substring(start, end)
            start = end
        }
        return out
    }

    private fun compareNumbers(x: String, y: String): Int {
        val a = x.trimStart('0')
        val b = y.trimStart('0')
        return if (a.length != b.length) a.length.compareTo(b.length) else a.compareTo(b)
    }
}
