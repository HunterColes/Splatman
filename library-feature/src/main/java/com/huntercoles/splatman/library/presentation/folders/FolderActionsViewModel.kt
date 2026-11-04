package com.huntercoles.splatman.library.presentation.folders

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.huntercoles.splatman.library.domain.NaturalNameComparator
import com.huntercoles.splatman.library.domain.model.DeletedFolder
import com.huntercoles.splatman.library.domain.model.LibraryFolder
import com.huntercoles.splatman.library.domain.model.Scan
import com.huntercoles.splatman.library.domain.repository.FolderNameTaken
import com.huntercoles.splatman.library.domain.repository.FolderRepository
import com.huntercoles.splatman.library.domain.repository.ScanRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

private const val STOP_TIMEOUT_MS = 5_000L

/** What the user has open; the delete confirm and the Move sheet are filled from the live folders and scans. */
private data class Open(
    val dialog: NameDialog? = null,
    val deleteFolderId: String? = null,
    val moveScanIds: List<String>? = null,
)

/** What Undo puts back. */
private sealed interface Undoable {
    data class Move(val places: Map<String, String?>) : Undoable

    data class Delete(val deleted: DeletedFolder) : Undoable
}

/** Why a save failed: a name error to show in the dialog, or an event to send. */
private data class SaveFailure(val error: NameError = NameError.None, val event: FolderActionsEvent? = null)

/**
 * New folder, rename, delete folder and Move with Undo (docs/design/screens/library.md "Folders: create, rename,
 * move, delete", ADR 0013 points 6-9). The SavedStateHandle key [FOLDER_ID_KEY] is the open folder (absent = the
 * Library root): deleting it emits [FolderActionsEvent.LeftFolder]. A repository failure emits MoveError or
 * FolderError and leaves the dialog, the sheet and the scans as they were. Undo undoes the last Moved or
 * FolderDeleted, once.
 */
