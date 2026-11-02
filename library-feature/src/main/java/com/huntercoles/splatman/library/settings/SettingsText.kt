package com.huntercoles.splatman.library.settings

import java.util.Locale

private const val UNIT_STEP = 1000.0
private const val ONE_DECIMAL_BELOW = 10.0
private val UNITS = listOf("B", "KB", "MB", "GB", "TB")

/**
 * A byte count as Storage shows it: decimal units (as Android's file sizes are), one decimal under 10 ("1.2 GB"),
 * whole numbers above ("340 MB"), plain bytes under a kilobyte ("512 B").
 */
fun formatBytes(bytes: Long, locale: Locale = Locale.getDefault()): String {
    if (bytes < UNIT_STEP) return "${bytes.coerceAtLeast(0)} B"
    var value = bytes.toDouble()
    var unit = 0
    // Compare the value as it will print, so 999.6 KB rolls over to 1.0 MB rather than showing "1000 KB".
    while (unit < UNITS.lastIndex && printed(value, Locale.ROOT).toDouble() >= UNIT_STEP) {
        value /= UNIT_STEP
        unit++
    }
    return "${printed(value, locale)} ${UNITS[unit]}"
}

private fun printed(value: Double, locale: Locale): String =
    String.format(locale, if (value < ONE_DECIMAL_BELOW) "%.1f" else "%.0f", value)

/** One open-source dependency of the build, from the generated `licences.tsv`. */
data class Licence(val module: String, val version: String, val licence: String, val url: String?)

/**
 * Parses `licences.tsv` (module, version, licence name, licence URL; tab-separated, one a line), as the app's build
 * writes it. Blank and malformed lines are skipped; a blank URL is null. Sorted by module.
 */
fun parseLicences(text: String): List<Licence> = text.lineSequence()
    .map { it.split('\t') }
    .filter { it.size >= LICENCE_FIELDS && it[0].isNotBlank() }
    .map { Licence(it[0].trim(), it[1].trim(), it[2].trim(), it.getOrNull(3)?.trim()?.takeIf(String::isNotEmpty)) }
    .sortedBy { it.module }
    .toList()

private const val LICENCE_FIELDS = 3

/** A bundled sample's credit, from the table in `assets/samples/ASSETS-LICENSES.md`. */
data class SampleCredit(val file: String, val author: String, val source: String?, val licence: String)

private val URL = Regex("""https?://[^\s,)]+""")
private const val SAMPLE_FIELDS = 6
private const val AUTHOR = 3
private const val SOURCE = 4
private const val LICENCE = 5

/**
 * The credits in the samples' licence note: each table row whose first cell is a file name in backticks gives
 * file, format, splats, author, source and licence. The source is the first URL in its cell.
 */
fun parseSampleCredits(markdown: String): List<SampleCredit> = markdown.lineSequence()
    .map { it.trim() }
    .filter { it.startsWith("| `") }
    .map { line -> line.trim('|').split('|').map(String::trim) }
    .filter { it.size >= SAMPLE_FIELDS }
    .map { cells ->
        SampleCredit(
            file = cells[0].trim('`'),
            author = cells[AUTHOR],
            source = URL.find(cells[SOURCE])?.value,
            licence = cells[LICENCE],
        )
    }
    .toList()
