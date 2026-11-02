package com.huntercoles.splatman.viewer.rendering.gl

import com.huntercoles.splatman.splat.math.Mat4
import com.huntercoles.splatman.splat.math.Vec3
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class SplatSortWorkerTest {

    private val scheduler = TestCoroutineScheduler()
    private val scope = CoroutineScope(StandardTestDispatcher(scheduler))

    // Four centres on the z axis: z = 1, -3, 0, 2.
    private val positions = floatArrayOf(0f, 0f, 1f, 0f, 0f, -3f, 0f, 0f, 0f, 0f, 0f, 2f)
    private val fromFront = Mat4.lookAt(Vec3(0f, 0f, 10f), Vec3.ZERO, Vec3.UNIT_Y).values
    private val fromBack = Mat4.lookAt(Vec3(0f, 0f, -10f), Vec3.ZERO, Vec3.UNIT_Y).values

    @Test
    fun `sorts the newest view back to front off the caller`() {
        val w = SplatSortWorker(positions, 4, 3f, scope)
        w.requestSort(fromFront)
        assertNull(w.takeOrder(), "nothing until the worker runs")
        scheduler.advanceUntilIdle()
        assertArrayEquals(intArrayOf(1, 2, 0, 3), w.takeOrder())
        assertNull(w.takeOrder(), "an order is taken once")
        w.close()
    }

    @Test
    fun `views offered while the worker is busy collapse to the newest`() {
        val w = SplatSortWorker(positions, 4, 3f, scope)
        w.requestSort(fromFront)
        w.requestSort(fromBack)
        scheduler.advanceUntilIdle()
        assertArrayEquals(intArrayOf(3, 0, 2, 1), w.takeOrder())
        assertEquals(1, w.sorts)
        w.close()
    }

    @Test
    fun `a recycled array is reused for the next order`() {
        val w = SplatSortWorker(positions, 4, 3f, scope)
        w.requestSort(fromFront)
        scheduler.advanceUntilIdle()
        val first = w.takeOrder()!!
        w.recycle(first)
        w.requestSort(fromBack)
        scheduler.advanceUntilIdle()
        val second = w.takeOrder()
        assertSame(first, second)
        assertArrayEquals(intArrayOf(3, 0, 2, 1), second)
        w.close()
    }

    @Test
    fun `an unchanged view is skipped until invalidated`() {
        val w = SplatSortWorker(positions, 4, 3f, scope)
        w.requestSort(fromFront)
        scheduler.advanceUntilIdle()
        w.takeOrder()
        w.requestSort(fromFront)
        scheduler.advanceUntilIdle()
        assertNull(w.takeOrder())
        w.invalidate()
        w.requestSort(fromFront)
        scheduler.advanceUntilIdle()
        assertArrayEquals(intArrayOf(1, 2, 0, 3), w.takeOrder())
        assertEquals(2, w.sorts)
        w.close()
    }

    @Test
    fun `a closed worker sorts nothing`() {
        val w = SplatSortWorker(positions, 4, 3f, scope)
        w.close()
        w.requestSort(fromFront)
        scheduler.advanceUntilIdle()
        assertNull(w.takeOrder())
    }
}
