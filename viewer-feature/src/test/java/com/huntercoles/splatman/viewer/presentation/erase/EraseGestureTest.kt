package com.huntercoles.splatman.viewer.presentation.erase

import com.huntercoles.splatman.splat.edit.EraseMask
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** #332: one finger paints, two fingers move the camera, and the chrome's enabled rules (erase.md "States"). */
class EraseGestureTest {

    private val calls = mutableListOf<String>()
    private val gesture = EraseGesture(
        object : EraseGesture.Sink {
            override fun start(x: Float, y: Float) {
                calls += "start $x,$y"
            }

            override fun to(x: Float, y: Float) {
                calls += "to $x,$y"
            }

            override fun end() {
                calls += "end"
            }
        },
    )

    @Test
    fun `one finger paints from touch-down to lift and never moves the camera`() {
        assertFalse(gesture.event(1, 10f, 20f))
        assertFalse(gesture.event(1, 15f, 20f))
        assertFalse(gesture.event(0, 15f, 20f))
        assertEquals(listOf("start 10.0,20.0", "to 15.0,20.0", "end"), calls)
    }

    @Test
    fun `a single tap is a stroke of one point`() {
        gesture.event(1, 5f, 5f)
        gesture.event(0, 5f, 5f)
        assertEquals(listOf("start 5.0,5.0", "end"), calls)
    }

    @Test
    fun `a second finger ends the stroke and the rest of the gesture moves the camera until every finger lifts`() {
        gesture.event(1, 0f, 0f)
        gesture.event(1, 1f, 0f)
        assertTrue(gesture.event(2, 2f, 0f))
        assertTrue(gesture.event(2, 3f, 0f))
        // Back to one finger: no one-finger orbit and no new stroke in erase mode.
        assertFalse(gesture.event(1, 4f, 0f))
        assertFalse(gesture.event(0, 4f, 0f))
        assertEquals(listOf("start 0.0,0.0", "to 1.0,0.0", "end"), calls)
        // The next gesture paints again.
        gesture.event(1, 9f, 9f)
        assertEquals("start 9.0,9.0", calls.last())
    }

    @Test
    fun `two fingers landing together never paint`() {
        assertTrue(gesture.event(2, 0f, 0f))
        gesture.event(0, 0f, 0f)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `a gesture that starts while painting is not allowed paints nothing to the end`() {
        gesture.event(1, 0f, 0f, canPaint = false)
        gesture.event(1, 5f, 0f, canPaint = true)
        gesture.event(0, 5f, 0f)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `a cut gesture ends the stroke in progress once`() {
        gesture.event(1, 0f, 0f)
        gesture.cancel()
        gesture.cancel()
        assertEquals(listOf("start 0.0,0.0", "end"), calls)
        assertFalse(gesture.painting)
    }

    private val mask = EraseMask(10)
    private val brush = EraseBrushSize.MEDIUM

    @Test
    fun `nothing marked leaves Undo and Apply off and hides the count`() {
        val c = EraseControls.of(EraseUiState.NothingMarked(brush, mask), isolating = false, fingerDown = false)
        assertFalse(c.applyEnabled)
        assertFalse(c.undoEnabled)
        assertFalse(c.showCount)
        assertTrue(c.sizeEnabled && c.isolatedEnabled && c.cancelEnabled)
    }

    @Test
    fun `a stroke that marked nothing leaves Apply off`() {
        val c = EraseControls.of(EraseUiState.NothingMarked(brush, mask, canUndo = false), false, false)
        assertFalse(c.applyEnabled)
    }

    @Test
    fun `marked enables all four and shows the count`() {
        val c = EraseControls.of(EraseUiState.Marked(3, canUndo = true, brush, mask), false, false)
        assertTrue(c.applyEnabled && c.undoEnabled && c.cancelEnabled && c.isolatedEnabled)
        assertTrue(c.showCount && c.marked)
    }

    @Test
    fun `painting keeps Apply off while a pass is in flight and the size picker off while a finger is down`() {
        val c = EraseControls.of(EraseUiState.Painting(3, canUndo = true, brush, mask), false, fingerDown = true)
        assertFalse(c.applyEnabled)
        assertFalse(c.sizeEnabled)
        assertTrue(c.undoEnabled)
    }

    @Test
    fun `finding isolated leaves only Cancel`() {
        val c = EraseControls.of(EraseUiState.Painting(3, canUndo = true, brush, mask), isolating = true, false)
        assertTrue(c.isolating)
        assertTrue(c.cancelEnabled)
        assertFalse(c.applyEnabled || c.undoEnabled || c.sizeEnabled || c.isolatedEnabled)
    }

    @Test
    fun `applying enables nothing`() {
        val c = EraseControls.of(EraseUiState.Applying(3, brush, mask), false, false)
        assertTrue(c.applying)
        assertFalse(c.applyEnabled || c.undoEnabled || c.sizeEnabled || c.isolatedEnabled || c.cancelEnabled)
    }

    @Test
    fun `a refusal shows its message and acts as marked`() {
        val state = EraseUiState.Refused(10, canUndo = true, brush, mask, EraseMessage.ALL_MARKED)
        val c = EraseControls.of(state, false, false)
        assertEquals(EraseMessage.ALL_MARKED, c.message)
        assertTrue(c.applyEnabled && c.undoEnabled)
    }
}
