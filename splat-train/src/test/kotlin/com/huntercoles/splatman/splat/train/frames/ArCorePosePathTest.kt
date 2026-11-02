package com.huntercoles.splatman.splat.train.frames

import com.huntercoles.splatman.splat.frames.FrameConversions
import com.huntercoles.splatman.splat.math.Quat
import com.huntercoles.splatman.splat.math.Vec3
import com.huntercoles.splatman.splat.model.CameraPose
import com.huntercoles.splatman.splat.train.frames.FrameFixtures.CACTUS_UP
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Frame suite 2 (card #144): an ARCore pose (translation, quaternion x, y, z, w) aimed at the cactus, read through
 * [FrameConversions.poseFromArCore], renders as the camera built directly; read as w, x, y, z it does not.
 */
class ArCorePosePathTest {

    private val cloud by lazy { FrameFixtures.load(CACTUS_UP) }

    /** The canonical camera orbited about the cactus by a turn with no symmetric component order. */
    private fun direct(): FrameFixtures.View {
        val view = FrameFixtures.canonicalView(cloud)
        val r = Quat.fromAxisAngle(Vec3(0f, 1f, 0f), Math.toRadians(50.0).toFloat()) *
            Quat.fromAxisAngle(Vec3(1f, 0f, 0f), Math.toRadians(-25.0).toFloat())
        return FrameFixtures.orbit(view, r, FrameFixtures.boxCentre(cloud))
    }

    @Test
    fun `an ARCore pose renders as the canonical camera built directly`() {
        val view = direct()
        val xyzw = FrameFixtures.arCoreXyzw(view.pose.rotation)
        val pose = FrameConversions.poseFromArCore(FrameFixtures.arCoreTranslation(view.pose.translationM), xyzw)
        val expected = FrameFixtures.render(cloud, view)
        assertTrue(FrameFixtures.mass(expected) > MIN_MASS, "direct camera sees nothing")
        val psnr = FrameFixtures.psnr(FrameFixtures.render(cloud, FrameFixtures.View(view.intrinsics, pose)).color,
            expected.color)
        assertTrue(psnr >= 40.0, "ARCore pose vs direct camera: $psnr dB, want >= 40")
    }

    @Test
    fun `the same quaternion misread as w, x, y, z renders differently`() {
        val view = direct()
        val xyzw = FrameFixtures.arCoreXyzw(view.pose.rotation)
        val misread = CameraPose(Quat(xyzw[0], xyzw[1], xyzw[2], xyzw[3]).normalized(), view.pose.translationM)
        val read = FrameConversions.poseFromArCore(FrameFixtures.arCoreTranslation(view.pose.translationM), xyzw)
        val misreadImage = FrameFixtures.render(cloud, FrameFixtures.View(view.intrinsics, misread)).color
        val vsDirect = FrameFixtures.psnr(misreadImage, FrameFixtures.render(cloud, view).color)
        assertTrue(vsDirect < 25.0, "misread quaternion vs direct camera: $vsDirect dB, want < 25")
        // The app's reader must not be the misreading one.
        val vsRead = FrameFixtures.psnr(
            misreadImage,
            FrameFixtures.render(cloud, FrameFixtures.View(view.intrinsics, read)).color,
        )
        assertTrue(vsRead < 25.0, "misread quaternion vs poseFromArCore: $vsRead dB, want < 25")
    }

    @Test
    fun `a non-unit ARCore quaternion is normalised, not refused`() {
        val view = direct()
        val unit = FrameFixtures.arCoreXyzw(view.pose.rotation)
        val scaled = FloatArray(4) { unit[it] * 3.5f }
        val t = FrameFixtures.arCoreTranslation(view.pose.translationM)
        val pose = FrameConversions.poseFromArCore(t, scaled)
        assertEquals(1f, pose.rotation.norm(), 1e-5f, "rotation of a scaled ARCore quaternion is not unit")
        val psnr = FrameFixtures.psnr(
            FrameFixtures.render(cloud, FrameFixtures.View(view.intrinsics, pose)).color,
            FrameFixtures.render(cloud, view).color,
        )
        assertTrue(psnr >= 40.0, "scaled ARCore quaternion vs direct camera: $psnr dB, want >= 40")
    }

    private companion object {
        const val MIN_MASS = 50.0
    }
}
