package com.huntercoles.splatman.viewer.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.io.FileNotFoundException
import java.io.OutputStream
import javax.inject.Inject

/** Where export bytes go: a document the user created (SAF), or a cache file to share. Blocking. */
interface ExportTarget {
    /** [uri] is the `content://` URI `CreateDocument` returned. */
    fun openDocument(uri: String): OutputStream

    /** A fresh file `<cache>/exports/<fileName>`; earlier exports there are deleted first. */
    fun openShareFile(fileName: String): OutputStream

    /** The FileProvider URI of the share file [fileName], as a string. */
    fun shareUri(fileName: String): String
}

/** The FileProvider (app manifest, `res/xml/export_paths.xml`) serves only [DIR] under the cache. */
class AndroidExportTarget @Inject constructor(
    @ApplicationContext private val context: Context,
) : ExportTarget {

    private val dir: File get() = File(context.cacheDir, DIR)

    override fun openDocument(uri: String): OutputStream =
        context.contentResolver.openOutputStream(Uri.parse(uri), "wt")
            ?: throw FileNotFoundException("The file could not be created.")

    override fun openShareFile(fileName: String): OutputStream {
        val folder = dir
        folder.listFiles()?.forEach { it.delete() }
        if (!folder.isDirectory && !folder.mkdirs()) throw FileNotFoundException("No room for the export.")
        return file(fileName).outputStream()
    }

    override fun shareUri(fileName: String): String =
        FileProvider.getUriForFile(context, authority(context), file(fileName)).toString()

    private fun file(fileName: String): File {
        val f = File(dir, fileName)
        if (f.parentFile?.canonicalPath != dir.canonicalPath) throw FileNotFoundException("Not a file name: $fileName")
        return f
    }

    companion object {
        /** Must match `cache-path` in app/src/main/res/xml/export_paths.xml. */
        const val DIR = "exports"
        const val AUTHORITY_SUFFIX = ".exports"

        fun authority(context: Context): String = context.packageName + AUTHORITY_SUFFIX

        /** An `ACTION_SEND` chooser for a shared export, with read access granted to the receiver. */
        fun shareIntent(uri: String, fileName: String): Intent {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = ExportCodecs.MIME
                putExtra(Intent.EXTRA_STREAM, Uri.parse(uri))
                putExtra(Intent.EXTRA_TITLE, fileName)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            return Intent.createChooser(send, fileName).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class ExportModule {
    @Binds
    abstract fun bindTarget(target: AndroidExportTarget): ExportTarget
}
