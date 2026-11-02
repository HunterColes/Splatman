package com.huntercoles.splatman.splat.train.frames

import com.huntercoles.splatman.splat.io.ply.PlyReader
import com.huntercoles.splatman.splat.io.ply.PlyWriter
import com.huntercoles.splatman.splat.io.splat.SplatCodec
import com.huntercoles.splatman.splat.io.spz.SpzCodec
import com.huntercoles.splatman.splat.math.Quat
import com.huntercoles.splatman.splat.math.Vec3
import com.huntercoles.splatman.splat.model.SplatCloud
import com.huntercoles.splatman.splat.train.frames.FrameFixtures.CACTUS_UP
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.abs

/**
 * Frame suite 3 (card #145): the up fixture in W, written and read back through each export format, renders from
 * three cameras like the original, and the bundled cactus.spz (written by another tool, frame F) reads upright.
 * A codec that flips on write and back on read passes a round trip; only the foreign file catches it.
 */
class FormatFrameTest {

    /** A codec seen from the test: W cloud in, the W cloud read back out. */
    private class Format(val name: String, val minPsnr: Double, val roundTrip: (SplatCloud) -> SplatCloud)

    // The card asks >= 40 dB (PLY) and >= 35 dB (.splat, SPZ); clean round trips measure 54.6 (.splat) and 57.5 (SPZ),
    // a swapped .splat quaternion order 37.9, so the lossy formats are held to 45.
    private val formats = listOf(
        Format("PLY", 40.0) { c ->
            val out = ByteArrayOutputStream().also { PlyWriter.write(c, it) }
            PlyReader.read(ByteArrayInputStream(out.toByteArray()))
        },
        Format(".splat", 45.0) { c -> SplatCodec.read(SplatCodec.write(c)) },
        Format("SPZ", 45.0) { c -> SpzCodec.read(SpzCodec.write(c)) },
    )

    @Test
    fun `the up fixture survives each format's round trip seen from front, side and top`() {
        val cloud = FrameFixtures.load(CACTUS_UP)
        val front = FrameFixtures.canonicalView(cloud)
        val centre = FrameFixtures.boxCentre(cloud)
        val views = listOf(
            "front" to front,
            "side" to FrameFixtures.orbit(front, Quat.fromAxisAngle(Y_AXIS, HALF_PI), centre),
            "top" to FrameFixtures.orbit(front, Quat.fromAxisAngle(X_AXIS, -HALF_PI), centre),
        )
        val reference = views.map { (_, v) -> FrameFixtures.render(cloud, v) }
        // .splat stores SH0 only: its reference is the DC-only original.
        val dcReference = views.map { (_, v) -> FrameFixtures.render(FrameFixtures.dcOnly(cloud), v) }
        for (format in formats) {
            val back = format.roundTrip(cloud)
            assertEquals(cloud.count, back.count, "${format.name}: splat count")
            for ((i, named) in views.withIndex()) {
                val (name, view) = named
                val image = FrameFixtures.render(back, view)
                assertTrue(FrameFixtures.mass(image) > MIN_MASS, "${format.name} $name: nothing on the image")
                val ref = if (back.shDegree == 0 && cloud.shDegree > 0) dcReference[i] else reference[i]
                val psnr = FrameFixtures.psnr(ref.color, image.color)
                assertTrue(
                    psnr >= format.minPsnr,
                    "${format.name} $name: round trip vs original $psnr dB, want >= ${format.minPsnr}",
                )
            }
        }
    }

    @Test
    fun `the bundled cactus spz from another tool reads with its pot in the lower half`() {
        val cloud = FrameFixtures.loadSample(FrameFixtures.CACTUS_SPZ) { SpzCodec.read(it) }
        val view = FrameFixtures.canonicalView(cloud)
        val pot = FrameFixtures.bluish(cloud)
        assertTrue(pot.size >= cloud.count / 20, "only ${pot.size} of ${cloud.count} splats are pot-coloured")
        val m = FrameFixtures.halfMass(cloud, pot, view)
        assertTrue(m.total > MIN_MASS, "the pot is not on the image (mass ${m.total})")
        assertTrue(m.bottom >= POT_LOW * m.total, "only ${m.bottom / m.total} of the pot's mass is in the lower half")
    }

    @Test
    fun `axis extremes and half-turn rotations survive each round trip within quantisation`() {
        val cloud = FrameFixtures.extremes(EXTENT)
        val tolerances = mapOf("PLY" to (0f to 0f), ".splat" to (0f to 1f / 64), "SPZ" to (1f / 2048 to 1f / 64))
        for (format in formats) {
            val back = format.roundTrip(cloud)
            val (posTol, rotTol) = tolerances.getValue(format.name)
            assertEquals(cloud.count, back.count, "${format.name}: splat count")
            for (i in 0 until cloud.count) {
                for (a in 0 until 3) {
                    val want = cloud.positions[3 * i + a]
                    val got = back.positions[3 * i + a]
                    assertTrue(abs(want - got) <= posTol, "${format.name} splat $i axis $a: $got, want $want")
                }
                val q = cloud.rotation(i).normalized()
                val r = back.rotation(i).normalized()
                // q and -q are the same rotation.
                val dot = abs(q.w * r.w + q.x * r.x + q.y * r.y + q.z * r.z)
                assertTrue(1f - dot <= rotTol, "${format.name} splat $i: rotation $r, want $q (|dot| $dot)")
            }
        }
    }

    private companion object {
        val X_AXIS = Vec3(1f, 0f, 0f)
        val Y_AXIS = Vec3(0f, 1f, 0f)
        const val HALF_PI = (Math.PI / 2).toFloat()
        /**
         * The colour pick also catches some blue-grey cactus splats: upright reads 0.66 of the pot's mass in the
         * lower half, a y flip on read 0.34. 0.6 sits between them.
         */
        const val POT_LOW = 0.6
        const val MIN_MASS = 50.0
        const val EXTENT = 100f
    }
}
