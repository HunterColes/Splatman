package com.huntercoles.splatman.viewer.rendering.gl

import com.huntercoles.splatman.splat.sort.DepthSort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Sorts one scene's splats back to front on a worker coroutine (ADR 0006). The GL thread offers each frame's view
 * with [requestSort] (never blocks: the channel is conflated, so the worker always sorts the newest view) and picks
 * up the newest finished order with [takeOrder], handing the array back with [recycle] once uploaded. At most three
 * order arrays live at once (being written, ready, being uploaded). The worker re-sorts only when [ResortPolicy]
 * says the view changed enough, or after [invalidate].
 *
 * [positions] are the scene's centres (3 per splat), read but never written.
 */
class SplatSortWorker(
    private val positions: FloatArray,
    private val count: Int,
    sceneRadius: Float,
    scope: CoroutineScope,
) {
    private val views = Channel<FloatArray>(Channel.CONFLATED)
    private val ready = AtomicReference<IntArray?>(null)
    private val free = ConcurrentLinkedQueue<IntArray>()
    private val forced = AtomicBoolean(false)

    /** Completed sorts, for tests and the debug overlay. */
    @Volatile
    var sorts: Int = 0
        private set

    private val job = scope.launch {
        val sort = DepthSort(count)
        val depths = FloatArray(count)
        var policy = ResortPolicy(sceneRadius)
        for (view in views) {
            if (forced.getAndSet(false)) policy = ResortPolicy(sceneRadius)
            if (!policy.needsSort(view)) continue
            ResortPolicy.depths(view, positions, count, depths)
            val out = free.poll() ?: IntArray(count)
            sort.backToFront(depths, count, out)
            policy.markSorted(view)
            sorts++
            ready.getAndSet(out)?.let { free.offer(it) }
        }
    }

    /** Offers the current view (column-major world-to-camera); copied, so the caller may reuse [view]. */
    fun requestSort(view: FloatArray) {
        views.trySend(view.copyOf())
    }

    /** The newest finished order (`count` indices, farthest first) not yet taken, or null. */
    fun takeOrder(): IntArray? = ready.getAndSet(null)

    /** Returns an array from [takeOrder] for reuse. */
    fun recycle(order: IntArray) {
        free.offer(order)
    }

    /** Sort again on the next request even if the view did not move (the GL order buffer was lost). */
    fun invalidate() {
        forced.set(true)
    }

    fun close() {
        views.close()
        job.cancel()
    }
}
