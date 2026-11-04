package com.huntercoles.splatman.library.presentation.folders

/**
 * The folder actions of the Library (docs/design/screens/library.md "Folders: create, rename, move, delete",
 * ADR 0013 points 6-9): the name dialog, the delete confirm and the Move sheet.
 */
data class FolderActionsUiState(
    val dialog: NameDialog? = null,
    val deleteConfirm: DeleteConfirm? = null,
    val move: MoveSheet? = null,
)

/** What the name dialog is for. */
sealed interface NameDialogMode {
    data object New : NameDialogMode

    data class Rename(val folderId: String) : NameDialogMode

    /** "New folder" from the Move sheet: creates the folder and moves [scanIds] into it. */
    data class NewForMove(val scanIds: List<String>) : NameDialogMode
}

enum class NameError { None, Blank, Taken, TooLong }

/** The name dialog: [text] as typed, [error] from the last confirm, [saving] while the repository works. */
data class NameDialog(
    val mode: NameDialogMode,
    val text: String,
    val error: NameError = NameError.None,
    val saving: Boolean = false,
) {
    /** Create or Rename is enabled only for a name that is not blank once trimmed, and not while saving. */
    val canConfirm: Boolean get() = text.isNotBlank() && !saving
}

/** "Delete folder [name]?": its [scanCount] scans go to the Library, none is deleted. */
data class DeleteConfirm(val folderId: String, val name: String, val scanCount: Int)

/** The Move sheet for [scanIds]: the Library first, then every folder in name order. */
data class MoveSheet(val scanIds: List<String>, val targets: List<MoveTarget>)

/**
 * A place in the Move sheet: [folderId] and [name] null for the Library. [hereNow] when every selected scan is
 * already there.
 */
data class MoveTarget(val folderId: String?, val name: String?, val scanCount: Int, val hereNow: Boolean)

sealed interface FolderActionsEvent {
    /** [count] scans moved; the snackbar offers Undo. */
    data class Moved(val count: Int) : FolderActionsEvent

    /** Folder [name] was deleted; the snackbar offers Undo. */
    data class FolderDeleted(val name: String) : FolderActionsEvent

    data object MoveError : FolderActionsEvent

    data object FolderError : FolderActionsEvent

    /** The deleted folder [folderId] was the open one: the screen goes back to the Library. */
    data class LeftFolder(val folderId: String) : FolderActionsEvent
}

sealed interface FolderActionsIntent {
    data object NewFolder : FolderActionsIntent

    data class RenameFolder(val folderId: String) : FolderActionsIntent

    data class EditName(val text: String) : FolderActionsIntent

    data object ConfirmName : FolderActionsIntent

    data object CancelName : FolderActionsIntent

    data class AskDelete(val folderId: String) : FolderActionsIntent

    data object ConfirmDelete : FolderActionsIntent

    data object CancelDelete : FolderActionsIntent

    data class OpenMove(val scanIds: List<String>) : FolderActionsIntent

    data object NewFolderForMove : FolderActionsIntent

    /** Moves the Move sheet's scans to [folderId] (null = the Library). */
    data class MoveTo(val folderId: String?) : FolderActionsIntent

    data object CancelMove : FolderActionsIntent

    /** Undoes the last Moved or FolderDeleted. */
    data object Undo : FolderActionsIntent
}
