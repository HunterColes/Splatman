package com.huntercoles.splatman.splat.edit

/**
 * Per-splat erase state for [count] splats on one `ByteArray(count)` (ADR 0014 rules 3, 6): [KEPT], [EARLIER]
 * (marked by an earlier stroke) or [LAST] (marked by the last stroke). Undo depth is one: [undo] clears the last
 * stroke only. [marked], [lastMarked] and [version] follow every change; [version] grows on each one, so the GL
 * thread re-uploads [bytes] only when it moved.
 *
 * Not thread-safe for writers, except [markUnderBrush] chunks over disjoint index ranges (see there). Readers
 * (the renderer) may read [bytes] without a lock: a byte is written whole.
 */
class EraseMask(val count: Int) {

    private val states = ByteArray(count)

    /** Splats in state [EARLIER] or [LAST]. */
    var marked: Int = 0
        private set

    /** Splats in state [LAST]. */
    var lastMarked: Int = 0
        private set

    @Volatile
    var version: Int = 0
        private set

    /** Read-only view of the states for the renderer's mask texture (ADR 0014 rule 4). Never write to it. */
    val bytes: ByteArray get() = states

    fun isMarked(i: Int): Boolean = states[i] != KEPT

    /** Ends the last stroke: its marks become earlier marks and can no longer be undone. */
    fun beginStroke() {
        if (lastMarked == 0) return
        for (i in 0 until count) if (states[i] == LAST) states[i] = EARLIER
        lastMarked = 0
        version++
    }

    /** Marks splat [i] in the last stroke; a splat already marked keeps its state. True when it was kept before. */
    fun mark(i: Int): Boolean {
        if (!markState(i)) return false
        commit(1)
        return true
    }

    /** Clears the last stroke's marks. True when anything changed. */
    fun undo(): Boolean {
        if (lastMarked == 0) return false
        for (i in 0 until count) if (states[i] == LAST) states[i] = KEPT
        marked -= lastMarked
        lastMarked = 0
        version++
        return true
    }

    /** Sets state [LAST] on a kept splat without touching the counts; pair with [commit]. */
    internal fun markState(i: Int): Boolean {
        if (states[i] != KEPT) return false
        states[i] = LAST
        return true
    }

    /** Adds [newly] last-stroke marks to the counts and grows [version]; one call per hit-test chunk. */
    @Synchronized
    internal fun commit(newly: Int) {
        if (newly == 0) return
        marked += newly
        lastMarked += newly
        version++
    }

    companion object {
        const val KEPT: Byte = 0
        const val EARLIER: Byte = 1
        const val LAST: Byte = 2
    }
}
