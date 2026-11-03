package com.huntercoles.splatman.library.domain

/** Library search (ADR 0013 point 11): substring match on [LibraryText.key]; a blank query filters nothing. */
object LibrarySearch {

    fun matches(query: String, name: String): Boolean {
        val q = LibraryText.key(query)
        return q.isEmpty() || LibraryText.key(name).contains(q)
    }

    /** The [items] whose [name] matches [query], in their input order. */
    fun <T> filter(items: List<T>, query: String, name: (T) -> String): List<T> {
        val q = LibraryText.key(query)
        if (q.isEmpty()) return items
        return items.filter { LibraryText.key(name(it)).contains(q) }
    }
}
