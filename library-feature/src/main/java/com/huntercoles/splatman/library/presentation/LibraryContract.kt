package com.huntercoles.splatman.library.presentation

import com.huntercoles.splatman.library.domain.model.OpenTarget
import com.huntercoles.splatman.library.domain.model.Sample
import com.huntercoles.splatman.library.domain.model.Scan
import com.huntercoles.splatman.library.domain.model.ScanStatus
import com.huntercoles.splatman.library.domain.model.ScanThumbnail
import com.huntercoles.splatman.library.domain.model.openTarget

/** What a library card shows under its thumbnail, and how its thumbnail is drawn. */
sealed interface CardState {
    /** Built: "date · size", opens the Viewer. */
    data class Ready(val splatCount: Int) : CardState

    /** Recorded, waiting for its build. */
    data object Captured : CardState

    /**
     * Being built: a progress bar along the thumbnail and "Building… 42%"; [percent] is null (an indeterminate bar
     * and "Building…") until the run reports progress.
     */
    data class Processing(val percent: Int? = null) : CardState

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
    /** The scan folder's size, formatted ("38 MB"); the metadata line reads "date · size". */
    val sizeText: String = "",
    /** The scan's `thumbnail.png`, or null: a plain block shows instead. */
    val thumbnail: ScanThumbnail? = null,
)

/** The body of the Library: placeholders on first load, the empty state, or the grid. */
sealed interface LibraryContent {
    data object Loading : LibraryContent

    data object Empty : LibraryContent

    data class Cards(val cards: List<LibraryCard>) : LibraryContent
}

/** Why Share is off: a scan with no splat yet (waiting or building), or one whose build failed. */
enum class ShareBlock { BUILDING, FAILED }

/** The reason [cards] cannot be shared (a failed build wins over one still to build), or null when all are built. */
fun shareBlockOf(cards: List<LibraryCard>): ShareBlock? = when {
    cards.any { it.state is CardState.Failed } -> ShareBlock.FAILED
    cards.any { it.state !is CardState.Ready } -> ShareBlock.BUILDING
    else -> null
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
    /** False once the phone answered that it cannot track motion: the Scan FAB dims and explains instead. */
    val scanAvailable: Boolean = true,
    /** True while the share files are being written. */
    val sharing: Boolean = false,
) {
    val selecting: Boolean get() = selectedIds.isNotEmpty()

    /** Why the selection cannot be shared (a scan in it has no splat yet, or its build failed), or null. */
    val shareBlock: ShareBlock?
        get() {
            val cards = (content as? LibraryContent.Cards)?.cards ?: return null
            return shareBlockOf(cards.filter { it.id in selectedIds })
        }

    /** Share in the selection bar is on: something is selected, all of it built, and no share is being written. */
    val canShare: Boolean get() = selecting && shareBlock == null && !sharing

    /** The empty state shows its own Start a scan button, so the FAB hides. */
    val showFab: Boolean get() = content !is LibraryContent.Empty

    /** The FAB at 38 % content alpha: this phone cannot scan. */
    val fabDimmed: Boolean get() = !scanAvailable

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

    /** Scan (the FAB or the empty state's button): opens Capture, or explains when the phone cannot track motion. */
    data object Scan : LibraryIntent

    /** Shares the selection, or [ids] when given (a card's TalkBack Share action), through the share sheet. */
    data class Share(val ids: Set<String>? = null) : LibraryIntent
}

/** One-off things the Library screen does for the ViewModel. */
sealed interface LibraryEvent {
    /** Go to Capture. */
    data object OpenScan : LibraryEvent

    /** Show the "can't track motion" snackbar with Details (the onboarding AR page). */
    data object ArUnsupported : LibraryEvent

    /** Open the share sheet for [files] (`ACTION_SEND` for one, `ACTION_SEND_MULTIPLE` for several). */
    data class Share(val files: SharedFiles) : LibraryEvent

    /** A scan to share was deleted before its file was written: say so in a snackbar. */
    data object ShareGone : LibraryEvent

    /** The share files could not be written: say so in a snackbar. */
    data object ShareFailed : LibraryEvent
}

/**
 * The card for this scan: READY carries its splat count, PROCESSING the build [percent] (null until the run reports
 * one), every other status maps to its own card state. [sizeText] is the folder size, already formatted.
 */
fun Scan.toCard(selected: Boolean = false, percent: Int? = null, sizeText: String = ""): LibraryCard = LibraryCard(
    id = id,
    name = name,
    createdAtMs = createdAtMs,
    state = when (status) {
        ScanStatus.READY -> CardState.Ready(splatCount)
        ScanStatus.CAPTURED -> CardState.Captured
        ScanStatus.PROCESSING -> CardState.Processing(percent)
        ScanStatus.FAILED -> CardState.Failed
    },
    target = openTarget(),
    selected = selected,
    imported = imported,
    sizeText = sizeText,
    thumbnail = thumbnail,
)
