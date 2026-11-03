package com.huntercoles.splatman.library.domain.model

/** A Library folder (ADR 0013): one level, a database row only; scans name it by [Scan.folderId]. */
data class LibraryFolder(val id: String, val name: String, val createdAtMs: Long)

/** What deleting [folder] moved to the Library root: its [scanIds], so Undo can put them back. */
data class DeletedFolder(val folder: LibraryFolder, val scanIds: List<String>)
