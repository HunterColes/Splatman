package com.huntercoles.splatman.viewer.presentation.erase

import com.huntercoles.splatman.splat.edit.EraseMask
import com.huntercoles.splatman.splat.model.SplatCloud
import com.huntercoles.splatman.viewer.presentation.load.SplatSource

/** What the erase chrome (docs/design/screens/erase.md) sends to [EraseViewModel]. */
sealed interface EraseIntent {
    /** The Viewer reached Ready on [source]: erase is offered (not for a document). */
    data class SourceReady(val source: SplatSource) : EraseIntent

    /** Enter erase mode over the loaded [cloud]: one fresh [EraseMask] with nothing marked. */
    data class Enter(val cloud: SplatCloud) : EraseIntent

    /**
     * A finger went down: a new stroke (the last one becomes an earlier one). [viewProj] (16 floats, column-major)
     * and the [widthPx] x [heightPx] surface hold for the whole stroke; [radiusPx] is the brush radius in surface
     * pixels. The first point comes as a [StrokeTo].
     */
    class StrokeStart(
        val viewProj: FloatArray,
        val widthPx: Int,
        val heightPx: Int,
        val radiusPx: Float,
    ) : EraseIntent

    /** A touch point of the stroke, surface pixels (x right, y down). */
    data class StrokeTo(val x: Float, val y: Float) : EraseIntent

    data object StrokeEnd : EraseIntent

    /** Clears the last stroke's marks (ADR 0014 rule 6); waits for a pass in flight. */
    data object Undo : EraseIntent

    /** Marks isolated splats as one stroke (ADR 0014 rule 8, `markIsolated`). */
    data object RemoveIsolated : EraseIntent

    data class SetBrushSize(val size: EraseBrushSize) : EraseIntent

    /** Leaves erase mode, every mark dropped, nothing written. */
    data object Cancel : EraseIntent

    /** Cuts the marked splats through `CropStore.apply` (ADR 0014 rule 7). */
    data object Apply : EraseIntent
}

/** The brush sizes of docs/design/screens/erase.md (diameter in dp); Medium on first entry. */
enum class EraseBrushSize(val diameterDp: Int) { SMALL(32), MEDIUM(56), LARGE(96) }

/** The one-line refusals erase mode shows (copy keys `erase_all`, `erase_error`). */
enum class EraseMessage { ALL_MARKED, SAVE_ERROR }

/**
 * Erase mode's state; the Compose card only draws it. [mask] is what the Viewer hands to
 * `SplatRenderer.setEraseMask` (null outside erase mode); [marked] and [canUndo] are read from it as passes land.
 */
sealed interface EraseUiState {
    val marked: Int
    val canUndo: Boolean
    val brush: EraseBrushSize
    val mask: EraseMask?

    /** Not erasing; [offered] is false until a scan or sample is ready, and always for a document. */
    data class Idle(
        val offered: Boolean = false,
        override val brush: EraseBrushSize = EraseBrushSize.MEDIUM,
    ) : EraseUiState {
        override val marked: Int get() = 0
        override val canUndo: Boolean get() = false
        override val mask: EraseMask? get() = null
    }

    /** In erase mode with no splat marked: Undo and Apply off. */
    data class NothingMarked(
        override val brush: EraseBrushSize,
        override val mask: EraseMask,
        override val canUndo: Boolean = false,
    ) : EraseUiState {
        override val marked: Int get() = 0
    }

    /** A finger is down, or a hit pass or the isolated pass is still running. */
    data class Painting(
        override val marked: Int,
        override val canUndo: Boolean,
        override val brush: EraseBrushSize,
        override val mask: EraseMask,
    ) : EraseUiState

    data class Marked(
        override val marked: Int,
        override val canUndo: Boolean,
        override val brush: EraseBrushSize,
        override val mask: EraseMask,
    ) : EraseUiState

    /** Apply is running: every intent is ignored until it ends. */
    data class Applying(
        override val marked: Int,
        override val brush: EraseBrushSize,
        override val mask: EraseMask,
    ) : EraseUiState {
        override val canUndo: Boolean get() = false
    }

    /** Back to marked with [message] shown: every splat was marked, or the store failed. Nothing was written. */
    data class Refused(
        override val marked: Int,
        override val canUndo: Boolean,
        override val brush: EraseBrushSize,
        override val mask: EraseMask,
        val message: EraseMessage,
    ) : EraseUiState
}

/** One-shot events for the Viewer. */
sealed interface EraseEffect {
    /** An erase was applied: reload the source through the Viewer's Loading state. */
    data object ReloadSource : EraseEffect

    /** Remove isolated found nothing: no stroke recorded (`erase_isolated_none`). */
    data object IsolatedNone : EraseEffect
}
