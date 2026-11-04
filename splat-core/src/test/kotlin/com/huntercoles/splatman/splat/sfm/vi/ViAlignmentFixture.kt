package com.huntercoles.splatman.splat.sfm.vi

import java.util.Random
import kotlin.math.acos
import kotlin.math.round

/**
 * Turns a [ViSim] into what [ViAlignment.align] and [CameraImuRotation.refine] see: camera poses in an SfM frame W
 * (a fixed rotation and offset of G, positions divided by [metresPerUnit]), with Gaussian rotation noise
 * [rotationSigma] (rad, right-multiplied) and position noise [positionSigma] (m, before the division), and the IMU
 * preintegrated (linearised at zero bias) between every [step]-th keyframe of [from] .. [to].
 */
@Suppress("LongParameterList") // A fixture's knobs, each named at the call site.
class ViAlignmentFixture(
    val sim: ViSim,
    val metresPerUnit: Double = 2.5,
    rotationSigma: Double = 0.0,
    positionSigma: Double = 0.0,
    val from: Int = 0,
    val to: Int = sim.keyframes.size - 1,
    val step: Int = 1,
    seed: Long = 11,
    /** Keyframe index and offset in G (m) of one bad SfM position, or null. */
    outlier: Pair<Int, DoubleArray>? = null,
) {
    val rWG: DoubleArray = SO3.exp(doubleArrayOf(0.3, -0.7, 1.1))
    private val origin = doubleArrayOf(0.4, -0.2, 1.0)
    private val rnd = Random(seed)
    val frames: List<Int> = (from..to step step).toList()

    val poses: List<ViPose> = frames.map { k ->
        val kf = sim.keyframes[k]
        val n = DoubleArray(3) { rotationSigma * rnd.nextGaussian() }
        val bad = if (outlier?.first == k) outlier.second else DoubleArray(3)
        val pG = DoubleArray(3) { kf.pGC[it] + bad[it] + positionSigma * rnd.nextGaussian() }
        ViPose(
            Mat3.mul(Mat3.mul(rWG, kf.rGC), SO3.exp(n)),
            Mat3.scale(Mat3.mv(rWG, Mat3.sub(pG, origin)), 1.0 / metresPerUnit),
        )
    }

    val preintegrations: List<ImuPreintegration> = frames.zipWithNext { i, j -> window(i, j) }

    val rCS: DoubleArray = Mat3.transpose(sim.config.rSC)
    val leverArm: DoubleArray = sim.config.pSC
    val gravityW: DoubleArray = Mat3.mv(rWG, sim.gravity)

    /** Mean true gyro bias over the IMU samples the windows use. */
    val trueGyroBias: DoubleArray = run {
        val used = sim.imu.subList(imuIndex(frames.first()), imuIndex(frames.last()) + 1)
        DoubleArray(3) { a -> used.sumOf { it.gyroBias[a] } / used.size }
    }

    fun trueVelocityW(k: Int): DoubleArray = Mat3.mv(rWG, sim.state(time(k)).v)

    fun align(rCS: DoubleArray = this.rCS) = ViAlignment.align(poses, preintegrations, rCS, leverArm)

    /** Preintegration from keyframe [i] to [j], each step with the mean of its two bracketing samples. */
    fun window(i: Int, j: Int): ImuPreintegration = ImuPreintegration(DoubleArray(3), DoubleArray(3), sim.config.noise)
        .apply {
            for (s in imuIndex(i) until imuIndex(j)) {
                val a = sim.imu[s]
                val b = sim.imu[s + 1]
                integrate(b.t - a.t, mid(a.gyro, b.gyro), mid(a.accel, b.accel))
            }
        }

    /** SfM relative rotation `R_CiCj` of keyframes [i], [j] (sim indices, both in [frames]). */
    fun sfmRelative(i: Int, j: Int): DoubleArray =
        Mat3.tmul(poses[frames.indexOf(i)].rWC, poses[frames.indexOf(j)].rWC)

    private fun time(k: Int) = sim.keyframes[k].tCam + sim.config.td
    private fun imuIndex(k: Int) = round(time(k) * sim.config.imuRate).toInt()
    private fun mid(a: DoubleArray, b: DoubleArray) = DoubleArray(3) { (a[it] + b[it]) / 2 }

    companion object {
        fun angleDeg(a: DoubleArray, b: DoubleArray): Double {
            val c = Mat3.dot(a, b) / (Mat3.norm(a) * Mat3.norm(b))
            return Math.toDegrees(acos(c.coerceIn(-1.0, 1.0)))
        }

        fun rotationAngleDeg(a: DoubleArray, b: DoubleArray): Double = Math.toDegrees(Mat3.norm(SO3.log(Mat3.tmul(a, b))))
    }
}
