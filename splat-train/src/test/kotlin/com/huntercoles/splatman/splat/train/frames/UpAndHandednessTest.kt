package com.huntercoles.splatman.splat.train.frames

import com.huntercoles.splatman.splat.math.Quat
import com.huntercoles.splatman.splat.math.Vec3
import com.huntercoles.splatman.splat.model.CameraPose
import com.huntercoles.splatman.splat.train.frames.FrameFixtures.CACTUS_UP
import com.huntercoles.splatman.splat.train.frames.FrameFixtures.TAPE_HAND
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream

/**
 * Frame suite 1 (card #143): two real CC0 scenes, read through the app's PLY reader into W and rendered on the CPU
 * from [FrameFixtures.canonicalView], must come out right side up and not mirrored. The known parts are picked from
 * the raw file coordinates ([FrameFixtures.loadFileFrame]), not from the reader's W, so a wrong frame conversion in
 * the reader moves them on the image.
 */
class UpAndHandednessTest {

    @Test
    fun `the known-down part of each fixture lands in the lower half of the image`() {
        for (fixture in listOf(CACTUS_UP, TAPE_HAND)) {
            val cloud = FrameFixtures.load(fixture)
            val view = FrameFixtures.canonicalView(cloud)
            val m = FrameFixtures.halfMass(cloud, FrameFixtures.knownDown(FrameFixtures.loadFileFrame(fixture)), view)
            assertTrue(m.total > MIN_MASS, "${fixture.resource}: known-down part not on the image (mass ${m.total})")
            assertTrue(
                m.bottom >= IN_HALF * m.total,
                "${fixture.resource}: only ${m.bottom / m.total} of the known-down mass is in the lower half",
            )
        }
    }

    @Test
    fun `the render of the x-mirrored tape is the left-right flip of its render`() {
        val cloud = FrameFixtures.load(TAPE_HAND)
        val view = FrameFixtures.canonicalView(cloud)
        val image = FrameFixtures.render(cloud, view)
        val mirrored = FrameFixtures.render(FrameFixtures.mirrorX(cloud, FrameFixtures.axisX(view)), view)
        val psnr = FrameFixtures.psnr(mirrored.color, FrameFixtures.flipX(image))
        assertTrue(psnr >= 40.0, "mirrored render vs flipped image: $psnr dB, want >= 40")
    }

    @Test
    fun `the tape is chiral and its known-left part is on the image's left`() {
        val cloud = FrameFixtures.load(TAPE_HAND)
        val view = FrameFixtures.canonicalView(cloud)
        val image = FrameFixtures.render(cloud, view)
        val psnr = FrameFixtures.psnr(image.color, FrameFixtures.flipX(image))
        assertTrue(psnr < 25.0, "the tape's render matches its own mirror ($psnr dB): it cannot prove handedness")
        val m = FrameFixtures.halfMass(cloud, FrameFixtures.knownLeft(FrameFixtures.loadFileFrame(TAPE_HAND)), view)
        assertTrue(m.total > MIN_MASS, "known-left part not on the image (mass ${m.total})")
        assertTrue(m.left >= IN_HALF * m.total, "only ${m.left / m.total} of the known-left mass is on the left")
    }

    @Test
    fun `each fixture read into W renders like the raw file seen by the same camera moved into F`() {
        for (fixture in listOf(CACTUS_UP, TAPE_HAND)) {
            val world = FrameFixtures.load(fixture)
            val file = FrameFixtures.loadFileFrame(fixture)
            val view = FrameFixtures.canonicalView(world)
            // W -> F is the X half-turn (ADR 0003), written out here: the same physical camera, posed in F.
            val t = view.pose.translationM
            val fileView = view.copy(pose = CameraPose(Quat(0f, 1f, 0f, 0f) * view.pose.rotation, Vec3(t.x, -t.y, -t.z)))
            val psnr = FrameFixtures.psnr(
                FrameFixtures.render(world, view).color, FrameFixtures.render(file, fileView).color,
            )
            assertTrue(psnr >= 40.0, "${fixture.resource}: W render vs F render $psnr dB, want >= 40")
        }
    }

    @Test
    fun `a missing, truncated or short fixture fails with its name, never as zero splats`() {
        val missing = assertThrows<AssertionError> { FrameFixtures.load(CACTUS_UP) { null } }
        assertTrue("missing" in missing.message!! && CACTUS_UP.resource in missing.message!!, missing.message)

        val cut = FrameFixtures.truncated(CACTUS_UP, 5_000)
        val truncated = assertThrows<AssertionError> { FrameFixtures.load(CACTUS_UP) { ByteArrayInputStream(cut) } }
        assertTrue("truncated" in truncated.message!! && CACTUS_UP.resource in truncated.message!!, truncated.message)

        val header = String(FrameFixtures.truncated(CACTUS_UP, 1_000), Charsets.US_ASCII).substringBefore("end_header")
        val empty = (header.replace("element vertex ${CACTUS_UP.count}", "element vertex 0") + "end_header\n")
            .toByteArray(Charsets.US_ASCII)
        val zero = assertThrows<AssertionError> { FrameFixtures.load(CACTUS_UP) { ByteArrayInputStream(empty) } }
        assertTrue("0 splats, expected ${CACTUS_UP.count}" in zero.message!!, zero.message)
    }

    private companion object {
        /** Share of a known part's render mass that must land in its half of the image. */
        const val IN_HALF = 0.8

        /** Summed alpha, px: a known part must cover at least this much of the image. */
        const val MIN_MASS = 50.0
    }
}
