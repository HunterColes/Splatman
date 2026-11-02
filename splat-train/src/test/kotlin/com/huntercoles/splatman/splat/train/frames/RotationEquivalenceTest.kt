package com.huntercoles.splatman.splat.train.frames

import com.huntercoles.splatman.splat.frames.FrameConversions
import com.huntercoles.splatman.splat.math.Quat
import com.huntercoles.splatman.splat.math.Vec3
import com.huntercoles.splatman.splat.train.frames.FrameFixtures.CACTUS_UP
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

/**
 * Frame suite 2 (card #144): turning the camera by R about the cactus' centre renders the same image as turning
 * the cactus by R^-1 about it and keeping the canonical camera. The turned camera arrives the way the app gets one,
 * as an ARCore pose through [FrameConversions.poseFromArCore]; the scene map is built only from
 * [FrameConversions.poseMatrix] and [FrameConversions.viewMatrix]. SH DC only: the rasteriser draws DC, and a
 * turned cloud's view-dependent colour would have to turn as well.
 */
class RotationEquivalenceTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("rotations")
    fun `turning the camera by R renders as turning the cloud by R inverse`(name: String, r: Quat) {
        val view = FrameFixtures.canonicalView(cloud)
        val turned = FrameFixtures.orbit(view, r, centre)
        val arCorePose = FrameConversions.poseFromArCore(
            FrameFixtures.arCoreTranslation(turned.pose.translationM),
            FrameFixtures.arCoreXyzw(turned.pose.rotation),
        )
        val byCamera = FrameFixtures.render(cloud, FrameFixtures.View(view.intrinsics, arCorePose))
        val scene = FrameFixtures.transform(cloud, FrameFixtures.sceneMapFor(view.pose, turned.pose))
        val byCloud = FrameFixtures.render(scene, view)

        assertTrue(FrameFixtures.mass(byCamera) > MIN_MASS, "$name: turned camera sees nothing")
        val psnr = FrameFixtures.psnr(byCamera.color, byCloud.color)
        assertTrue(psnr >= 40.0, "$name: camera turned vs cloud turned back: $psnr dB, want >= 40")
    }

    companion object {
        private const val MIN_MASS = 50.0
        private val cloud by lazy { FrameFixtures.dcOnly(FrameFixtures.load(CACTUS_UP)) }
        private val centre by lazy { FrameFixtures.boxCentre(cloud) }

        private val YAW = Vec3(0f, 1f, 0f)
        private val PITCH = Vec3(1f, 0f, 0f)
        private val ROLL = Vec3(0f, 0f, 1f)

        private fun about(axis: Vec3, deg: Double): Quat = Quat.fromAxisAngle(axis, Math.toRadians(deg).toFloat())

        @JvmStatic
        fun rotations(): Stream<Arguments> {
            val single = listOf("yaw" to YAW, "pitch" to PITCH, "roll" to ROLL).flatMap { (name, axis) ->
                listOf(30.0, 90.0, 180.0).map { deg -> Arguments.of("$name $deg", about(axis, deg)) }
            }
            return (
                single + listOf(
                    // Gimbal: pitch 90 first, then yaw; the yaw axis now lies where roll was.
                    Arguments.of("gimbal pitch 90 then yaw 40", about(YAW, 40.0) * about(PITCH, 90.0)),
                    Arguments.of(
                        "compound yaw 60, pitch -35, roll 25",
                        about(ROLL, 25.0) * about(PITCH, -35.0) * about(YAW, 60.0),
                    ),
                )
                ).stream()
        }
    }
}
