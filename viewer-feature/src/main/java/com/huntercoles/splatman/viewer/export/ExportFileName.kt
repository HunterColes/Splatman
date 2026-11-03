package com.huntercoles.splatman.viewer.export

/**
 * `<scan name>.<ext>` for an export: a known splat extension on the scan name is dropped, anything outside letters,
 * digits, space, `-`, `_` and `.` becomes `_`, leading dots and edge spaces go, and the stem is cut to [MAX_STEM]
 * characters, so the result is always a single safe file name (never a path).
 */
object ExportFileName {
    const val MAX_STEM = 80
    const val FALLBACK = "splat"

    private val KNOWN = Regex("""\.(ply|splat|spz|ksplat)$""", RegexOption.IGNORE_CASE)
    private val UNSAFE = Regex("""[^\p{L}\p{N} ._-]""")

    fun of(scanName: String, format: ExportFormat): String {
        val stem = scanName.trim()
            .replace(KNOWN, "")
            .replace(UNSAFE, "_")
            .trimStart('.', ' ')
            .take(MAX_STEM)
            .trimEnd('.', ' ')
        return stem.ifEmpty { FALLBACK } + "." + format.extension
    }

    /** [of] for each scan, with a name that repeats an earlier one (ignoring case) numbered: `Name (2).ply`. */
    fun unique(scanNames: List<String>, format: ExportFormat): List<String> {
        val taken = HashSet<String>()
        return scanNames.map { scanName ->
            val base = of(scanName, format)
            val stem = base.removeSuffix("." + format.extension)
            var name = base
            var n = 2
            while (!taken.add(name.lowercase())) {
                name = "$stem (${n++})." + format.extension
            }
            name
        }
    }
}
