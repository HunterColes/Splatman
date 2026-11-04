package com.huntercoles.splatman.splat.sfm.nativebridge

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Debug
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * On the e2e emulator (x86_64): `tools/e2e.sh splat-sfm`; on a phone: `./gradlew :splat-sfm:connectedDebugAndroidTest`.
 * Two 640x480 images of a random-noise texture, the second rotated by [ANGLE_DEG] about the centre and shifted by
 * ([SHIFT_X], [SHIFT_Y]) px: physically a camera roll plus a translation parallel to a fronto-parallel plane.
 */
@RunWith(AndroidJUnit4::class)
class NativeSfmTest {

    @Test
    fun orbVerifiesAtLeast200Inliers() {
        val inliers = run(NativeSfm.KIND_ORB, noRotation)
        assertTrue("ORB inliers ${inliers[0]}", inliers[0] >= MIN_INLIERS)
    }

    @Test
    fun akazeVerifiesAtLeast200Inliers() {
        val inliers = run(NativeSfm.KIND_AKAZE, noRotation)
        assertTrue("AKAZE inliers ${inliers[0]}", inliers[0] >= MIN_INLIERS)
    }

    @Test
    fun theTrueGyroRotationKeepsTheMatches() {
        val inliers = run(NativeSfm.KIND_ORB, rollQuat(ANGLE_DEG))
        assertTrue("inliers with the true rotation ${inliers[0]}", inliers[0] >= MIN_INLIERS)
    }

    @Test
    fun aWrongGyroRotationDropsTheMatchesBeforeRansac() {
        // A 20 degree tilt about x: K R K^-1 sends every point about f tan(20) = 180 px off, far past the 32 px gate.
        val tilt = Math.toRadians(20.0) / 2
        val inliers = run(NativeSfm.KIND_ORB, floatArrayOf(cos(tilt).toFloat(), sin(tilt).toFloat(), 0f, 0f))
        assertTrue("inliers with a wrong rotation ${inliers[0]}", inliers[0] < 10)
    }

    @Test
    fun downscaledToTheLongSideStillVerifies() {
        val inliers = run(NativeSfm.KIND_ORB, noRotation, longSide = 320)
        assertTrue("inliers at 320 px ${inliers[0]}", inliers[0] >= 100)
    }

    @Test
    fun matchesBothWaysAndReportsStageTimes() {
        val h = NativeSfm.frontEnd(paths, intrinsics, intArrayOf(0, 1, 1, 0), noRotation + noRotation, NativeSfm.KIND_ORB, 2000, 640)
        try {
            val stats = NativeSfm.matchStats(h)
            assertEquals(2, stats.size)
            assertTrue("both directions ${stats.toList()}", stats.all { it >= MIN_INLIERS })
            val ms = NativeSfm.stageMs(h)
            assertEquals(3, ms.size)
            assertTrue("stage times ${ms.toList()}", ms.all { it >= 0 })
        } finally {
            NativeSfm.release(h)
        }
    }

    @Test
    fun noPairsGivesEmptyStats() {
        val h = NativeSfm.frontEnd(paths, intrinsics, IntArray(0), FloatArray(0), NativeSfm.KIND_AKAZE, 500, 640)
        try {
            assertEquals(0, NativeSfm.matchStats(h).size)
        } finally {
            NativeSfm.release(h)
        }
    }

    @Test
    fun badInputThrowsWithTheReason() {
        val pair = intArrayOf(0, 1)
        fun fails(reason: String, block: () -> Unit) {
            val e = assertThrows(NativeSfmException::class.java) { block() }
            assertTrue("'${e.message}' should contain '$reason'", e.message!!.contains(reason))
        }
        val missing = File(dir, "missing.png").path
        fails("cannot read") { NativeSfm.frontEnd(arrayOf(paths[0], missing), intrinsics, pair, noRotation, 0, 500, 640) }
        val wrongSize = intrinsics.copyOf().also { it[10] = 800f }
        fails("intrinsics say 800x480") { NativeSfm.frontEnd(paths, wrongSize, pair, noRotation, 0, 500, 640) }
        fails("intrinsics has 11 floats") { NativeSfm.frontEnd(paths, intrinsics.copyOf(11), pair, noRotation, 0, 500, 640) }
        fails("pairs has 3 ints") { NativeSfm.frontEnd(paths, intrinsics, intArrayOf(0, 1, 1), noRotation, 0, 500, 640) }
        fails("pairRotations has 3 floats") { NativeSfm.frontEnd(paths, intrinsics, pair, noRotation.copyOf(3), 0, 500, 640) }
        fails("pairRotations has 4 floats") { NativeSfm.frontEnd(paths, intrinsics, IntArray(0), noRotation, 0, 500, 640) }
        fails("not two different frames") { NativeSfm.frontEnd(paths, intrinsics, intArrayOf(0, 2), noRotation, 0, 500, 640) }
        fails("not two different frames") { NativeSfm.frontEnd(paths, intrinsics, intArrayOf(-1, 1), noRotation, 0, 500, 640) }
        fails("not two different frames") { NativeSfm.frontEnd(paths, intrinsics, intArrayOf(1, 1), noRotation, 0, 500, 640) }
        val halfNan = floatArrayOf(1f, Float.NaN, 0f, 0f)
        fails("unit quaternion") { NativeSfm.frontEnd(paths, intrinsics, pair, halfNan, 0, 500, 640) }
        fails("unit quaternion") { NativeSfm.frontEnd(paths, intrinsics, pair, floatArrayOf(2f, 0f, 0f, 0f), 0, 500, 640) }
        fails("unknown feature kind 7") { NativeSfm.frontEnd(paths, intrinsics, pair, noRotation, 7, 500, 640) }
        fails("maxFeatures must be > 0") { NativeSfm.frontEnd(paths, intrinsics, pair, noRotation, 0, 0, 640) }
        fails("longSide must be > 0") { NativeSfm.frontEnd(paths, intrinsics, pair, noRotation, 0, 500, 0) }
        fails("no frames") { NativeSfm.frontEnd(emptyArray(), FloatArray(0), IntArray(0), FloatArray(0), 0, 500, 640) }
        val fractional = intrinsics.copyOf().also { it[4] = 640.5f }
        fails("whole number of pixels") { NativeSfm.frontEnd(paths, fractional, pair, noRotation, 0, 500, 640) }
        val negativeFocal = intrinsics.copyOf().also { it[0] = -1f }
        fails("bad intrinsics") { NativeSfm.frontEnd(paths, negativeFocal, pair, noRotation, 0, 500, 640) }
    }

