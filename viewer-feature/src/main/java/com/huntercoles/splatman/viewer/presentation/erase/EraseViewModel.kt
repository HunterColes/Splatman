package com.huntercoles.splatman.viewer.presentation.erase

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.huntercoles.splatman.core.coroutines.DefaultDispatcher
import com.huntercoles.splatman.core.coroutines.IoDispatcher
import com.huntercoles.splatman.splat.edit.EraseMask
import com.huntercoles.splatman.splat.edit.keepUnmarked
import com.huntercoles.splatman.splat.edit.markIsolated
import com.huntercoles.splatman.splat.edit.markUnderBrush
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
 * Erase mode's state machine (#331, ADR 0014 rules 2, 6, 7, 8). Every change to the mask runs in order through one
 * worker on the main thread: a stroke start, a hit pass (on [default], one in flight; points that arrive meanwhile
 * go to the next pass), Undo and the isolated pass. `keepUnmarked` runs on [default], the store on [io] (the crop's
 * store binding, which tells `ScanChangeListener` itself). The [EraseMask] is the only per-splat allocation; it is
 * dropped on Cancel, Apply, a new source and [onCleared].
 */
@HiltViewModel
class EraseViewModel @Inject constructor(
    private val store: CropStore,
    @DefaultDispatcher private val default: CoroutineDispatcher,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {

    private val _uiState = MutableStateFlow<EraseUiState>(EraseUiState.Idle())
    val uiState: StateFlow<EraseUiState> = _uiState

    private val _effects = Channel<EraseEffect>(Channel.BUFFERED)
    val effects: Flow<EraseEffect> = _effects.receiveAsFlow()

    private var source: SplatSource? = null
    private var cloud: SplatCloud? = null
    private var mask: EraseMask? = null
    private var brush = EraseBrushSize.MEDIUM

    /** The stroke points are added to; null between strokes. */
    private var stroke: Brush? = null

    /** Mask work not yet run, in arrival order; [worker] runs it. */
    private val ops = ArrayDeque<Op>()
    private var worker: Job? = null

    /** Apply in flight: every intent is ignored until it ends. */
    private var applying: Job? = null

    fun onIntent(intent: EraseIntent) {
        if (applying?.isActive == true) return
        when (intent) {
            is EraseIntent.SourceReady -> sourceReady(intent.source)
            is EraseIntent.Enter -> enter(intent.cloud)
            is EraseIntent.StrokeStart -> strokeStart(intent)
            is EraseIntent.StrokeTo -> strokeTo(intent.x, intent.y)
            EraseIntent.StrokeEnd -> strokeEnd()
            EraseIntent.Undo -> enqueue(Op.Undo)
            EraseIntent.RemoveIsolated -> enqueue(Op.Isolated)
            is EraseIntent.SetBrushSize -> setBrush(intent.size)
            EraseIntent.Cancel -> if (mask != null) leave()
            EraseIntent.Apply -> apply()
        }
    }

    private fun sourceReady(next: SplatSource) {
        source = next
        leave(offered = next !is SplatSource.Document)
    }

    private fun enter(loaded: SplatCloud) {
        val idle = _uiState.value as? EraseUiState.Idle ?: return
        if (!idle.offered) return
        cloud = loaded
        mask = EraseMask(loaded.count)
        publish()
    }

    private fun strokeStart(intent: EraseIntent.StrokeStart) {
        if (mask == null) return
        val next = Brush(intent.viewProj.copyOf(), intent.widthPx, intent.heightPx, intent.radiusPx)
        stroke = next
        enqueue(Op.Begin)
    }

    private fun strokeTo(x: Float, y: Float) {
        val current = stroke ?: return
        val last = ops.lastOrNull()
        val points = if (last is Op.Points && last.brush === current) last else Op.Points(current).also { ops += it }
        points.add(x, y)
        runOps()
    }

    private fun strokeEnd() {
        stroke ?: return
        stroke = null
        publish()
    }

    private fun setBrush(size: EraseBrushSize) {
        brush = size
        val idle = _uiState.value as? EraseUiState.Idle
        if (idle != null) _uiState.value = idle.copy(brush = size) else publish()
    }

    private fun enqueue(op: Op) {
        if (mask == null) return
        ops += op
        runOps()
    }

    /** Starts the worker unless it runs: it takes ops until none is left, publishing after each. */
    private fun runOps() {
        publish()
        if (worker?.isActive == true) return
        worker = viewModelScope.launch {
            while (true) {
                val op = ops.removeFirstOrNull() ?: break
                val m = mask ?: break
                val c = cloud ?: break
                run(op, m, c)
                publish()
            }
            worker = null
            publish()
        }
    }

    private suspend fun run(op: Op, m: EraseMask, c: SplatCloud) {
        when (op) {
            Op.Begin -> m.beginStroke()
            is Op.Points -> {
                val b = op.brush
                val points = op.polyline()
                withContext(default) {
                    markUnderBrush(c.positions, b.viewProj, b.widthPx, b.heightPx, points, b.radiusPx, m)
                }
            }
            Op.Undo -> m.undo()
            Op.Isolated -> {
                val newly = withContext(default) { markIsolated(c, m) }
                if (newly == 0) _effects.send(EraseEffect.IsolatedNone)
            }
        }
    }

    private fun busy() = stroke != null || worker?.isActive == true || ops.isNotEmpty()

    /** The state read from the mask; nothing outside erase mode. */
    private fun publish() {
        val m = mask ?: return
        val marked = m.marked
        val canUndo = m.lastMarked > 0
        _uiState.value = when {
            busy() -> EraseUiState.Painting(marked, canUndo, brush, m)
            marked == 0 -> EraseUiState.NothingMarked(brush, m, canUndo)
            else -> EraseUiState.Marked(marked, canUndo, brush, m)
        }
    }

    private fun apply() {
        val m = mask ?: return
        val c = cloud ?: return
        val target = source ?: return
        if (busy() || m.marked == 0) return
        val canUndo = m.lastMarked > 0
        if (m.marked >= c.count) {
            _uiState.value = EraseUiState.Refused(m.marked, canUndo, brush, m, EraseMessage.ALL_MARKED)
            return
        }
        _uiState.value = EraseUiState.Applying(m.marked, brush, m)
        applying = viewModelScope.launch {
            try {
                val kept = withContext(default) { keepUnmarked(c, m) }
                withContext(io) { store.apply(target, kept) }
            } catch (e: CancellationException) {
                throw e
            } catch (ignored: Exception) {
                _uiState.value = EraseUiState.Refused(m.marked, canUndo, brush, m, EraseMessage.SAVE_ERROR)
                return@launch
            }
            leave()
            _effects.send(EraseEffect.ReloadSource)
        }
    }

    /** Drops the mask and every pending op; nothing is written. */
    private fun leave(offered: Boolean = source.let { it != null && it !is SplatSource.Document }) {
        worker?.cancel()
        worker = null
        ops.clear()
        stroke = null
        mask = null
        cloud = null
        _uiState.value = EraseUiState.Idle(offered = offered, brush = brush)
    }

    override fun onCleared() {
        leave()
        super.onCleared()
    }

    /** One stroke's camera and brush; [lastX], [lastY] carry the polyline from one pass to the next. */
    private class Brush(val viewProj: FloatArray, val widthPx: Int, val heightPx: Int, val radiusPx: Float) {
        var hasLast = false
        var lastX = 0f
        var lastY = 0f
    }

    private sealed interface Op {
        data object Begin : Op

        /** Points of [brush] waiting for one hit pass. */
        class Points(val brush: Brush) : Op {
            private var xy = FloatArray(INITIAL_POINTS * 2)
            private var size = 0

            fun add(x: Float, y: Float) {
                if (size + 2 > xy.size) xy = xy.copyOf(xy.size * 2)
                xy[size++] = x
                xy[size++] = y
            }

            /** The points after the stroke's last tested one, so the segment between two passes is covered. */
            fun polyline(): FloatArray {
                val lead = if (brush.hasLast) 2 else 0
                val out = FloatArray(lead + size)
                if (brush.hasLast) {
                    out[0] = brush.lastX
                    out[1] = brush.lastY
                }
                xy.copyInto(out, lead, 0, size)
                brush.hasLast = true
                brush.lastX = xy[size - 2]
                brush.lastY = xy[size - 1]
                return out
            }
        }

        data object Undo : Op
        data object Isolated : Op
    }

    private companion object {
        const val INITIAL_POINTS = 16
    }
}
