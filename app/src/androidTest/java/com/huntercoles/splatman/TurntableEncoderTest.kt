package com.huntercoles.splatman

import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.huntercoles.splatman.viewer.export.video.OrbitPath
import com.huntercoles.splatman.viewer.export.video.TurntableEncoder
import com.huntercoles.splatman.viewer.export.video.TurntableResult
import com.huntercoles.splatman.viewer.export.video.VideoEncoderChoice
import com.huntercoles.splatman.viewer.export.video.VideoSpec
import com.huntercoles.splatman.viewer.presentation.load.SplatFileDecoder
import com.huntercoles.splatman.viewer.rendering.GaussianCameraController
import com.huntercoles.splatman.viewer.rendering.ViewerConstants
import com.huntercoles.splatman.viewer.rendering.gl.RenderScene
import com.huntercoles.splatman.viewer.rendering.gl.SceneFraming
import com.huntercoles.splatman.viewer.rendering.pack.SplatPacker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * #262: the cactus sample encodes into a looping orbit MP4 that MediaExtractor reads back as one H.264 track of
 * [OrbitPath.FRAMES] frames lasting about [OrbitPath.SECONDS] s; a cancelled encode leaves no file. Skipped (not
 * failed) on a device without an H.264 surface encoder.
 *
 * Progress must keep moving: no frame may take [MAX_FRAME_GAP_MS] (the input dispatch timeout). A frame that long
 * holds the emulator's shared GL renderer, and the app's windows stop answering input (keyDispatchingTimedOut).
 */
@RunWith(AndroidJUnit4::class)
class TurntableEncoderTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var choice: VideoEncoderChoice
    private lateinit var scene: RenderScene
    private lateinit var path: OrbitPath
    private lateinit var out: File

    @Before
    fun setUp() {
        val found = VideoEncoderChoice.find()
        assumeTrue("no H.264 surface encoder on this device", found != null)
        choice = requireNotNull(found)
        val cloud = context.assets.open(SAMPLE).use { SplatFileDecoder().decode(it) }.cloud
        val framing = SceneFraming.of(cloud.positions, cloud.count, ViewerConstants.FOV_DEGREES)
        scene = RenderScene(SplatPacker().pack(cloud), cloud.positions, framing.radius)
        val camera = GaussianCameraController().apply {
            setAspectRatio(1f)
            setInitialView(framing.target, framing.radius)
        }
        path = OrbitPath.of(camera.getRotation(), camera.getDistance(), framing.target)
        out = File(context.cacheDir, "turntable-test.mp4").apply { delete() }
    }

    @Test
    fun cactusEncodesToALoopingH264Orbit() = runBlocking {
        val progress = mutableListOf<Float>()
        val stamps = mutableListOf<Long>()
        val started = System.nanoTime()
        val result = withTimeout(TIMEOUT_MS) {
            TurntableEncoder().encode(scene, path, choice, out) {
                progress += it
                stamps += System.nanoTime()
            }
        }
        val seconds = (System.nanoTime() - started) / 1e9
        val gapsMs = (listOf(started) + stamps).zipWithNext { a, b -> (b - a) / 1_000_000 }
        android.util.Log.i(
            "TurntableEncoderTest",
            "encoded ${choice.spec.size}px in %.1f s, slowest frame %d ms".format(seconds, gapsMs.max()),
        )
        assertTrue("a frame took ${gapsMs.max()} ms", gapsMs.max() < MAX_FRAME_GAP_MS)
        assertEquals(TurntableResult.Done(out, OrbitPath.FRAMES), result)
        assertEquals(OrbitPath.FRAMES, progress.size)
        assertEquals(1f, progress.last(), 0f)
        assertEquals(progress.sorted(), progress)

        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(out.path)
            assertEquals(1, extractor.trackCount)
            val format = extractor.getTrackFormat(0)
            assertEquals(VideoSpec.MIME, format.getString(MediaFormat.KEY_MIME))
            assertEquals(choice.spec.size, format.getInteger(MediaFormat.KEY_WIDTH))
            assertEquals(choice.spec.size, format.getInteger(MediaFormat.KEY_HEIGHT))
            val durationUs = format.getLong(MediaFormat.KEY_DURATION)
            val expectedUs = OrbitPath.SECONDS * 1_000_000L
            assertTrue("duration $durationUs us", abs(durationUs - expectedUs) <= expectedUs / 10)
            extractor.selectTrack(0)
            var frames = 0
            while (extractor.sampleTime >= 0) {
                frames++
                extractor.advance()
            }
            assertEquals(OrbitPath.FRAMES, frames)
        } finally {
            extractor.release()
            out.delete()
        }
    }

    @Test
    fun cancelledEncodeLeavesNoFile() = runBlocking {
        lateinit var encode: Deferred<TurntableResult>
        // Cancelled as the sheet's Back does: the caller's job, a quarter of the way in.
        encode = async { TurntableEncoder().encode(scene, path, choice, out) { if (it >= CANCEL_AT) encode.cancel() } }
        val cancelled = try {
            encode.await()
            false
        } catch (e: CancellationException) {
            true
        }
        assertTrue("the encode was not cancelled", cancelled)
        assertFalse("a partial file was left", out.exists())
    }

    private companion object {
        const val SAMPLE = "samples/cactus.spz"
        const val TIMEOUT_MS = 10 * 60_000L
        const val CANCEL_AT = 0.25f
        const val MAX_FRAME_GAP_MS = 5_000L
    }
}
