package com.huntercoles.splatman.splat.sfm

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.lang.management.ManagementFactory
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * #227: `BundleAdjuster.adjust(model, frames, iterations, isCancelled): BaResult` refines every registered camera and
 * every point of a [SparseModel] (LM, Huber 2 px, Schur on the points, dense Cholesky on the cameras), OpenCV axes in
 * and out (ADR 0003). `frames` is index-aligned with the model's frames and gives each frame's fixed intrinsics.
 *
 * `BaResult(model, meanBeforePx, medianBeforePx, meanAfterPx, medianAfterPx, iterations)`: "before" over every input
 * observation, "after" over the returned model's (kept) observations, mean and median of the pixel distance.
 * The scenes are built by [baScene] (BaScene.kt).
 */
class BundleAdjusterTest {

    private val cap = 50

    private fun adjust(scene: BaScene, iterations: Int = cap, isCancelled: () -> Boolean = { false }): BaResult =
        BundleAdjuster.adjust(scene.model, scene.frames, iterations, isCancelled)

    private fun exactScene() = baScene(noisePx = 0.0, rotDeg = 0.0, transFrac = 0.0, pointFrac = 0.0)

    // --- A1: a new model plus the numbers ---

    @Test
    fun `A1 returns a new model and leaves the input untouched`() {
        val scene = baScene()
        val m = scene.model
        val registered = m.registered.copyOf()
        val rot = m.rotationsCcvW.copyOf()
        val trans = m.translationsCcvW.copyOf()
        val pts = m.points.copyOf()
        val colours = m.colours.copyOf()
        val obsFrame = m.obsFrame.copyOf()
        val obsPoint = m.obsPoint.copyOf()
        val uv = m.obsUv.copyOf()

        val r = adjust(scene)

        assertNotSame(m, r.model)
        assertNotSame(m.points, r.model.points)
        assertNotSame(m.rotationsCcvW, r.model.rotationsCcvW)
        assertNotSame(m.translationsCcvW, r.model.translationsCcvW)
        assertArrayEquals(registered, m.registered)
        assertArrayEquals(rot, m.rotationsCcvW)
        assertArrayEquals(trans, m.translationsCcvW)
        assertArrayEquals(pts, m.points)
        assertArrayEquals(colours, m.colours)
        assertArrayEquals(obsFrame, m.obsFrame)
        assertArrayEquals(obsPoint, m.obsPoint)
        assertArrayEquals(uv, m.obsUv)
    }

    @Test
    fun `A1 reports mean and median before and after and the iterations run`() {
        val scene = baScene()
        val before = baErrors(scene.model, scene.frames)

        val r = adjust(scene)

        val after = baErrors(r.model, scene.frames)
        assertEquals(baMean(before), r.meanBeforePx.toDouble(), 1e-4 * baMean(before))
        assertEquals(baMedian(before), r.medianBeforePx.toDouble(), 1e-4 * baMedian(before))
        assertEquals(baMean(after), r.meanAfterPx.toDouble(), 0.01)
        assertEquals(baMedian(after), r.medianAfterPx.toDouble(), 0.01)
        assertTrue(r.medianBeforePx.toDouble() > 5.0, "the perturbed start reads ${r.medianBeforePx} px")
        assertTrue(r.medianAfterPx.toDouble() < r.medianBeforePx.toDouble())
        assertTrue(r.iterations in 1..cap, "iterations ${r.iterations}")
    }

    // --- A2: pinhole with each frame's intrinsics, gauge ---

    @Test
    fun `A2 each frame's own intrinsics are used`() {
        // Even frames 500 px focal, odd frames 620/615 px with another principal point: one shared K reads tens of px.
        val scene = exactScene()

        val r = adjust(scene)

        assertTrue(r.meanBeforePx.toDouble() < 1e-3, "exact scene reads ${r.meanBeforePx} px before")
        assertTrue(r.meanAfterPx.toDouble() < 1e-3, "exact scene reads ${r.meanAfterPx} px after")
    }

    @Test
    fun `A2 gauge holds the first registered camera and its distance to the second`() {
        // Frames 0 and 7 unregistered with zero-filled slots: frame 1 is the first registered, frame 2 the second.
        val scene = baScene(unregistered = setOf(0, 7))
        val m = scene.model
        val first = scene.firstRegistered
        val second = scene.secondRegistered
        assertEquals(1, first)
        assertEquals(2, second)
        val dBefore = dist(baModelCentre(m, first), baModelCentre(m, second))

        val r = adjust(scene)

        val out = r.model
        assertArrayEquals(m.registered, out.registered)
        assertTrue(baRotationAngle(baRotation(m, first), baRotation(out, first)) < 1e-6)
        for (k in 0..2) assertEquals(m.translationsCcvW[3 * first + k], out.translationsCcvW[3 * first + k], 1e-6f)
        assertEquals(dBefore, dist(baModelCentre(out, first), baModelCentre(out, second)), 1e-5 * dBefore)
        assertTrue(baRotationAngle(baRotation(m, second), baRotation(out, second)) > 1e-3, "the second camera moved")
        for (f in listOf(0, 7)) {
            for (k in 0..3) assertEquals(0f, out.rotationsCcvW[4 * f + k], "unregistered frame $f rotation")
            for (k in 0..2) assertEquals(0f, out.translationsCcvW[3 * f + k], "unregistered frame $f translation")
        }
        assertTrue(r.medianAfterPx.toDouble() < 0.6, "median ${r.medianAfterPx} px")
    }