    @Test
    fun aReleasedOrUnknownHandleThrows() {
        val h = NativeSfm.frontEnd(paths, intrinsics, intArrayOf(0, 1), noRotation, NativeSfm.KIND_ORB, 500, 640)
        NativeSfm.release(h)
        assertThrows(NativeSfmException::class.java) { NativeSfm.matchStats(h) }
        assertThrows(NativeSfmException::class.java) { NativeSfm.stageMs(h) }
        assertThrows(NativeSfmException::class.java) { NativeSfm.release(h) }
        assertThrows(NativeSfmException::class.java) { NativeSfm.matchStats(0L) }
    }

    @Test
    fun releaseFreesEverything() {
        repeat(WARMUP) { NativeSfm.release(NativeSfm.frontEnd(paths, intrinsics, intArrayOf(0, 1), noRotation, 0, 2000, 640)) }
        System.gc()
        val before = Debug.getNativeHeapAllocatedSize()
        repeat(LOOPS) {
            val h = NativeSfm.frontEnd(paths, intrinsics, intArrayOf(0, 1), noRotation, NativeSfm.KIND_ORB, 2000, 640)
            NativeSfm.matchStats(h)
            NativeSfm.release(h)
        }
        System.gc()
        val grown = Debug.getNativeHeapAllocatedSize() - before
        // One unreleased result (2 x 2000 keypoints and descriptors, the matches) is about 250 kB; LOOPS of them
        // would add several MB.
        assertTrue("native heap grew by $grown bytes over $LOOPS front ends", grown < MAX_GROWTH_BYTES)
    }

    private fun run(kind: Int, rotation: FloatArray, longSide: Int = 640): IntArray {
        val h = NativeSfm.frontEnd(paths, intrinsics, intArrayOf(0, 1), rotation, kind, 2000, longSide)
        try {
            return NativeSfm.matchStats(h)
        } finally {
            NativeSfm.release(h)
        }
    }

    companion object {
        private const val W = 640
        private const val H = 480
        private const val F = 500f
        private const val ANGLE_DEG = 8.0
        private const val SHIFT_X = 24f
        private const val SHIFT_Y = -16f
        private const val MIN_INLIERS = 200
        private const val WARMUP = 3
        private const val LOOPS = 30
        private const val MAX_GROWTH_BYTES = 2L * 1024 * 1024

        private val noRotation = FloatArray(4) { Float.NaN }
        private val intrinsics = floatArrayOf(F, F, W / 2f, H / 2f, W.toFloat(), H.toFloat()).let { it + it }
        private lateinit var dir: File
        private lateinit var paths: Array<String>

        /** R_Cj_Ci for an image rotated by [deg] (clockwise on screen, y down): a roll about the optical axis. */
        private fun rollQuat(deg: Double): FloatArray {
            val half = Math.toRadians(deg) / 2
            return floatArrayOf(cos(half).toFloat(), 0f, 0f, sin(half).toFloat())
        }

        @BeforeClass
        @JvmStatic
        fun writeImages() {
            dir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "native-sfm-test").apply { mkdirs() }
            // Noise on a quarter-resolution grid, smoothly upscaled, on a source larger than both views.
            val rnd = Random(7)
            val coarse = Bitmap.createBitmap((W + 2 * MARGIN) / 4, (H + 2 * MARGIN) / 4, Bitmap.Config.ARGB_8888)
            for (y in 0 until coarse.height) {
                for (x in 0 until coarse.width) {
                    val v = rnd.nextInt(256)
                    coarse.setPixel(x, y, Color.rgb(v, v, v))
                }
            }
            val source = Bitmap.createScaledBitmap(coarse, W + 2 * MARGIN, H + 2 * MARGIN, true)
            val first = Matrix().apply { setTranslate(-MARGIN.toFloat(), -MARGIN.toFloat()) }
            val second = Matrix(first).apply {
                postRotate(ANGLE_DEG.toFloat(), W / 2f, H / 2f)
                postTranslate(SHIFT_X, SHIFT_Y)
            }
            paths = arrayOf(write(source, first, "a.png"), write(source, second, "b.png"))
        }

        private const val MARGIN = 160

        private fun write(source: Bitmap, m: Matrix, name: String): String {
            val out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
            Canvas(out).drawBitmap(source, m, Paint(Paint.FILTER_BITMAP_FLAG))
            val file = File(dir, name)
            file.outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
            return file.path
        }
    }
}
