package com.huntercoles.splatman.core.preferences

import android.content.Context
import android.content.SharedPreferences

/** The key-value seam under the app's preferences, so their defaults and persistence test on the JVM. */
interface PreferenceStore {
    fun getBoolean(key: String, default: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)
    fun getString(key: String): String?
    fun putString(key: String, value: String)
}

/** A [PreferenceStore] over the shared preferences file [name]. */
class SharedPreferenceStore(context: Context, name: String) : PreferenceStore {
    private val prefs: SharedPreferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    override fun getBoolean(key: String, default: Boolean): Boolean = prefs.getBoolean(key, default)

    override fun putBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }

    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }
}