    // --- A3: double precision inside, stopping rules ---

    @Test
    fun `A3 noiseless perturbed start converges below 1e-3 px`() {
        val scene = baScene(noisePx = 0.0)

        val r = adjust(scene)

        assertTrue(r.medianAfterPx.toDouble() < 1e-3, "median ${r.medianAfterPx} px")
        assertTrue(baCentreErrorAfterSimilarity(r.model, scene) < 1e-4)
    }

    @Test
    fun `A3 stops before a high cap once the relative cost change is small`() {
        val r = adjust(baScene(), iterations = 200)

        assertTrue(r.iterations in 1 until 200, "iterations ${r.iterations}")
    }

    @Test
    fun `A3 the iteration cap bounds the iterations run`() {
        val scene = baScene()

        val r = adjust(scene, iterations = 2)

        assertTrue(r.iterations in 1..2, "iterations ${r.iterations}")
        assertTrue(r.medianAfterPx.toDouble() < r.medianBeforePx.toDouble())
    }

    @Test
    fun `A3 cancelled from the start runs no iteration`() {
        val scene = baScene()

        val r = adjust(scene) { true }

        assertEquals(0, r.iterations)
        assertEquals(scene.model.frameCount, r.model.frameCount)
    }

    @Test
    fun `A3 cancel mid-run stops at the next check`() {
        val scene = baScene()
        var calls = 0

        val r = adjust(scene) { ++calls > 3 }

        assertTrue(calls >= 4, "isCancelled polled $calls times")
        assertTrue(r.iterations <= 3, "iterations ${r.iterations}")
    }

    // --- A4: drop observations over 4 px, points under 2 observations ---

    @Test
    fun `A4 observations over 4 px are dropped and points left under 2 observations removed`() {
        val scene = baScene(grossFrac = 0.1, pairPoints = 20)
        val m = scene.model
        val grossKeys = HashSet<Long>()
        for (o in 0 until m.observationCount) {
            if (scene.gross[o]) grossKeys += key(m.obsFrame[o], baColourId(m, m.obsPoint[o]))
        }

        val r = adjust(scene)

        val out = r.model
        val errors = baErrors(out, scene.frames)
        assertTrue(errors.all { it <= 4.0 + 1e-3 }, "worst kept error ${errors.max()} px")
        val perPoint = IntArray(out.pointCount)
        for (o in 0 until out.observationCount) perPoint[out.obsPoint[o]]++
        assertTrue(perPoint.all { it >= 2 }, "a point kept with ${perPoint.min()} observations")
        val ids = (0 until out.pointCount).map { baColourId(out, it) }
        assertTrue(ids.none { it >= scene.pairPointsFrom }, "a two-observation point with a gross one survived")
        assertTrue(out.pointCount < m.pointCount)
        assertTrue(out.pointCount >= scene.pairPointsFrom * 95 / 100, "kept ${out.pointCount} points")
        for (o in 0 until out.observationCount) {
            assertFalse(key(out.obsFrame[o], baColourId(out, out.obsPoint[o])) in grossKeys, "gross observation $o kept")
        }
        assertTrue(out.observationCount >= (m.observationCount - grossKeys.size) * 95 / 100)
    }

    @Test
    fun `A4 SfmStats-ready numbers come out`() {
        val scene = baScene(grossFrac = 0.1)

        val r = adjust(scene)

        val after = baErrors(r.model, scene.frames)
        val stats = SfmStats(
            totalFrames = r.model.frameCount,
            registeredFrames = r.model.registered.count { it },
            pointCount = r.model.pointCount,
            meanReprojPx = r.meanAfterPx.toFloat(),
            medianReprojPx = r.medianAfterPx.toFloat(),
            stageMs = emptyMap(),
        )
        assertEquals(baMean(after), stats.meanReprojPx.toDouble(), 0.01)
        assertEquals(baMedian(after), stats.medianReprojPx.toDouble(), 0.01)
        assertEquals(20, stats.registeredFrames)
    }

    // --- A5: accuracy, outliers, scale ---

    @Test
    fun `A5 20 cameras 500 points 0_5 px noise converge under 0_6 px median and 1 percent centres`() {
        val scene = baScene()

        val r = adjust(scene)

        assertTrue(r.medianAfterPx.toDouble() < 0.6, "median ${r.medianAfterPx} px")
        val centreError = baCentreErrorAfterSimilarity(r.model, scene)
        assertTrue(centreError < 0.01, "camera centres off by ${centreError * 100} %")
    }

