package com.huntercoles.splatman

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.huntercoles.splatman.library.domain.repository.ScanRepository
import com.huntercoles.splatman.splat.capture.ScanMode
import com.huntercoles.splatman.splat.io.bundle.BundleFrameInput
import com.huntercoles.splatman.splat.io.bundle.CaptureBundleReader
import com.huntercoles.splatman.splat.io.bundle.CaptureBundleWriter
import com.huntercoles.splatman.splat.io.ply.PlyWriter
import com.huntercoles.splatman.splat.math.Quat
import com.huntercoles.splatman.splat.math.Vec3
import com.huntercoles.splatman.splat.model.CameraIntrinsics
import com.huntercoles.splatman.splat.model.CameraPose
import com.huntercoles.splatman.splat.model.SeedPoints
import com.huntercoles.splatman.splat.model.SplatCloud
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * #325 (#293's proof): a room scan on the device opens from inside. The fixture is a Library row (imported through
 * [ScanRepository.import]) whose folder holds a `splat.ply` of a 4 x 2.5 x 4 m box of coloured walls around the
 * origin and a capture bundle (`transforms.json` with `splatman_mode: "room"`, four small frames) whose cameras stand
 * near the middle, the first looking at the red-and-white checked wall (-Z). The viewer opens it by id
 * (`viewer/<scanId>`) and leaves build/e2e/room-viewer-phone.png: the checked wall seen from inside, not the box
 * from outside. The row and its folder are deleted afterwards.
 */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class RoomViewerEndToEndTest {

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createEmptyComposeRule()

    @Inject
    lateinit var scans: ScanRepository

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private var scanId: String? = null
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun openLibrary() {
        hilt.inject()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitForIdle()
        if (exists(hasText(ONBOARDING_START))) compose.onNodeWithText(ONBOARDING_START).performClick()
        compose.waitUntilAtLeastOneExists(hasText(LIBRARY_TITLE), TIMEOUT_MS)
    }

    @After
    fun closeApp() {
        scenario.close()
        scanId?.let { runBlocking { scans.delete(it) } }
    }

    @Test
    fun roomScanOpensFromInsideAtPhoneWidth() {
        val folder = roomFixture()
        assertEquals(ScanMode.ROOM, CaptureBundleReader.readPoses(folder).mode)

        scenario.close()
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_ROUTE, "$VIEWER_ROUTE/${checkNotNull(scanId)}")
        scenario = ActivityScenario.launch(intent)
        compose.waitUntil(TIMEOUT_MS) {
            check(!exists(hasText(VIEWER_FAILED))) { "the viewer failed to load the room fixture" }
            exists(hasContentDescription(VIEWER_STATS)) && !exists(hasText(VIEWER_LOADING, substring = true))
        }
        compose.onNodeWithContentDescription(VIEWER_STATS).performClick()
        compose.waitUntilAtLeastOneExists(hasText("${format(ROOM_COUNT)} splats", substring = true), TIMEOUT_MS)
        compose.onNodeWithContentDescription(VIEWER_STATS).performClick()
        screenshot("room-viewer-phone.png")
    }

    /** The Library row, its `splat.ply` and the room bundle beside it; returns the scan's folder. */
    private fun roomFixture(): File {
        val cloud = roomCloud()
        val scan = runBlocking {
            scans.import(FIXTURE_NAME, System.currentTimeMillis(), cloud.count) { PlyWriter.write(cloud, it) }
        }
        scanId = scan.id
        val folder = File(File(context.filesDir, SCANS_DIR), scan.folder)
        val jpeg = jpeg()
        val intrinsics = CameraIntrinsics(FOCAL, FOCAL, JPEG_W / 2f, JPEG_H / 2f, JPEG_W, JPEG_H)
        val frames = CAMERAS.mapIndexed { i, (yaw, at) ->
            val pose = CameraPose(Quat.fromAxisAngle(Vec3.UNIT_Y, yaw), at)
            BundleFrameInput(timestampNs = i * FRAME_NS, intrinsics = intrinsics, pose = pose, jpeg = jpeg)
        }
        val seed = SeedPoints(1, floatArrayOf(0f, EYE_Y, -HALF), byteArrayOf(-1, 0, 0))
        CaptureBundleWriter.write(folder, frames, seed, mode = ScanMode.ROOM)
        return folder
    }

    /** Walls at x, z = +-2 m, floor at 0, ceiling at 2.5 m, one splat every 10 cm, each face its own colour. */
    private fun roomCloud(): SplatCloud {
        val points = ArrayList<Pair<Vec3, Int>>()
        val n = (2 * HALF / STEP).roundToInt()
        val m = (HEIGHT / STEP).roundToInt()
        for (i in 0..n) for (j in 0..m) {
            val u = -HALF + i * STEP
            val y = j * STEP
            val checked = if ((i / CHECK + j / CHECK) % 2 == 0) Color.rgb(220, 40, 40) else Color.WHITE
            points += Vec3(u, y, -HALF) to checked
            points += Vec3(u, y, HALF) to Color.rgb(40, 80, 220)
            points += Vec3(-HALF, y, u) to Color.rgb(40, 180, 70)
            points += Vec3(HALF, y, u) to Color.rgb(230, 200, 40)
        }
        for (i in 0..n) for (k in 0..n) {
            val x = -HALF + i * STEP
            val z = -HALF + k * STEP
            points += Vec3(x, 0f, z) to Color.rgb(110, 110, 110)
            points += Vec3(x, HEIGHT, z) to Color.rgb(200, 200, 210)
        }
        val count = points.size
        assertEquals(ROOM_COUNT, count)
        val positions = FloatArray(3 * count)
        val shDc = FloatArray(3 * count)
        val rotations = FloatArray(4 * count)
        points.forEachIndexed { v, (p, c) ->
            positions[3 * v] = p.x; positions[3 * v + 1] = p.y; positions[3 * v + 2] = p.z
            shDc[3 * v] = dc(Color.red(c)); shDc[3 * v + 1] = dc(Color.green(c)); shDc[3 * v + 2] = dc(Color.blue(c))
            rotations[4 * v] = 1f
        }
        return SplatCloud(
            count = count,
            shDegree = 0,
            positions = positions,
            logScales = FloatArray(3 * count) { ln(SPLAT_SIZE) },
            rotations = rotations,
            opacityLogits = FloatArray(count) { OPACITY_LOGIT },
            shDc = shDc,
            shRest = FloatArray(0),
        )
    }

    private fun dc(channel: Int): Float = (channel / 255f - 0.5f) / SH_C0

    private fun jpeg(): ByteArray {
        val bitmap = Bitmap.createBitmap(JPEG_W, JPEG_H, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.GRAY)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    private fun exists(matcher: SemanticsMatcher): Boolean =
        compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun format(n: Int): String = java.text.NumberFormat.getIntegerInstance().format(n)

    private fun screenshot(name: String) {
        compose.waitForIdle()
        Thread.sleep(SETTLE_MS) // the GL surface draws after Compose goes idle
        shell("mkdir -p $DEVICE_DIR")
        shell("screencap -p $DEVICE_DIR/$name")
    }

    private fun shell(command: String): String {
        val pfd: ParcelFileDescriptor = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { String(it.readBytes()) }
    }

    private companion object {
        const val ONBOARDING_START = "Skip"
        const val LIBRARY_TITLE = "Splatman"
        const val VIEWER_ROUTE = "viewer" // ShellRoutes.VIEWER
        const val VIEWER_STATS = "Stats"
        const val VIEWER_LOADING = "Opening "
        const val VIEWER_FAILED = "Can't open this splat"
        const val SCANS_DIR = "scans" // library-feature LibraryModule.SCANS_DIR
        const val FIXTURE_NAME = "Room fixture (e2e)"
        const val HALF = 2f
        const val HEIGHT = 2.5f
        const val STEP = 0.1f
        const val CHECK = 5
        const val EYE_Y = 1.4f
        const val SPLAT_SIZE = 0.035f
        const val OPACITY_LOGIT = 4f
        const val SH_C0 = 0.28209479f

        // 41 x 26 on each of four walls, 41 x 41 on floor and ceiling.
        const val ROOM_COUNT = 4 * 41 * 26 + 2 * 41 * 41
        const val FOCAL = 50f
        const val JPEG_W = 64
        const val JPEG_H = 48
        const val JPEG_QUALITY = 90
        const val FRAME_NS = 33_000_000L

        /** Yaw about +Y and camera centre: the first looks along -Z at the checked wall. */
        val CAMERAS = listOf(
            0f to Vec3(0.2f, EYE_Y, 0.2f),
            (PI / 2).toFloat() to Vec3(-0.2f, EYE_Y, 0.2f),
            PI.toFloat() to Vec3(-0.2f, EYE_Y, -0.2f),
            (-PI / 2).toFloat() to Vec3(0.2f, EYE_Y, -0.2f),
        )
        const val SETTLE_MS = 1_500L
        const val DEVICE_DIR = "/sdcard/Download/splatman-e2e"
        const val TIMEOUT_MS = 60_000L
    }
}
