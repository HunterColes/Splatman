package com.huntercoles.splatman.library.presentation

import com.huntercoles.splatman.library.domain.model.OpenTarget
import com.huntercoles.splatman.library.domain.model.Sample
import com.huntercoles.splatman.library.domain.model.Scan
import com.huntercoles.splatman.library.domain.model.ScanStatus
import com.huntercoles.splatman.library.domain.model.openTarget

/** What a library card shows under its thumbnail, and how its thumbnail is drawn. */
sealed interface CardState {
    /** Built: "date · N splats", opens the Viewer. */
    data class Ready(val splatCount: Int) : CardState

    /** Recorded, waiting for its build. */
    data object Captured : CardState

    /** Being built: a progress bar along the thumbnail and "Building…". */
    data object Processing : CardState

    /** The build failed: dimmed thumbnail and the "Couldn't build" badge. */
    data object Failed : CardState
}

/** One card in the grid. */
data class LibraryCard(
    val id: String,
    val name: String,
    val createdAtMs: Long,
    val state: CardState,
    val target: OpenTarget,
    val selected: Boolean = false,
    /** Came from a file (the import flow): the thumbnail carries an "Imported" chip. */
    val imported: Boolean = false,
)

/** The body of the Library: placeholders on first load, the empty state, or the grid. */
sealed interface LibraryContent {
    data object Loading : LibraryContent

    data object Empty : LibraryContent

    data class Cards(val cards: List<LibraryCard>) : LibraryContent
}

/** Scans taken off the grid but kept on disk until the Undo window closes. */
data class PendingDelete(val ids: Set<String>)

data class LibraryUiState(
    val content: LibraryContent = LibraryContent.Loading,
    val samples: List<Sample> = emptyList(),
    val selectedIds: Set<String> = emptySet(),
    /** Non-null while the delete dialog asks about these scans. */
    val confirmDelete: Set<String>? = null,
    /** Non-null while the "Deleted n" snackbar offers Undo. */
    val undo: PendingDelete? = null,
) {
    val selecting: Boolean get() = selectedIds.isNotEmpty()

    /** The empty state shows its own Start a scan button, so the FAB hides. */
    val showFab: Boolean get() = content !is LibraryContent.Empty

    /** The sample "Try a sample" opens, if any is bundled. */
    val firstSample: Sample? get() = samples.firstOrNull()
}

sealed interface LibraryIntent {
    /** Long-press: enters selection mode with [id] selected (keeps it selected when already selecting). */
    data class LongPress(val id: String) : LibraryIntent

    /** Tap while selecting. */
    data class Toggle(val id: String) : LibraryIntent

    data object ExitSelection : LibraryIntent

    /** Asks to delete the selection, or [ids] when given (a card's TalkBack Delete action). */
    data class RequestDelete(val ids: Set<String>? = null) : LibraryIntent

    data object CancelDelete : LibraryIntent

    data object ConfirmDelete : LibraryIntent

    data object Undo : LibraryIntent

    /** The Undo snackbar went away without Undo: delete now. */
    data object CommitDelete : LibraryIntent

    data class Rename(val id: String, val name: String) : LibraryIntent
}

/** The card for this scan: READY carries its splat count, every other status maps to its own card state. */
fun Scan.toCard(selected: Boolean = false): LibraryCard = LibraryCard(
    id = id,
    name = name,
    createdAtMs = createdAtMs,
    state = when (status) {
        ScanStatus.READY -> CardState.Ready(splatCount)
        ScanStatus.CAPTURED -> CardState.Captured
        ScanStatus.PROCESSING -> CardState.Processing
        ScanStatus.FAILED -> CardState.Failed
    },
    target = openTarget(),
    selected = selected,
    imported = imported,
)