    @Test
    fun `A5 10 percent gross outliers stay within the same bounds`() {
        val scene = baScene(grossFrac = 0.1, seed = 2270L)

        val r = adjust(scene)

        assertTrue(r.medianAfterPx.toDouble() < 0.6, "median ${r.medianAfterPx} px")
        val centreError = baCentreErrorAfterSimilarity(r.model, scene)
        assertTrue(centreError < 0.01, "camera centres off by ${centreError * 100} %")
    }

    @Test
    fun `A5 times 150 cameras and 20000 points on the JVM`() {
        // Each point seen by 6 consecutive cameras of the ring: about 120k observations. Printed, no time assertion.
        val scene = baScene(cameras = 150, points = 20_000, window = 6, seed = 150L)

        val t0 = System.nanoTime()
        val r = adjust(scene, iterations = 10)
        val ms = (System.nanoTime() - t0) / 1_000_000

        println(
            "BundleAdjuster #227: 150 cameras, 20000 points, ${scene.model.observationCount} observations: $ms ms, " +
                "${r.iterations} iterations, median ${r.medianBeforePx} -> ${r.medianAfterPx} px",
        )
        assertTrue(r.iterations > 0)
    }

    // --- Hardening ---

    @Test
    fun `H1 no Android imports in the bundle adjuster`() {
        val dir = File("src/main/kotlin/com/huntercoles/splatman/splat/sfm")
        val file = File(dir, "BundleAdjuster.kt")
        assertTrue(file.isFile, "missing ${file.path}")
        val sources = dir.listFiles { f -> f.extension == "kt" }!!.filter { "BundleAdjuster" in it.readText() }
        for (f in sources) {
            val androidImport = Regex("""^\s*import\s+(android|androidx)\.""", RegexOption.MULTILINE)
            assertFalse(androidImport.containsMatchIn(f.readText()), f.path)
        }
    }

    @Test
    fun `H2 no allocation per observation per iteration`() {
        // Heap bytes this thread allocates per extra iteration, per observation, stay under 8: a single object per
        // observation per iteration is already 16. Camera- or point-sized scratch per iteration fits in that budget.
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        assertTrue(bean.isThreadAllocatedMemorySupported)
        bean.isThreadAllocatedMemoryEnabled = true
        val scene = baScene(points = 2000, seed = 22L)
        val obs = scene.model.observationCount
        adjust(scene, iterations = 3) // warm up the JIT

        val a0 = bean.currentThreadAllocatedBytes
        val short = adjust(scene, iterations = 2)
        val a1 = bean.currentThreadAllocatedBytes
        val long = adjust(scene, iterations = 12)
        val a2 = bean.currentThreadAllocatedBytes

        val extraIterations = long.iterations - short.iterations
        assertTrue(extraIterations >= 2, "iterations ${short.iterations} and ${long.iterations}")
        val perObsPerIteration = ((a2 - a1) - (a1 - a0)).toDouble() / extraIterations / obs
        assertTrue(perObsPerIteration < 8.0, "$perObsPerIteration bytes per observation per iteration")
    }

    @Test
    fun `H3 exact OpenCV scene reads zero error and stays put`() {
        // No axis flips: the scene is OpenCV world-to-camera (x right, y down, z forward), quaternions w x y z.
        val scene = exactScene()
        val m = scene.model

        val r = adjust(scene)

        val out = r.model
        assertTrue(r.medianBeforePx.toDouble() < 1e-3, "exact scene reads ${r.medianBeforePx} px")
        assertEquals(m.pointCount, out.pointCount)
        assertEquals(m.observationCount, out.observationCount)
        for (f in 0 until m.frameCount) {
            assertTrue(baRotationAngle(baRotation(m, f), baRotation(out, f)) < 1e-5, "frame $f rotation")
            val qn = sqrt((0..3).sumOf { out.rotationsCcvW[4 * f + it].toDouble().let { v -> v * v } })
            assertEquals(1.0, qn, 1e-5, "frame $f quaternion norm")
            for (k in 0..2) assertEquals(m.translationsCcvW[3 * f + k], out.translationsCcvW[3 * f + k], 1e-4f)
        }
        val byId = (0 until out.pointCount).associateBy { baColourId(out, it) }
        for (p in 0 until m.pointCount) {
            val q = byId.getValue(baColourId(m, p))
            for (k in 0..2) assertTrue(abs(m.points[3 * p + k] - out.points[3 * q + k]) < 1e-4f, "point $p")
        }
    }

    private fun dist(a: DoubleArray, b: DoubleArray) =
        sqrt((a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1]) + (a[2] - b[2]) * (a[2] - b[2]))

    private fun key(frame: Int, id: Int): Long = (frame.toLong() shl 32) or id.toLong()
}
