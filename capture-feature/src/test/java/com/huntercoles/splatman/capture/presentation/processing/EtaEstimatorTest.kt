package com.huntercoles.splatman.capture.presentation.processing

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EtaEstimatorTest {

    /** A steady run: 1 % per second from t = 0 (100 s in all). */
    private fun steady(eta: EtaEstimator, fromS: Int, toS: Int): List<Eta> =
        (fromS..toS).map { s -> eta.update(s / 100f, s * 1000L) }

    @Test
    fun `hidden at or under 10 percent however stable`() {
        val eta = EtaEstimator()
        val seen = steady(eta, 0, 10)
        assertTrue(seen.all { it == Eta.Unknown }, "$seen")
    }

    @Test
    fun `hidden until the estimate has been stable for 10 s, then shown`() {
        val eta = EtaEstimator()
        // Erratic start: the predicted finish jumps far each second.
        assertEquals(Eta.Unknown, eta.update(0.0f, 0))
        assertEquals(Eta.Unknown, eta.update(0.11f, 1_000)) // finish ~9 s
        assertEquals(Eta.Unknown, eta.update(0.12f, 20_000)) // finish ~167 s: re-anchored
        // Steady from here at the same rate (0.12 / 20 s): finish stays at ~167 s.
        val rate = 0.12f / 20_000f
        var shownAt = -1L
        for (t in 21_000L..40_000L step 1_000L) {
            if (eta.update(rate * t, t) != Eta.Unknown && shownAt < 0) shownAt = t
        }
        assertEquals(30_000L, shownAt)
    }

    @Test
    fun `rounds to whole minutes, one minute below 90 s, almost done below 15 s`() {
        assertEquals(Eta.AlmostDone, EtaEstimator.display(0))
        assertEquals(Eta.AlmostDone, EtaEstimator.display(14))
        assertEquals(Eta.Minutes(1), EtaEstimator.display(15))
        assertEquals(Eta.Minutes(1), EtaEstimator.display(89))
        assertEquals(Eta.Minutes(2), EtaEstimator.display(90))
        assertEquals(Eta.Minutes(2), EtaEstimator.display(149))
        assertEquals(Eta.Minutes(3), EtaEstimator.display(150))
        assertEquals(Eta.Minutes(10), EtaEstimator.display(600))
    }

    @Test
    fun `a steady run shows its minutes once stable`() {
        val eta = EtaEstimator()
        // 0.1 % per second: 1000 s in all.
        var last: Eta = Eta.Unknown
        for (s in 0..120) last = eta.update(s / 1000f, s * 1000L)
        // At 120 s: 880 s left -> 15 min.
        assertEquals(Eta.Minutes(15), last)
    }

    @Test
    fun `never counts up when the run slows down`() {
        val eta = EtaEstimator()
        val shown = mutableListOf<Int>()
        fun minutes(e: Eta) = when (e) {
            is Eta.Minutes -> e.minutes
            Eta.AlmostDone -> 0
            Eta.Unknown -> null
        }
        var f = 0f
        for (s in 0..400) {
            // 0.2 %/s for 100 s, then a quarter of that: the raw estimate jumps up.
            f += if (s < 100) 0.002f else 0.0005f
            minutes(eta.update(f, s * 1000L))?.let { shown += it }
        }
        assertTrue(shown.isNotEmpty())
        assertTrue(shown.zipWithNext().all { (a, b) -> b <= a }, "$shown")
    }

    @Test
    fun `hot phone hides it until stable for 10 s again`() {
        val eta = EtaEstimator()
        for (s in 0..30) eta.update(s / 100f, s * 1000L)
        assertTrue(eta.update(0.31f, 31_000) is Eta.Minutes)
        assertEquals(Eta.Unknown, eta.update(0.32f, 32_000, hot = true))
        for (s in 33..42) assertEquals(Eta.Unknown, eta.update(s / 100f, s * 1000L), "at $s")
        assertTrue(eta.update(0.43f, 43_000) is Eta.Minutes)
    }
}
