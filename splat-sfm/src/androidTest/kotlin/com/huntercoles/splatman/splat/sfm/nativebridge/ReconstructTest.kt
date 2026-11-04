package com.huntercoles.splatman.splat.sfm.nativebridge

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.huntercoles.splatman.splat.io.ply.PlyReader
import com.huntercoles.splatman.splat.math.Quat
import com.huntercoles.splatman.splat.math.Vec3
import com.huntercoles.splatman.splat.model.CameraIntrinsics
import com.huntercoles.splatman.splat.model.RgbImage
import com.huntercoles.splatman.splat.model.SplatCloud
import com.huntercoles.splatman.splat.sfm.ImuPriors
import com.huntercoles.splatman.splat.sfm.SfmFrame
import com.huntercoles.splatman.splat.sfm.SfmOptions
import com.huntercoles.splatman.splat.sfm.SparseModel
import com.huntercoles.splatman.splat.train.synthetic.ScanPath
import com.huntercoles.splatman.splat.train.synthetic.SyntheticScan
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The incremental reconstruction (#226) on the synthetic orbit (#224): [FRAMES] views at [W]x[H] of the cactus fixture
 * on a textured table ([withBackdrop]) around one full turn, pairs from [ImuPriors.pairs] (window 8, no gyro rotation),
 * the default ORB front end.
 * On the e2e emulator: `tools/e2e.sh splat-sfm`. The figures go to the log and to build/e2e/reconstruct.txt, the first
 * view to build/e2e/reconstruct-frame00.png.
 */
@RunWith(AndroidJUnit4::class)
class ReconstructTest {

    @Test
    fun theOrbitRegistersAtLeast55FramesUnder1Point5PxMeanReprojection() {
        val registered = model.registered.count { it }
        val mean = meanReprojPx(model)
        val ms = NativeSfm.stageMs(handle)
        val line = "registered $registered/$FRAMES, points ${model.pointCount}, observations " +
            "${model.observationCount}, mean reprojection ${"%.3f".format(mean)} px, INIT ${ms[NativeSfm.STAGE_INIT]} ms, " +
            "REGISTER ${ms[NativeSfm.STAGE_REGISTER]} ms, stageMs ${ms.toList()}, pair inliers min/median/max " +
            NativeSfm.matchStats(handle).sorted().let { "${it.first()}/${it[it.size / 2]}/${it.last()} over ${it.size} pairs" }
        report(line + ", median step rotation error ${"%.2f".format(stepRotationErrorsDeg().let { it[it.size / 2] })} deg")
        assertTrue(line, registered >= MIN_REGISTERED)
        assertTrue(line, mean < MAX_MEAN_REPROJ_PX)
        assertEquals(5, ms.size)
        assertTrue(line, ms.all { it >= 0 })
    }

    @Test
    fun posesStayInOpenCvAxesWorldToCamera() {
        // Frame-to-frame rotations against the truth's R_Ccv_W (6 degrees a step): a camera-to-world pose or a flip to
        // OpenGL axes turns each step the wrong way, about 12 degrees off; drift without bundle adjustment adds ~1.
        val errors = stepRotationErrorsDeg()
        assertTrue("median step rotation error ${errors[errors.size / 2]} deg", errors[errors.size / 2] < MAX_MEDIAN_STEP_ROT_DEG)
        // Every observation is in front of its camera.
        for (k in 0 until model.observationCount) {
            val c = toCamera(model, model.obsFrame[k], model.obsPoint[k])
            assertTrue("observation $k has depth ${c.z}", c.z > 0f)
        }
    }

    @Test
    fun noPointIsObservedTwiceInOneFrameAndEveryPointTwice() {
        val seen = HashSet<Long>()
        val perPoint = IntArray(model.pointCount)
        for (k in 0 until model.observationCount) {
            val key = model.obsPoint[k].toLong() * FRAMES + model.obsFrame[k]
            assertTrue("point ${model.obsPoint[k]} twice in frame ${model.obsFrame[k]}", seen.add(key))
            perPoint[model.obsPoint[k]]++
        }
        assertTrue("a point with fewer than 2 observations", perPoint.all { it >= 2 })
    }

    @Test
    fun coloursAreThePixelInTheFirstObservingFrame() {
        val first = IntArray(model.pointCount) { -1 }
        val firstObs = IntArray(model.pointCount) { -1 }
        for (k in 0 until model.observationCount) {
            val p = model.obsPoint[k]
            if (first[p] < 0 || model.obsFrame[k] < first[p]) {
                first[p] = model.obsFrame[k]
                firstObs[p] = k
            }
        }
        val bitmaps = HashMap<Int, Bitmap>()
        var checked = 0
        for (p in 0 until model.pointCount step COLOUR_STRIDE) {
            val k = firstObs[p]
            val bmp = bitmaps.getOrPut(first[p]) { BitmapFactory.decodeFile(paths[first[p]]) }
            val x = Math.round(model.obsUv[2 * k]).coerceIn(0, W - 1)
            val y = Math.round(model.obsUv[2 * k + 1]).coerceIn(0, H - 1)
            val px = bmp.getPixel(x, y)
            val rgb = Triple((px shr 16) and 0xFF, (px shr 8) and 0xFF, px and 0xFF)
            val got = Triple(model.colours[3 * p].toInt() and 0xFF, model.colours[3 * p + 1].toInt() and 0xFF, model.colours[3 * p + 2].toInt() and 0xFF)
            assertEquals("colour of point $p at ($x, $y) in frame ${first[p]}", rgb, got)
            checked++
        }
        assertTrue("checked $checked colours", checked > 0)
    }

    @Test
    fun capsAreReportedNotCrashed() {
        val capped = NativeSfm.reconstruction(handle, maxPoints = 100, maxObservations = 150)
        assertTrue(capped.pointsCapped)
        assertTrue(capped.observationsCapped)
        assertTrue("${capped.model.pointCount} points", capped.model.pointCount <= 100)
        assertEquals(150, capped.model.observationCount)
        val full = NativeSfm.reconstruction(handle)
        assertFalse(full.pointsCapped)
        assertFalse(full.observationsCapped)
        val e1 = assertThrows(NativeSfmException::class.java) { NativeSfm.reconstruction(handle, maxPoints = NativeSfm.MAX_POINTS + 1) }
        assertTrue(e1.message, e1.message!!.contains("maxPoints must be in [1, 50000]"))
        val e2 = assertThrows(NativeSfmException::class.java) { NativeSfm.reconstruction(handle, maxObservations = 0) }
        assertTrue(e2.message, e2.message!!.contains("maxObservations must be in [1, 1000000]"))
    }

    @Test
    fun noPairsRegistersNothing() {
        val h = NativeSfm.frontEnd(paths.sliceArray(0 until 3), intrinsicsArray.copyOf(18), IntArray(0), FloatArray(0), NativeSfm.KIND_ORB, 500, 960)
        try {
            val m = NativeSfm.reconstruct(h)
            assertEquals(3, m.frameCount)
            assertTrue(m.registered.none { it })
            assertEquals(0, m.pointCount)
            assertEquals(0, m.observationCount)
        } finally {
            NativeSfm.release(h)
        }
    }

    @Test
    fun aReleasedHandleThrows() {
        val h = NativeSfm.frontEnd(paths.sliceArray(0 until 2), intrinsicsArray.copyOf(12), IntArray(0), FloatArray(0), NativeSfm.KIND_ORB, 500, 960)
        NativeSfm.release(h)
        assertThrows(NativeSfmException::class.java) { NativeSfm.reconstruct(h) }
    }

    companion object {
        private const val TAG = "ReconstructTest"
        private const val FRAMES = 60
        private const val W = 320
        private const val H = 240
        private const val F = 260f
        private const val SEED = 11L
        private const val MIN_REGISTERED = 55
        private const val MAX_MEAN_REPROJ_PX = 1.5
        private const val MAX_MEDIAN_STEP_ROT_DEG = 2.0
        private const val COLOUR_STRIDE = 7
        private const val BACKDROP = 4000
        private const val BACKDROP_SEED = 5L
        private const val SH_C0 = 0.28209479f
        private const val DEVICE_DIR = "/sdcard/Download/splatman-e2e"

        private lateinit var scan: SyntheticScan
        private lateinit var paths: Array<String>
        private lateinit var intrinsicsArray: FloatArray
        private lateinit var model: SparseModel
        private var handle = 0L

        @BeforeClass
        @JvmStatic
        fun reconstructTheOrbit() {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val cactus = instrumentation.context.assets.open("cactus-up.ply").use { PlyReader.read(it) }
            val intrinsics = CameraIntrinsics(F, F, W / 2f, H / 2f, W, H)
            val probe = SyntheticScan.render(cactus, 1, intrinsics, ScanPath.Orbit(), SEED)
            val cloud = withBackdrop(cactus, probe.subjectCentre, probe.orbitRadius)
            scan = SyntheticScan.render(cloud, FRAMES, intrinsics, ScanPath.Orbit(), SEED)
            val dir = File(instrumentation.targetContext.cacheDir, "reconstruct-test").apply { mkdirs() }
            paths = Array(FRAMES) { i -> writePng(scan.frames[i].image, File(dir, "f%02d.png".format(i))) }
            intrinsicsArray = FloatArray(FRAMES * NativeSfm.INTRINSICS_PER_FRAME) { k ->
                when (k % NativeSfm.INTRINSICS_PER_FRAME) {
                    0 -> F
                    1 -> F
                    2 -> W / 2f
                    3 -> H / 2f
                    4 -> W.toFloat()
                    else -> H.toFloat()
                }
            }
            // No gyro rotations: the front end's rotation-only gate (#225) drops every pair of a 6-degree-a-frame orbit,
            // whose translation cancels its rotation; init parallax then removes the two-view rotation instead.
            val options = SfmOptions(useGyroPrior = false)
            val frames = scan.frames.map { SfmFrame(it.index, it.timestampNs, paths[it.index], intrinsics) }
            val pairs = ImuPriors.pairs(frames, scan.gyro, scan.rotationCcvImu, options)
            val idx = IntArray(2 * pairs.size)
            val rot = FloatArray(4 * pairs.size)
            pairs.forEachIndexed { p, pair ->
                idx[2 * p] = pair.i
                idx[2 * p + 1] = pair.j
                val q = pair.rotationCjCi
                rot[4 * p] = q?.w ?: Float.NaN
                rot[4 * p + 1] = q?.x ?: Float.NaN
                rot[4 * p + 2] = q?.y ?: Float.NaN
                rot[4 * p + 3] = q?.z ?: Float.NaN
            }
            handle = NativeSfm.frontEnd(paths, intrinsicsArray, idx, rot, NativeSfm.KIND_ORB, options.maxFeatures, options.longSidePx)
            model = NativeSfm.reconstruct(handle)
        }

        @AfterClass
        @JvmStatic
        fun releaseTheHandle() {
            if (handle != 0L) NativeSfm.release(handle)
        }

        /**
         * [cloud] standing on a textured table: [BACKDROP] opaque, flat, randomly coloured strokes on a disc of radius
         * 0.85 x the orbit radius [r] just under the subject's lowest splats (a real scan has surroundings; the bare
         * subject on black gives few, mostly silhouette, features). Inside the orbit, so every stroke stays well in front
         * of the camera (the CPU rasteriser has no guard band: a splat beside the camera smears over the whole frame),
         * and few enough that the scan's 2..98th percentile subject box, and so its orbit radius, hardly moves.
         */
        private fun withBackdrop(cloud: SplatCloud, centre: Vec3, r: Float): SplatCloud {
            val rnd = Random(BACKDROP_SEED)
            val ys = FloatArray(cloud.count) { cloud.positions[3 * it + 1] }.also { it.sort() }
            val tableY = ys[cloud.count / 50] - 0.02f * r
            val total = cloud.count + BACKDROP
            val pos = cloud.positions.copyOf(3 * total)
            val scales = cloud.logScales.copyOf(3 * total)
            val rots = cloud.rotations.copyOf(4 * total)
            val opacity = cloud.opacityLogits.copyOf(total)
            val dc = cloud.shDc.copyOf(3 * total)
            val rest = cloud.shRest.copyOf(3 * cloud.shRestPerChannel * total)
            for (s in 0 until BACKDROP) {
                val i = cloud.count + s
                val a = rnd.nextDouble() * 2 * Math.PI
                val d = 0.85 * r * sqrt(rnd.nextDouble())
                pos[3 * i] = centre.x + (d * cos(a)).toFloat()
                pos[3 * i + 1] = tableY
                pos[3 * i + 2] = centre.z + (d * sin(a)).toFloat()
                // Long along local x, thin along local y (world up), turned about up by a random angle.
                scales[3 * i] = ln(0.025f * r)
                scales[3 * i + 1] = ln(0.002f * r)
                scales[3 * i + 2] = ln(0.006f * r)
                val half = rnd.nextDouble() * Math.PI
                rots[4 * i] = cos(half).toFloat()
                rots[4 * i + 1] = 0f
                rots[4 * i + 2] = sin(half).toFloat()
                rots[4 * i + 3] = 0f
                opacity[i] = 5f
                for (c in 0 until 3) dc[3 * i + c] = (rnd.nextFloat() - 0.5f) / SH_C0
            }
            return SplatCloud(total, cloud.shDegree, pos, scales, rots, opacity, dc, rest)
        }

        private fun writePng(image: RgbImage, file: File): String {
            val pixels = IntArray(image.width * image.height) { k ->
                val r = image.rgba[4 * k].toInt() and 0xFF
                val g = image.rgba[4 * k + 1].toInt() and 0xFF
                val b = image.rgba[4 * k + 2].toInt() and 0xFF
                (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            val bmp = Bitmap.createBitmap(pixels, image.width, image.height, Bitmap.Config.ARGB_8888)
            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            return file.path
        }

        private fun rotation(m: SparseModel, f: Int) = Quat(
            m.rotationsCcvW[4 * f], m.rotationsCcvW[4 * f + 1], m.rotationsCcvW[4 * f + 2], m.rotationsCcvW[4 * f + 3],
        )

        /** True `R_Ccv_W`: the inverse of `R_W_Cgl * R_Cgl_Ccv`. */
        private fun truthCcvW(f: Int): Quat = (scan.frames[f].pose.rotation * SyntheticScan.CGL_FROM_CCV).conjugate()

        /** Sorted rotation errors of each registered frame relative to the previous registered one, degrees. */
        private fun stepRotationErrorsDeg(): List<Double> {
            val reg = (0 until FRAMES).filter { model.registered[it] }
            return reg.zipWithNext { a, i ->
                val est = rotation(model, i) * rotation(model, a).conjugate()
                val truth = truthCcvW(i) * truthCcvW(a).conjugate()
                angleDeg(est * truth.conjugate())
            }.sorted()
        }

        private fun angleDeg(q: Quat): Double {
            val n = q.normalized()
            return Math.toDegrees(2 * acos(min(1.0, abs(n.w.toDouble()))))
        }

        private fun toCamera(m: SparseModel, f: Int, p: Int): Vec3 {
            val x = Vec3(m.points[3 * p], m.points[3 * p + 1], m.points[3 * p + 2])
            val c = rotation(m, f).rotate(x)
            return Vec3(c.x + m.translationsCcvW[3 * f], c.y + m.translationsCcvW[3 * f + 1], c.z + m.translationsCcvW[3 * f + 2])
        }

        private fun meanReprojPx(m: SparseModel): Double {
            var sum = 0.0
            for (k in 0 until m.observationCount) {
                val c = toCamera(m, m.obsFrame[k], m.obsPoint[k])
                val du = F * c.x / c.z + W / 2f - m.obsUv[2 * k]
                val dv = F * c.y / c.z + H / 2f - m.obsUv[2 * k + 1]
                sum += sqrt((du * du + dv * dv).toDouble())
            }
            return if (m.observationCount == 0) Double.NaN else sum / m.observationCount
        }

        /** To the log and, as the shell user, to [DEVICE_DIR]/reconstruct.txt (tools/e2e.sh pulls it to build/e2e/). */
        private fun report(line: String) {
            Log.i(TAG, line)
            deviceWrite("reconstruct.txt", (line + "\n").toByteArray())
            deviceWrite("reconstruct-frame00.png", File(paths[0]).readBytes())
        }

        private fun deviceWrite(name: String, bytes: ByteArray) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
            val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
            ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand("mkdir -p $DEVICE_DIR")).use { it.readBytes() }
            val (out, input) = ui.executeShellCommandRw("tee $DEVICE_DIR/$name")
            ParcelFileDescriptor.AutoCloseOutputStream(input).use { it.write(bytes) }
            ParcelFileDescriptor.AutoCloseInputStream(out).use { it.readBytes() }
        }
    }
}
