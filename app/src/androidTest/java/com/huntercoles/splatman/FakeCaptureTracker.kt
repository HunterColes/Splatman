package com.huntercoles.splatman

import com.huntercoles.splatman.capture.presentation.capture.CaptureTracker
import com.huntercoles.splatman.capture.presentation.capture.CaptureTrackerModule
import com.huntercoles.splatman.capture.preview.PreviewTarget
import com.huntercoles.splatman.capture.tracking.CameraImage
import com.huntercoles.splatman.capture.tracking.FeaturePoints
import com.huntercoles.splatman.capture.tracking.LumaPlane
import com.huntercoles.splatman.capture.tracking.PoseSourceStatus
import com.huntercoles.splatman.capture.tracking.TrackedFrame
import com.huntercoles.splatman.capture.tracking.TrackingState
import com.huntercoles.splatman.splat.math.Quat
import com.huntercoles.splatman.splat.math.Vec3
import com.huntercoles.splatman.splat.model.CameraIntrinsics
import com.huntercoles.splatman.splat.model.CameraPose
import com.huntercoles.splatman.splat.model.RgbImage
import dagger.Module
import dagger.Provides
import dagger.hilt.android.components.ViewModelComponent
import dagger.hilt.testing.TestInstallIn
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A tracker with no camera, for the emulator (no ARCore): once started it reports Running and, while in the
 * foreground, emits a frame every [FRAME_MS] walking round a circle of [RADIUS_M] about the origin, looking in, with
 * a textured 640x480 image and a few feature points. Each frame turns [STEP_DEG] degrees, so the recorder's
 * keyframe policy (10 degrees) takes one every few frames and coverage grows as it goes round. After the first turn
 * it rises to [HIGH_DEG] above the origin over [RISE_FRAMES] frames, so a second band fills and Finish unlocks.
 *
 * While [hold] is true it emits nothing. A recording changes the screen on every frame, and on the emulator drawing
 * one takes about as long as the gap to the next, so Compose never goes idle and every node lookup (which waits for
 * idle) blocks for good (#189). Tests hold the frames while they look at the screen.
 */
class FakeCaptureTracker : CaptureTracker {
    private val mutableStatus = MutableStateFlow<PoseSourceStatus>(PoseSourceStatus.Idle)
    private val foreground = MutableStateFlow(true)
    private val started = CompletableDeferred<Unit>()
    @Volatile private var released = false

    override val status: StateFlow<PoseSourceStatus> = mutableStatus.asStateFlow()

    override val frames: Flow<TrackedFrame> = flow {
        started.await()
        var i = 0
        while (!released) {
            foreground.filter { it }.first()
            hold.filter { !it }.first()
            emit(frame(i++))
            delay(FRAME_MS)
        }
    }

    override suspend fun start() {
        mutableStatus.value = PoseSourceStatus.Running
        started.complete(Unit)
    }

    override fun setForeground(inForeground: Boolean) {
        foreground.value = inForeground
        if (mutableStatus.value != PoseSourceStatus.Idle) {
            mutableStatus.value = if (inForeground) PoseSourceStatus.Running else PoseSourceStatus.Paused
        }
    }

    override fun setPreview(target: PreviewTarget?): Job = Job().apply { complete() }

    override fun release() {
        released = true
    }

    private fun frame(i: Int): TrackedFrame {
        val theta = (i * STEP_DEG * PI / 180).toFloat()
        val turn = 360 / STEP_DEG
        val rise = ((i - turn) / RISE_FRAMES).coerceIn(0.0, 1.0)
        val elevation = (rise * HIGH_DEG * PI / 180).toFloat()
        // Cgl looks down -Z; turning it by theta about +Y and pitching it down by the elevation looks from
        // (r cos e sin, r sin e, r cos e cos) at the origin.
        val pose = CameraPose(
            Quat.fromAxisAngle(Vec3(0f, 1f, 0f), theta) * Quat.fromAxisAngle(Vec3(1f, 0f, 0f), -elevation),
            Vec3(
                RADIUS_M * cos(elevation) * sin(theta),
                RADIUS_M * sin(elevation),
                RADIUS_M * cos(elevation) * cos(theta),
            ),
        )
        val k = CameraIntrinsics(fx = 500f, fy = 500f, cx = 319.5f, cy = 239.5f, width = WIDTH, height = HEIGHT)
        return TrackedFrame(
            timestampNs = i * FRAME_MS * 1_000_000L,
            trackingState = TrackingState.TRACKING,
            pose = pose,
            intrinsics = k,
            image = TexturedImage(),
            points = GRID,
            depth = null,
        )
    }

    /** The same checkerboard-like texture every frame: constant sharpness, so the policy keys on motion alone. */
    private class TexturedImage : CameraImage {
        override val width = WIDTH
        override val height = HEIGHT

        override fun toRgb(): RgbImage = RgbImage(width, height, ByteArray(width * height * 4) { 0x80.toByte() })

        override fun luma(): LumaPlane =
            LumaPlane(width, height, width, ByteArray(width * height) { j -> (((j % width) xor (j / width)) * 37).toByte() })

        override fun close() = Unit
    }

    companion object {
        /** True: no frames until it is false again (shared by every instance; tests reset it). */
        val hold = MutableStateFlow(false)

        private const val WIDTH = 640
        private const val HEIGHT = 480
        private const val FRAME_MS = 33L
        private const val STEP_DEG = 2.0
        private const val RADIUS_M = 0.3f
        private const val HIGH_DEG = 45.0
        private const val RISE_FRAMES = 60.0

        /** 27 points 5 cm apart round the origin, full confidence. */
        private val GRID: FeaturePoints = run {
            val p = FloatArray(81)
            var o = 0
            for (x in -1..1) for (y in -1..1) for (z in -1..1) {
                p[o++] = 0.05f * x
                p[o++] = 0.05f * y
                p[o++] = 0.05f * z
            }
            FeaturePoints(27, p, FloatArray(27) { 1f })
        }
    }
}

/** Every capture view model in the device tests gets a [FakeCaptureTracker] instead of ARCore. */
@Module
@TestInstallIn(components = [ViewModelComponent::class], replaces = [CaptureTrackerModule::class])
object FakeCaptureTrackerModule {
    @Provides
    fun captureTracker(): CaptureTracker = FakeCaptureTracker()
}
