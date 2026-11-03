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