@HiltViewModel
class FolderActionsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val folderRepository: FolderRepository,
    scanRepository: ScanRepository,
) : ViewModel() {

    private val openFolderId: String? = savedStateHandle[FOLDER_ID_KEY]
    private val open = MutableStateFlow(Open())
    private val eventChannel = Channel<FolderActionsEvent>(Channel.BUFFERED)
    private var lastUndo: Undoable? = null

    /** What the screen shows once: the Undo snackbars, the error messages, leaving a deleted folder. */
    val events: Flow<FolderActionsEvent> = eventChannel.receiveAsFlow()

    val uiState: StateFlow<FolderActionsUiState> =
        combine(open, folderRepository.folders(), scanRepository.scans()) { open, folders, scans ->
            FolderActionsUiState(
                dialog = open.dialog,
                deleteConfirm = open.deleteFolderId?.let { id ->
                    folders.firstOrNull { it.id == id }?.let { folder ->
                        DeleteConfirm(folder.id, folder.name, scans.count { it.folderId == id })
                    }
                },
                move = open.moveScanIds?.let { ids -> MoveSheet(ids, moveTargets(ids, folders, scans)) },
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), FolderActionsUiState())

    fun onIntent(intent: FolderActionsIntent) {
        when (intent) {
            FolderActionsIntent.NewFolder -> openDialog(NameDialogMode.New, "")
            is FolderActionsIntent.RenameFolder -> viewModelScope.launch {
                val folder = folderRepository.folders().first().firstOrNull { it.id == intent.folderId }
                if (folder != null) openDialog(NameDialogMode.Rename(folder.id), folder.name)
            }
            is FolderActionsIntent.EditName -> setDialog {
                if (it.saving) it else it.copy(text = intent.text, error = NameError.None)
            }
            FolderActionsIntent.ConfirmName -> confirmName()
            FolderActionsIntent.CancelName -> open.update { it.copy(dialog = null) }
            is FolderActionsIntent.AskDelete -> open.update { it.copy(deleteFolderId = intent.folderId) }
            FolderActionsIntent.ConfirmDelete -> confirmDelete()
            FolderActionsIntent.CancelDelete -> open.update { it.copy(deleteFolderId = null) }
            is FolderActionsIntent.OpenMove -> open.update { it.copy(moveScanIds = intent.scanIds) }
            FolderActionsIntent.NewFolderForMove -> open.value.moveScanIds?.let {
                openDialog(NameDialogMode.NewForMove(it), "")
            }
            is FolderActionsIntent.MoveTo -> moveTo(intent.folderId)
            FolderActionsIntent.CancelMove -> open.update { it.copy(moveScanIds = null) }
            FolderActionsIntent.Undo -> undo()
        }
    }

    private fun openDialog(mode: NameDialogMode, text: String) {
        open.update { it.copy(dialog = NameDialog(mode, text)) }
    }

    private fun confirmName() {
        val dialog = open.value.dialog ?: return
        if (dialog.saving) return
        val name = dialog.text.trim()
        val localError = when {
            name.isEmpty() -> NameError.Blank
            name.codePointCount(0, name.length) > FolderRepository.MAX_NAME_LENGTH -> NameError.TooLong
            else -> NameError.None
        }
        if (localError != NameError.None) {
            setDialog { it.copy(error = localError) }
            return
        }
        setDialog { it.copy(saving = true, error = NameError.None) }
        viewModelScope.launch {
            val failure = when (val mode = dialog.mode) {
                NameDialogMode.New -> saveName { folderRepository.create(name) }
                is NameDialogMode.Rename -> saveName { folderRepository.rename(mode.folderId, name) }
                is NameDialogMode.NewForMove -> createAndMove(name, mode.scanIds)
            }
            if (failure == null) {
                val closesSheet = dialog.mode is NameDialogMode.NewForMove
                open.update { it.copy(dialog = null, moveScanIds = if (closesSheet) null else it.moveScanIds) }
            } else {
                setDialog { it.copy(saving = false, error = failure.error) }
                failure.event?.let { eventChannel.send(it) }
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun saveName(save: suspend () -> Unit): SaveFailure? = try {
        save()
        null
    } catch (e: CancellationException) {
        throw e
    } catch (_: FolderNameTaken) {
        SaveFailure(error = NameError.Taken)
    } catch (e: Exception) {
        Timber.w(e, "Folder save failed")
        SaveFailure(event = FolderActionsEvent.FolderError)
    }

    /** Creates the folder and moves [scanIds] into it; a failed move takes the new folder away again. */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun createAndMove(name: String, scanIds: List<String>): SaveFailure? {
        var created: LibraryFolder? = null
        saveName { created = folderRepository.create(name) }?.let { return it }
        val folder = created ?: return SaveFailure(event = FolderActionsEvent.FolderError)
        return try {
            val places = folderRepository.move(scanIds, folder.id)
            lastUndo = Undoable.Move(places)
            eventChannel.send(FolderActionsEvent.Moved(places.size))
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Move into a new folder failed")
            try {
                folderRepository.delete(folder.id)
            } catch (cleanup: Exception) {
                if (cleanup is CancellationException) throw cleanup
                Timber.w(cleanup, "Could not take the new folder away")
            }
            SaveFailure(event = FolderActionsEvent.MoveError)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun confirmDelete() {
        val id = open.value.deleteFolderId ?: return
        viewModelScope.launch {
            try {
                val deleted = folderRepository.delete(id)
                lastUndo = Undoable.Delete(deleted)
                open.update { it.copy(deleteFolderId = null) }
                if (id == openFolderId) eventChannel.send(FolderActionsEvent.LeftFolder(id))
                eventChannel.send(FolderActionsEvent.FolderDeleted(deleted.folder.name))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Folder delete failed")
                eventChannel.send(FolderActionsEvent.FolderError)
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun moveTo(folderId: String?) {
        val ids = open.value.moveScanIds ?: return
        viewModelScope.launch {
            try {
                val places = folderRepository.move(ids, folderId)
                lastUndo = Undoable.Move(places)
                open.update { it.copy(moveScanIds = null) }
                eventChannel.send(FolderActionsEvent.Moved(places.size))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Move failed")
                eventChannel.send(FolderActionsEvent.MoveError)
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun undo() {
        val undoable = lastUndo ?: return
        lastUndo = null
        viewModelScope.launch {
            try {
                when (undoable) {
                    is Undoable.Move -> folderRepository.restorePlaces(undoable.places)
                    is Undoable.Delete -> folderRepository.restore(undoable.deleted)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Undo failed")
                eventChannel.send(
                    if (undoable is Undoable.Move) FolderActionsEvent.MoveError else FolderActionsEvent.FolderError,
                )
            }
        }
    }

    private fun setDialog(change: (NameDialog) -> NameDialog) {
        open.update { state -> state.copy(dialog = state.dialog?.let(change)) }
    }

    companion object {
        /** The open folder's id, as on `NavigationDestination.LibraryFolder`; absent at the Library root. */
        const val FOLDER_ID_KEY = "folderId"
    }
}

/** The Library, then every folder in name order, each with its scan count and whether all of [ids] are there. */
internal fun moveTargets(ids: List<String>, folders: List<LibraryFolder>, scans: List<Scan>): List<MoveTarget> {
    val selected = scans.filter { it.id in ids }
    fun target(folderId: String?, name: String?) = MoveTarget(
        folderId = folderId,
        name = name,
        scanCount = scans.count { it.folderId == folderId },
        hereNow = selected.isNotEmpty() && selected.all { it.folderId == folderId },
    )
    val ordered = folders.sortedWith(
        Comparator<LibraryFolder> { a, b -> NaturalNameComparator.compare(a.name, b.name) }.thenBy { it.id },
    )
    return listOf(target(null, null)) + ordered.map { target(it.id, it.name) }
}
