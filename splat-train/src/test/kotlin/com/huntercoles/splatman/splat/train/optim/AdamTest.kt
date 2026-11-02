package com.huntercoles.splatman.splat.train.optim

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * [Adam] against three steps worked by hand: p0 = 1, lr = 0.1, betas 0.9 / 0.999, eps = 1e-8, gradients 0.5, -0.25,
 * 1.0. With m_t = 0.9 m + 0.1 g, v_t = 0.999 v + 0.001 g^2, m_hat = m / (1 - 0.9^t), v_hat = v / (1 - 0.999^t),
 * p -= lr * m_hat / (sqrt(v_hat) + eps):
 * - t=1: m = 0.05, v = 0.00025, m_hat = 0.5, v_hat = 0.25, p = 1 - 0.1 * 0.5 / 0.5 = 0.900000002 (the eps);
 * - t=2: m = 0.045 - 0.025 = 0.02, v = 0.00024975 + 0.0000625 = 0.00031225, m_hat = 0.02 / 0.19 = 0.1052632,
 *   v_hat = 0.00031225 / 0.001999 = 0.1562031, p = 0.9 - 0.1 * 0.1052632 / 0.3952254 = 0.8733663;
 * - t=3: m = 0.018 + 0.1 = 0.118, v = 0.00031193775 + 0.001 = 0.00131193775, m_hat = 0.118 / 0.271 = 0.4354244,
 *   v_hat = 0.00131193775 / 0.002997001 = 0.4377502, p = 0.8733663 - 0.1 * 0.4354244 / 0.6616269 = 0.8075551.
 */
class AdamTest {

    @Test
    fun `three steps match the hand-computed update`() {
        val adam = Adam(width = 2, eps = 1e-8)
        // Element 1 has no gradient: it must not move.
        val params = floatArrayOf(1f, 3f)
        val expected = doubleArrayOf(0.900000002, 0.8733663, 0.8075551)
        val expectedM = doubleArrayOf(0.05, 0.02, 0.118)
        val expectedV = doubleArrayOf(0.00025, 0.00031225, 0.00131193775)
        for ((t, g) in listOf(0.5f, -0.25f, 1.0f).withIndex()) {
            adam.step(params, floatArrayOf(g, 0f), count = 1, lr = 0.1)
            assertEquals(t + 1, adam.steps)
            assertEquals(expectedM[t], adam.moment1(0).toDouble(), 1e-7, "m at step ${t + 1}")
            assertEquals(expectedV[t], adam.moment2(0).toDouble(), 1e-9, "v at step ${t + 1}")
            assertEquals(expected[t], params[0].toDouble(), 1e-6, "p at step ${t + 1}")
            assertEquals(3f, params[1])
        }
    }

    @Test
    fun `remap keeps moments of kept splats and zeroes new ones`() {
        val adam = Adam(width = 1, eps = 1e-8)
        val params = floatArrayOf(0f, 0f)
        adam.step(params, floatArrayOf(1f, -2f), count = 2, lr = 0.1)
        adam.remap(intArrayOf(1, -1, 0))
        assertEquals(-0.2f, adam.moment1(0), 1e-7f)
        assertEquals(0f, adam.moment1(1))
        assertEquals(0f, adam.moment2(1))
        assertEquals(0.1f, adam.moment1(2), 1e-7f)
        assertEquals(1, adam.steps)
    }
}
