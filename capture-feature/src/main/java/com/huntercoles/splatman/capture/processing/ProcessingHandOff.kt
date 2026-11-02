package com.huntercoles.splatman.capture.processing

import java.io.File

/** Starts processing a scan folder in the background (the foreground [ProcessingService] on a phone). */
fun interface ProcessingStarter {
    fun start(scan: File)
}

/**
 * Told when the service starts a scan's run ([ProcessingState.Running]) and how it ended ([ProcessingState.Done],
 * [ProcessingState.Cancelled] or [ProcessingState.Failed]). [scanId] is the scan's folder name. The app binds one
 * that keeps the library's rows in step; called on the main thread, so it must not block.
 */
fun interface ProcessingListener {
    fun onProcessing(scanId: String, state: ProcessingState)
}

/** The scan folders under [root] (`filesDir/scans`); a scan id is its folder name. */
class ScanFolders(val root: File) {
    /** The folder of [scanId], or null when the id is not a plain folder name (a sample, a path, blank). */
    fun folder(scanId: String): File? {
        val plain = scanId.isNotBlank() && scanId.trim() == scanId && !scanId.startsWith(".") &&
            scanId.none { it == '/' || it == '\\' || it == ':' }
        return if (plain) File(root, scanId) else null
    }
}

/** The scan's display name for the notification (the library row's name), or null when it has none. */
fun interface ScanNames {
    suspend fun name(scanId: String): String?
}

/** Deletes a scan (its row and folder); the app binds the library's delete, which stops a run first. */
fun interface ScanDeleter {
    suspend fun delete(scanId: String)
}
