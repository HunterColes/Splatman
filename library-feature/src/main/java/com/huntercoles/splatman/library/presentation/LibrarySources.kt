package com.huntercoles.splatman.library.presentation

import kotlinx.coroutines.flow.Flow

/** The build percent of a scan being processed; the app binds it to the processing runner's state. */
fun interface BuildProgress {
    /** 0..100 for scan [scanId] while its run reports progress, null before that (or when nothing runs). */
    fun percent(scanId: String): Flow<Int?>
}

/** Whether this phone can scan at all (motion tracking); the app binds it to the flavour's tracker check. */
fun interface ScanSupport {
    /** False only when the phone cannot track motion; an install still to do counts as true. Never throws. */
    suspend fun canScan(): Boolean
}

/** A scan folder's size as the card shows it ("38 MB"); the app binds it to `Formatter.formatShortFileSize`. */
fun interface SizeText {
    fun format(bytes: Long): String
}

/** One scan to share: its id (a scan's, or a bundled sample's `sample:<file>`) and the name its file is called by. */
data class ShareScan(val id: String, val name: String)

/** Share files ready for the share sheet: their `content://` URIs and file names, in the same order. */
data class SharedFiles(val uris: List<String>, val fileNames: List<String>) {
    /** Every URI is a `content://` one (a FileProvider grant), never a `file://` path or anything else. */
    val allContent: Boolean
        get() = uris.isNotEmpty() && uris.size == fileNames.size && uris.all { it.startsWith(CONTENT_SCHEME) }

    private companion object {
        const val CONTENT_SCHEME = "content://"
    }
}

/**
 * Writes the share files of scans; the app binds it to viewer-feature's export (Settings' default export format,
 * the export FileProvider). Samples share their bundled file.
 */
fun interface ScanSharer {
    /** The files in [scans]' order, or null when one could not be written (its splat is gone or unreadable). */
    suspend fun prepare(scans: List<ShareScan>): SharedFiles?
}
