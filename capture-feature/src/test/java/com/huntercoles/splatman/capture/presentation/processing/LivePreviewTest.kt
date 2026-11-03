package com.huntercoles.splatman.capture.presentation.processing

import com.huntercoles.splatman.capture.processing.ProcessingState
import com.huntercoles.splatman.splat.math.Quat
import com.huntercoles.splatman.splat.math.Vec3
import com.huntercoles.splatman.splat.model.SplatCloud
import com.huntercoles.splatman.splat.packet.TrainingProgress
import com.huntercoles.splatman.splat.train.still.SplatStill
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.io.File

class LivePreviewTest {

    private val background = 0xFF0A0A0D.toInt()

    /** An L of coloured splats: asymmetric about the vertical, so a turn changes the picture. */
    private fun cloud(): SplatCloud {
        val points = listOf(
            Vec3(0f, 0f, 0f), Vec3(0.3f, 0f, 0f), Vec3(0.6f, 0f, 0f), Vec3(0f, 0.3f, 0f), Vec3(0f, 0.6f, 0f),
            Vec3(0f, 0f, 0.3f),
        )
        val n = points.size
        return SplatCloud(
            n,
            0,
            FloatArray(3 * n) { points[it / 3].let { p -> floatArrayOf(p.x, p.y, p.z)[it % 3] } },
            FloatArray(3 * n) { -2.5f },
            FloatArray(4 * n) { if (it % 4 == 0) 1f else 0f },
            FloatArray(n) { 3f },
            FloatArray(3 * n) { k -> if (k % 3 == (k / 3) % 3) 1.5f else -1f },
            FloatArray(0),
        )
    }

    private fun progress(snapshot: SplatCloud?) = TrainingProgress(250, 1000, 0.1f, snapshot?.count ?: 0, 0L, snapshot)

    private fun ui(state: ProcessingState, snapshot: SplatCloud? = null, thumbnail: File? = null) =
        ProcessingUiState("scan", state = state, snapshot = snapshot, thumbnail = thumbnail)

    @Test
    fun `the placeholder shows until the first snapshot`() {
        assertEquals(PreviewSource.Placeholder, ui(ProcessingState.Idle).previewSource())
        assertEquals(PreviewSource.Placeholder, ui(ProcessingState.Running(null, prepared = 0.5f)).previewSource())
        assertEquals(PreviewSource.Placeholder, ui(ProcessingState.Running(progress(null))).previewSource())
    }

    @Test
    fun `the newest snapshot turns while running and holds still while hot`() {
        val c = cloud()
        assertEquals(PreviewSource.Cloud(c, turning = true), ui(ProcessingState.Running(progress(c)), c).previewSource())
        assertEquals(PreviewSource.Cloud(c, turning = false), ui(ProcessingState.Paused(progress(c)), c).previewSource())
    }

    @Test
    fun `Done shows the finished splat, else the saved still, else the placeholder`() {
        val c = cloud()
        val done = ProcessingState.Done(File("splat.ply"))
        val thumb = File("thumbnail.png")
        assertEquals(PreviewSource.Cloud(c, turning = false), ui(done, c, thumb).previewSource())
        assertEquals(PreviewSource.Still(thumb), ui(done, null, thumb).previewSource())
        assertEquals(PreviewSource.Placeholder, ui(done).previewSource())
    }

    @Test
    fun `a failure or a cancel shows the placeholder even with a snapshot`() {
        val c = cloud()
        assertEquals(PreviewSource.Placeholder, ui(ProcessingState.Failed("boom"), c).previewSource())
        assertEquals(PreviewSource.Placeholder, ui(ProcessingState.Cancelled, c).previewSource())
    }

    @Test
    fun `the held snapshot is the newest, survives Done and clears when a run restarts`() {
        val first = cloud()
        val second = cloud()
        var held = heldSnapshot(null, ProcessingState.Running(null))
        assertNull(held)
        held = heldSnapshot(held, ProcessingState.Running(progress(first)))
        assertSame(first, held)
        held = heldSnapshot(held, ProcessingState.Running(progress(null)))
        assertSame(first, held, "an emission without a snapshot keeps the last one")
        held = heldSnapshot(held, ProcessingState.Paused(progress(second)))
        assertSame(second, held)
        held = heldSnapshot(held, ProcessingState.Running(progress(second), prepared = 1f, saving = true))
        held = heldSnapshot(held, ProcessingState.Done(File("splat.ply")))
        assertSame(second, held, "Done keeps the finished splat")
        assertNull(heldSnapshot(held, ProcessingState.Running(null)), "a new run starts with no snapshot")
    }

    @Test
    fun `turning keeps the cloud's centre and spins positions and orientations about +Y`() {
        val c = cloud()
        val turned = LivePreview.turned(c, 90f)
        assertSame(c, LivePreview.turned(c, 0f))
        assertSame(c, LivePreview.turned(c, 360f))
        // Mean x and z of the six points: (0.9 / 6, 0.3 / 6).
        val cx = 0.15f
        val cz = 0.05f
        for (i in 0 until c.count) {
            val x = c.positions[3 * i] - cx
            val z = c.positions[3 * i + 2] - cz
            // +90 degrees about +Y (right hand): x' = z, z' = -x.
            assertEquals(cx + z, turned.positions[3 * i], 1e-5f)
            assertEquals(c.positions[3 * i + 1], turned.positions[3 * i + 1], 0f)
            assertEquals(cz - x, turned.positions[3 * i + 2], 1e-5f)
        }
        val q = Quat.fromAxisAngle(Vec3.UNIT_Y, Math.toRadians(90.0).toFloat())
        assertArrayEquals(floatArrayOf(q.w, q.x, q.y, q.z), turned.rotations.copyOfRange(0, 4), 1e-6f)
        assertEquals(0.6f, c.positions[6], 0f, "the input is not changed")
        assertArrayEquals(c.shDc, turned.shDc, 0f)
    }

    @Test
    fun `the preview is the square still from the front, and a turn changes it`() {
        val c = cloud()
        val front = LivePreview.render(c, 0f, background)
        assertEquals(LivePreview.SIZE * LivePreview.SIZE, front.size)
        assertArrayEquals(SplatStill.render(c, LivePreview.SIZE, LivePreview.SIZE, background), front)
        val side = LivePreview.render(c, 90f, background)
        assertFalse(front.contentEquals(side), "a quarter turn draws a different picture")
        assertFalse(side.all { it == background }, "the turned splat is still in view")
    }
}
