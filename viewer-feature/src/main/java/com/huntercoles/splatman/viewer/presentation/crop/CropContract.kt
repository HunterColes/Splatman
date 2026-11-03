package com.huntercoles.splatman.viewer.presentation.crop

import com.huntercoles.splatman.splat.edit.BoxFace
import com.huntercoles.splatman.splat.edit.CropBox
import com.huntercoles.splatman.splat.model.SplatCloud
import com.huntercoles.splatman.viewer.presentation.load.SplatSource

/** What the crop chrome (docs/design/screens/crop.md) sends to [CropViewModel]. */
sealed interface CropIntent {
    /** The Viewer reached Ready on [source]: crop is offered (not for a document) and `canRevert` is read. */
    data class SourceReady(val source: SplatSource) : CropIntent

    /** Enter crop mode over the loaded [cloud]: the box starts at `CropBox.fitCentral(cloud)`. */
    data class Enter(val cloud: SplatCloud) : CropIntent

    /** A drag moved [face] by [metres] along its outward normal (positive = out). */
    data class MoveFace(val face: BoxFace, val metres: Float) : CropIntent

    /** In / Out: move [face] one nudge step toward the centre (`out = false`) or away from it. */
    data class Nudge(val face: BoxFace, val out: Boolean) : CropIntent

    data class SelectFace(val face: BoxFace) : CropIntent

    data object ResetBox : CropIntent

    data object Cancel : CropIntent

    data object Apply : CropIntent

    data object Revert : CropIntent
}

/** The one-line messages crop mode shows (copy keys `crop_empty`, `crop_error`). */
enum class CropMessage { EMPTY, SAVE_ERROR }

/** Crop mode's state; the Compose card only draws it. */
sealed interface CropUiState {
    /** True while an original is kept (`CropStore.hasOriginal`): the overflow offers Revert crop. */
    val canRevert: Boolean

    /** Not cropping; [offered] is false until a scan or sample is ready, and always for a document. */
    data class Idle(val offered: Boolean = false, override val canRevert: Boolean = false) : CropUiState

    data class Editing(val box: CropBox, val selected: BoxFace, override val canRevert: Boolean) : CropUiState

    /** Apply is running: every intent is ignored until it ends. */
    data class Applying(val box: CropBox, val selected: BoxFace, override val canRevert: Boolean) : CropUiState

    /** Back to editing with [message] shown: the crop was empty, or the store failed. Nothing was written. */
    data class Refused(
        val box: CropBox,
        val selected: BoxFace,
        override val canRevert: Boolean,
        val message: CropMessage,
    ) : CropUiState
}

/** One-shot events for the Viewer. */
sealed interface CropEffect {
    /** A crop was applied or reverted: reload the source through the Viewer's Loading state. */
    data object ReloadSource : CropEffect
}
