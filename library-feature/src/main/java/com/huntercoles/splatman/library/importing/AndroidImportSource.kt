package com.huntercoles.splatman.library.importing

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.FileNotFoundException
import java.io.InputStream

/** Makes the [ImportSource] for a picked or shared URI. */
fun interface ImportSourceFactory {
    fun create(uri: String): ImportSource
}

/** A document from the system picker or another app's "Open with", read through the [resolver]. Blocking. */
class AndroidImportSource(private val resolver: ContentResolver, private val uri: Uri) : ImportSource {

    override val fileName: String by lazy { queryName() ?: uri.lastPathSegment?.substringAfterLast('/') ?: FALLBACK }

    override fun open(): InputStream =
        resolver.openInputStream(uri) ?: throw FileNotFoundException("The file could not be opened.")

    private fun queryName(): String? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    private companion object {
        const val FALLBACK = "file"
    }
}
