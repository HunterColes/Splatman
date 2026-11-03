package com.huntercoles.splatman.library.data.prefs

import android.content.Context
import com.huntercoles.splatman.core.preferences.PreferenceStore
import com.huntercoles.splatman.core.preferences.SharedPreferenceStore
import com.huntercoles.splatman.library.domain.LibrarySort
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** The remembered Library sort (ADR 0013 point 10): one choice for the whole app, default [LibrarySort.NEWEST]. */
@Singleton
class LibraryPreferences(private val store: PreferenceStore) {

    @Inject
    constructor(@ApplicationContext context: Context) : this(SharedPreferenceStore(context, FILE))

    private val _sort = MutableStateFlow(readSort())
    val sort: StateFlow<LibrarySort> = _sort.asStateFlow()

    fun setSort(sort: LibrarySort) {
        store.putString(SORT_KEY, sort.name)
        _sort.value = sort
    }

    private fun readSort(): LibrarySort {
        val stored = store.getString(SORT_KEY)
        return LibrarySort.entries.firstOrNull { it.name == stored } ?: LibrarySort.NEWEST
    }

    companion object {
        private const val FILE = "library_prefs"
        private const val SORT_KEY = "sort"
    }
}
