package com.huntercoles.splatman.viewer.presentation.crop

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.huntercoles.splatman.core.coroutines.DefaultDispatcher
import com.huntercoles.splatman.core.coroutines.IoDispatcher
import com.huntercoles.splatman.splat.edit.BoxFace
import com.huntercoles.splatman.splat.edit.CropBox
import com.huntercoles.splatman.splat.edit.cropToBox
import com.huntercoles.splatman.splat.model.SplatCloud
import com.huntercoles.splatman.viewer.crop.store.CropStore
import com.huntercoles.splatman.viewer.presentation.load.SplatSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Crop mode's state machine (#185). Box edits are applied synchronously; the fit and the crop run on [default],
 * the store on [io]. Nothing is kept in saved state: process death drops an edit.
 */
@HiltViewModel
class CropViewModel @Inject constructor(
    private val store: CropStore,
    @DefaultDispatcher private val default: CoroutineDispatcher,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {

    private val _uiState = MutableStateFlow<CropUiState>(CropUiState.Idle())
    val uiState: StateFlow<CropUiState> = _uiState

    private val _effects = Channel<CropEffect>(Channel.BUFFERED)
    val effects: Flow<CropEffect> = _effects.receiveAsFlow()

    private var source: SplatSource? = null
    private var cloud: SplatCloud? = null
    private var fit: CropBox? = null

    /** The source's `hasOriginal` read, cancelled when another source arrives. */
    private var sourceJob: Job? = null

    /** A fit, apply or revert in flight: every other intent waits for it to end. */
    private var work: Job? = null

    fun onIntent(intent: CropIntent) {
        if (work?.isActive == true) return
        when (intent) {
            is CropIntent.SourceReady -> sourceReady(intent.source)
            is CropIntent.Enter -> enter(intent.cloud)
            is CropIntent.MoveFace -> edit { box, selected -> box.moveFace(intent.face, intent.metres) to selected }
            is CropIntent.Nudge -> edit { box, selected ->
                val step = if (intent.out) NUDGE_STEP_METRES else -NUDGE_STEP_METRES
                box.moveFace(intent.face, step) to selected
            }
            is CropIntent.SelectFace -> edit { box, _ -> box to intent.face }
            CropIntent.ResetBox -> edit { box, selected -> (fit ?: box) to selected }
            CropIntent.Cancel -> cancel()
            CropIntent.Apply -> apply()
            CropIntent.Revert -> revert()
        }
    }

    private fun sourceReady(next: SplatSource) {
        sourceJob?.cancel()
        source = next
        cloud = null
        fit = null
        if (next is SplatSource.Document) {
            _uiState.value = CropUiState.Idle(offered = false, canRevert = false)
            return
        }
        _uiState.value = CropUiState.Idle(offered = true, canRevert = false)
        sourceJob = viewModelScope.launch {
            val canRevert = withContext(io) { store.hasOriginal(next) }
            _uiState.value = CropUiState.Idle(offered = true, canRevert = canRevert)
        }
    }

    private fun enter(loaded: SplatCloud) {
        val idle = _uiState.value as? CropUiState.Idle ?: return
        if (!idle.offered) return
        work = viewModelScope.launch {
            val box = withContext(default) { CropBox.fitCentral(loaded) }
            cloud = loaded
            fit = box
            _uiState.value = CropUiState.Editing(box, DEFAULT_FACE, _uiState.value.canRevert)
        }
    }

    /** Applies [change] to the box being edited; a shown message goes with it. */
    private inline fun edit(change: (CropBox, BoxFace) -> Pair<CropBox, BoxFace>) {
        val (box, selected) = editingBox() ?: return
        val (nextBox, nextFace) = change(box, selected)
        _uiState.value = CropUiState.Editing(nextBox, nextFace, _uiState.value.canRevert)
    }

    private fun editingBox(): Pair<CropBox, BoxFace>? = when (val state = _uiState.value) {
        is CropUiState.Editing -> state.box to state.selected
        is CropUiState.Refused -> state.box to state.selected
        else -> null
    }

    private fun cancel() {
        editingBox() ?: return
        leave(_uiState.value.canRevert)
    }

    private fun leave(canRevert: Boolean) {
        cloud = null
        fit = null
        _uiState.value = CropUiState.Idle(offered = true, canRevert = canRevert)
    }

    private fun apply() {
        val (box, selected) = editingBox() ?: return
        val target = source ?: return
        val loaded = cloud ?: return
        val canRevert = _uiState.value.canRevert
        _uiState.value = CropUiState.Applying(box, selected, canRevert)
        work = viewModelScope.launch {
            val cropped = withContext(default) { cropToBox(loaded, box) }
            if (cropped.count == 0) {
                _uiState.value = CropUiState.Refused(box, selected, canRevert, CropMessage.EMPTY)
                return@launch
            }
            if (cropped.count == loaded.count) {
                leave(canRevert)
                _effects.send(CropEffect.NothingCut)
                return@launch
            }
            val kept = try {
                withContext(io) {
                    store.apply(target, cropped)
                    store.hasOriginal(target)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = CropUiState.Refused(box, selected, canRevert, CropMessage.SAVE_ERROR)
                return@launch
            }
            leave(kept)
            _effects.send(CropEffect.ReloadSource)
        }
    }

    private fun revert() {
        val idle = _uiState.value as? CropUiState.Idle ?: return
        if (!idle.offered || !idle.canRevert) return
        val target = source ?: return
        work = viewModelScope.launch {
            val kept = try {
                withContext(io) {
                    store.revert(target)
                    store.hasOriginal(target)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _effects.send(CropEffect.RevertFailed)
                return@launch
            }
            _uiState.value = CropUiState.Idle(offered = true, canRevert = kept)
            _effects.send(CropEffect.ReloadSource)
        }
    }

    companion object {
        /** One In / Out tap, metres (docs/design/screens/crop.md: 1 cm). */
        const val NUDGE_STEP_METRES = 0.01f

        /** The face selected on Enter: Bottom (docs/design/screens/crop.md). */
        val DEFAULT_FACE = BoxFace.Y_NEG
    }
}
