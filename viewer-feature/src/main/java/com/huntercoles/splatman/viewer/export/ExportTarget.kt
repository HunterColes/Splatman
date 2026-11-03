package com.huntercoles.splatman.viewer.export

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
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

/** Where export bytes go: documents the user created (SAF), or cache files to share. Blocking. */
interface ExportTarget {
    /** [uri] is the `content://` URI `CreateDocument` returned, or one [createInFolder] made. */
    fun openDocument(uri: String): OutputStream

    /** A new document [fileName] in the folder [treeUri] `OpenDocumentTree` returned; its URI. */
    fun createInFolder(treeUri: String, fileName: String): String

    /** Removes a document this export created (a cancelled or failed write). */
    fun deleteDocument(uri: String)

    /** Deletes every earlier share file. */
    fun clearShareFiles()

    /** A fresh file `<cache>/exports/<fileName>`. */
    fun openShareFile(fileName: String): OutputStream

    /** The FileProvider URI of the share file [fileName], as a string. */
    fun shareUri(fileName: String): String

    /** Free bytes on the app's storage, or null when unknown. */
    fun freeBytes(): Long?
}

/** The FileProvider (app manifest, `res/xml/export_paths.xml`) serves only [DIR] under the cache. */
class AndroidExportTarget @Inject constructor(
    @ApplicationContext private val context: Context,
) : ExportTarget {

    private val dir: File get() = File(context.cacheDir, DIR)

    override fun openDocument(uri: String): OutputStream =
        context.contentResolver.openOutputStream(Uri.parse(uri), "wt")
            ?: throw FileNotFoundException("The file could not be created.")

    override fun createInFolder(treeUri: String, fileName: String): String {
        val tree = Uri.parse(treeUri)
        val folder = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val doc = DocumentsContract.createDocument(context.contentResolver, folder, ExportCodecs.MIME, fileName)
            ?: throw FileNotFoundException("The file could not be created.")
        return doc.toString()
    }

    override fun deleteDocument(uri: String) {
        DocumentsContract.deleteDocument(context.contentResolver, Uri.parse(uri))
    }

    override fun clearShareFiles() {
        dir.listFiles()?.forEach { it.delete() }
    }

    override fun openShareFile(fileName: String): OutputStream {
        val folder = dir
        if (!folder.isDirectory && !folder.mkdirs()) throw FileNotFoundException("No room for the export.")
        return file(fileName).outputStream()
    }

    override fun shareUri(fileName: String): String =
        FileProvider.getUriForFile(context, authority(context), file(fileName)).toString()

    override fun freeBytes(): Long? = context.cacheDir.usableSpace.takeIf { it > 0 }

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

        /** [shareIntent] for one file. */
        fun shareIntent(uri: String, fileName: String): Intent = shareIntent(ShareRequest(listOf(uri), listOf(fileName)))

        /**
         * A chooser for shared exports with read access granted to the receiver: `ACTION_SEND` for one file,
         * `ACTION_SEND_MULTIPLE` for several.
         */
        fun shareIntent(share: ShareRequest): Intent {
            val uris = share.uris.map(Uri::parse)
            val title = share.fileNames.joinToString()
            val send = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.single())
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            }.apply {
                type = ExportCodecs.MIME
                putExtra(Intent.EXTRA_TITLE, title)
                clipData = ClipData.newRawUri(title, uris.first()).also { clip ->
                    uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
                }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            return Intent.createChooser(send, title).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class ExportModule {
    @Binds
    abstract fun bindTarget(target: AndroidExportTarget): ExportTarget
}
