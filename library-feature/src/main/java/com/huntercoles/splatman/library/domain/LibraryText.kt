package com.huntercoles.splatman.library.domain

import java.text.Normalizer
import java.util.Locale

/** Text folding for Library names (ADR 0013 point 2). */
object LibraryText {
    private val combiningMarks = Regex("\\p{Mn}+")

    /**
     * The comparison key of a name: trimmed, Unicode NFD, combining marks dropped, lowercased in [Locale.ROOT].
     * "Café" and "CAFE" share a key; emoji and Hebrew or Arabic text pass through unchanged.
     */
    fun key(name: String): String =
        Normalizer.normalize(name.trim(), Normalizer.Form.NFD)
            .replace(combiningMarks, "")
            .lowercase(Locale.ROOT)
}
