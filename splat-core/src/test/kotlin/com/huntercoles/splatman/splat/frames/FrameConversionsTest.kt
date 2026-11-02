package com.huntercoles.splatman.splat.frames

import com.huntercoles.splatman.splat.math.Mat4
import com.huntercoles.splatman.splat.math.Quat
import com.huntercoles.splatman.splat.math.Vec3
import com.huntercoles.splatman.splat.math.assertMat
import com.huntercoles.splatman.splat.math.assertQuat
import com.huntercoles.splatman.splat.math.assertVec
import com.huntercoles.splatman.splat.model.CameraIntrinsics
import com.huntercoles.splatman.splat.model.CameraPose
import com.huntercoles.splatman.splat.model.SplatCloud
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream
import kotlin.math.PI
import kotlin.random.Random

/** ADR 0009 "Frames" fixtures for ADR 0003's FrameConversions: 1e-5 px, 1e-6 elsewhere. */
class FrameConversionsTest {

    private val fc = FrameConversions
    private val phone = CameraIntrinsics(fx = 1000.3f, fy = 998.7f, cx = 719.2f, cy = 541.9f, width = 1440, height = 1080)

    /** World point -> pixel through the full chain: view matrix (W -> Cgl), F (Cgl -> Ccv), pinhole. */
    private fun worldToPixel(pose: CameraPose, k: CameraIntrinsics, pw: Vec3): Pixel? =
        fc.project(k, fc.glToCv(fc.viewMatrix(pose).transformPoint(pw)))

    private val arCoreIdentity = fc.poseFromArCore(floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 0f, 0f, 1f))

    // ---- ARCore pose and the full chain ----

    @Test
    fun `identity ARCore pose sends world (0,0,-2) to the principal point`() {
        val px = worldToPixel(arCoreIdentity, phone, Vec3(0f, 0f, -2f))!!
        assertEquals(phone.cx, px.u, 1e-5f)
        assertEquals(phone.cy, px.v, 1e-5f)
    }

    @Test
    fun `a point right of and above the axis lands right of and above the principal point`() {
        val px = worldToPixel(arCoreIdentity, phone, Vec3(0.1f, 0.1f, -2f))!!
        assertTrue(px.u > phone.cx, "u=${px.u} cx=${phone.cx}")
        assertTrue(px.v < phone.cy, "v=${px.v} cy=${phone.cy}")
    }

    @Test
    fun `a point behind the camera does not project`() {
        assertNull(worldToPixel(arCoreIdentity, phone, Vec3(0f, 0f, 2f)))
        assertNull(fc.project(phone, Vec3(0f, 0f, 0f)))
    }

    @Test
    fun `unproject is the inverse of project`() {
        assertVec(Vec3(0f, 0f, 2.5f), fc.unproject(phone, phone.cx, phone.cy, 2.5f), 1e-6f)
        val rnd = Random(29)
        repeat(100) {
            val u = rnd.nextFloat() * phone.width
            val v = rnd.nextFloat() * phone.height
            val d = 0.1f + rnd.nextFloat() * 10f
            val p = fc.unproject(phone, u, v, d)
            assertEquals(d, p.z, 0f)
            val px = fc.project(phone, p)!!
            assertEquals(u, px.u, 2e-3f)
            assertEquals(v, px.v, 2e-3f)
        }
    }

    @Test
    fun `ARCore xyzw becomes wxyz and the pose is T_W_Cgl`() {
        // Camera at (1, 0, 0) turned +90 degrees about +Y: it looks down world -X.
        val h = (0.5 * kotlin.math.sqrt(2.0)).toFloat()
        val pose = fc.poseFromArCore(floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, h, 0f, h))
        assertQuat(Quat(h, 0f, h, 0f), pose.rotation)
        assertVec(Vec3(1f, 0f, 0f), pose.translationM)
        val px = worldToPixel(pose, phone, Vec3(-1f, 0f, 0f))!!
        // Float rotation entries of sqrt(2)/2 (1 ulp ~ 6e-8 rad) times fx ~ 1000 px: a few 1e-4 px.
        assertEquals(phone.cx, px.u, 1e-3f)
        assertEquals(phone.cy, px.v, 1e-3f)
    }

    @Test
    fun `ARCore rotation is normalised`() {
        val pose = fc.poseFromArCore(floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 0f, 0f, 2f))
        assertQuat(Quat.IDENTITY, pose.rotation)
    }

    // ---- quaternion component order ----

    @Test
    fun `xyzw round-trips through wxyz`() {
        val xyzw = floatArrayOf(0.1f, -0.2f, 0.3f, 0.9f)
        val q = fc.quatFromXyzw(xyzw)
        assertQuat(Quat(0.9f, 0.1f, -0.2f, 0.3f), q)
        assertArrayEquals(xyzw, fc.quatToXyzw(q))
        assertQuat(q, fc.quatFromXyzw(fc.quatToXyzw(q)))
    }

    @Test
    fun `quatFromXyzw reads at an offset`() {
        val arr = floatArrayOf(9f, 9f, 1f, 2f, 3f, 4f)
        assertQuat(Quat(4f, 1f, 2f, 3f), fc.quatFromXyzw(arr, 2))
        assertThrows<IllegalArgumentException> { fc.quatFromXyzw(arr, 3) }
    }

    @Test
    fun `a zero quaternion is rejected with a message`() {
        val zero = floatArrayOf(0f, 0f, 0f, 0f)
        listOf(
            assertThrows<IllegalArgumentException> { fc.poseFromArCore(floatArrayOf(0f, 0f, 0f), zero) },
            assertThrows<IllegalArgumentException> { fc.quatFromXyzw(zero) },
            assertThrows<IllegalArgumentException> { fc.quatToXyzw(Quat(0f, 0f, 0f, 0f)) },
            assertThrows<IllegalArgumentException> { fc.worldToFile(Quat(0f, 0f, 0f, 0f)) },
            assertThrows<IllegalArgumentException> { fc.viewMatrix(CameraPose(Quat(0f, 0f, 0f, 0f), Vec3.ZERO)) },
            assertThrows<IllegalArgumentException> {
                fc.rotationsWorldToFile(floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f))
            },
        ).forEach { e -> assertTrue(e.message!!.contains("zero quaternion"), e.message) }
    }

    // ---- Cgl <-> Ccv ----

    @Test
    fun `Cgl to Ccv flips y and z`() {
        assertVec(Vec3(1f, -2f, -3f), fc.glToCv(Vec3(1f, 2f, 3f)))
        assertVec(Vec3(1f, -2f, -3f), fc.cvToGl(Vec3(1f, 2f, 3f)))
    }

    @Test
    fun `Cgl to Ccv to Cgl is the identity for points and poses`() {
        val rnd = Random(3)
        repeat(100) {
            val p = Vec3(rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f)
            assertVec(p, fc.cvToGl(fc.glToCv(p)))
            assertVec(p, fc.glToCv(fc.cvToGl(p)))
            val pose = randomPose(rnd)
            val back = fc.cvToGl(fc.glToCv(pose))
            assertQuat(pose.rotation, back.rotation)
            assertVec(pose.translationM, back.translationM)
            assertQuat(pose.rotation, fc.glToCv(fc.cvToGl(pose)).rotation)
        }
    }

    @Test
    fun `T_W_Ccv looks where T_W_Cgl looks`() {
        val rnd = Random(4)
        repeat(20) {
            val gl = randomPose(rnd)
            val cv = fc.glToCv(gl)
            assertVec(gl.rotation.rotate(Vec3(0f, 0f, -1f)), cv.rotation.rotate(Vec3(0f, 0f, 1f)))
            assertVec(gl.rotation.rotate(Vec3(0f, 1f, 0f)), cv.rotation.rotate(Vec3(0f, -1f, 0f)))
            assertVec(gl.rotation.rotate(Vec3(1f, 0f, 0f)), cv.rotation.rotate(Vec3(1f, 0f, 0f)))
            assertVec(gl.translationM, cv.translationM)
        }
    }

    // ---- view and projection matrices ----

    @Test
    fun `view matrix inverts the pose`() {
        val rnd = Random(5)
        repeat(20) {
            val pose = randomPose(rnd)
            assertMat(Mat4.identity(), fc.viewMatrix(pose) * fc.poseMatrix(pose), 1e-5f)
            assertVec(Vec3.ZERO, fc.viewMatrix(pose).transformPoint(pose.translationM), 1e-5f)
        }
    }

    /** NDC of a Ccv point through the GL path: Ccv -> Cgl -> clip by [p] -> divide, in double. */
    private fun ndc(p: Mat4, pointCv: Vec3): DoubleArray {
        val g = fc.cvToGl(pointCv)
        val v = doubleArrayOf(g.x.toDouble(), g.y.toDouble(), g.z.toDouble(), 1.0)
        val clip = DoubleArray(4) { r -> (0 until 4).sumOf { c -> p[r, c].toDouble() * v[c] } }
        return doubleArrayOf(clip[0] / clip[3], clip[1] / clip[3], clip[2] / clip[3])
    }

    @Test
    fun `projection matrix sends the pixel edges to NDC plus and minus one`() {
        val p = fc.projectionFromIntrinsics(phone, 0.1f, 100f)
        val z = 3.0
        fun xAt(u: Double) = ((u - phone.cx) / phone.fx * z).toFloat()
        fun yAt(v: Double) = ((v - phone.cy) / phone.fy * z).toFloat()
        assertEquals(-1.0, ndc(p, Vec3(xAt(-0.5), 0f, z.toFloat()))[0], 1e-5)
        assertEquals(1.0, ndc(p, Vec3(xAt(phone.width - 0.5), 0f, z.toFloat()))[0], 1e-5)
        assertEquals(1.0, ndc(p, Vec3(0f, yAt(-0.5), z.toFloat()))[1], 1e-5) // top edge of the image
        assertEquals(-1.0, ndc(p, Vec3(0f, yAt(phone.height - 0.5), z.toFloat()))[1], 1e-5)
    }

    @Test
    fun `projection matrix maps near and far to NDC z of minus and plus one`() {
        val p = fc.projectionFromIntrinsics(phone, 0.1f, 100f)
        assertEquals(-1.0, ndc(p, Vec3(0f, 0f, 0.1f))[2], 1e-5)
        assertEquals(1.0, ndc(p, Vec3(0f, 0f, 100f))[2], 1e-5)
    }

    @Test
    fun `projection matrix agrees with the pinhole on 1000 seeded points to 1e-5 px`() {
        // Dyadic intrinsics so every matrix entry is exact in float and the comparison measures the formula.
        val k = CameraIntrinsics(fx = 400f, fy = 300f, cx = 300.5f, cy = 100.25f, width = 512, height = 256)
        val p = fc.projectionFromIntrinsics(k, 0.05f, 50f)
        val rnd = Random(1000)
        repeat(1000) {
            val z = 0.2 + rnd.nextDouble() * 10.0
            val pt = Vec3(
                ((rnd.nextDouble() - 0.5) * z).toFloat(), ((rnd.nextDouble() - 0.5) * z).toFloat(), z.toFloat(),
            )
            val n = ndc(p, pt)
            val uGl = (n[0] + 1.0) * k.width / 2.0 - 0.5
            val vGl = (1.0 - n[1]) * k.height / 2.0 - 0.5
            val uPin = k.fx.toDouble() * pt.x / pt.z + k.cx
            val vPin = k.fy.toDouble() * pt.y / pt.z + k.cy
            assertEquals(uPin, uGl, 1e-5, "u of $pt")
            assertEquals(vPin, vGl, 1e-5, "v of $pt")
            val px = fc.project(k, pt)!!
            assertEquals(uPin, px.u.toDouble(), 2e-4, "project() u of $pt") // float result: a few ulp at 500 px
            assertEquals(vPin, px.v.toDouble(), 2e-4, "project() v of $pt")
        }
    }

    @Test
    fun `projection rejects a bad near and far`() {
        assertThrows<IllegalArgumentException> { fc.projectionFromIntrinsics(phone, 0f, 10f) }
        assertThrows<IllegalArgumentException> { fc.projectionFromIntrinsics(phone, 1f, 1f) }
    }

    @Test
    fun `orbit projection is the field-of-view perspective`() {
        assertMat(Mat4.perspective(1f, 1.5f, 0.1f, 10f), fc.orbitProjection(1f, 1.5f, 0.1f, 10f))
    }

    // ---- Nerfstudio ----

    @Test
    fun `nerfstudio matrix is T_W_Cgl row-major`() {
        val q = Quat.fromAxisAngle(Vec3.UNIT_Z, (PI / 2).toFloat())
        val rows = fc.nerfstudioMatrix(CameraPose(q, Vec3(1f, 2f, 3f)))
        val expected = arrayOf(
            floatArrayOf(0f, -1f, 0f, 1f),
            floatArrayOf(1f, 0f, 0f, 2f),
            floatArrayOf(0f, 0f, 1f, 3f),
            floatArrayOf(0f, 0f, 0f, 1f),
        )
        for (r in 0 until 4) assertArrayEquals(expected[r], rows[r], 1e-6f, "row $r")
    }

    // ---- W <-> F ----

    @Test
    fun `worldToFile flips position y and z and quaternion y and z`() {
        assertVec(Vec3(1f, -2f, -3f), fc.worldToFile(Vec3(1f, 2f, 3f)))
        assertVec(Vec3(1f, -2f, -3f), fc.fileToWorld(Vec3(1f, 2f, 3f)))
        assertQuat(Quat(0.5f, 0.1f, -0.2f, -0.3f), fc.worldToFile(Quat(0.5f, 0.1f, 0.2f, 0.3f)))
        assertQuat(Quat(0.5f, 0.1f, -0.2f, -0.3f), fc.fileToWorld(Quat(0.5f, 0.1f, 0.2f, 0.3f)))
    }

    @Test
    fun `the quaternion rule is G R G`() {
        val rnd = Random(6)
        val g = Quat(0f, 1f, 0f, 0f)
        repeat(20) {
            val q = randomPose(rnd).rotation
            val v = Vec3(rnd.nextFloat(), rnd.nextFloat(), rnd.nextFloat())
            // Rotating in F the converted vector equals converting the vector rotated in W.
            assertVec(fc.worldToFile(q.rotate(v)), fc.worldToFile(q).rotate(fc.worldToFile(v)))
            assertQuat(g * q * g.conjugate(), fc.worldToFile(q))
        }
    }

    @Test
    fun `worldToFile on a cloud converts each array once and twice is the identity`() {
        val cloud = seededCloud(Random(7), count = 50, shDegree = 3)
        val before = snapshot(cloud)
        val f = fc.worldToFile(cloud)
        assertArrayEquals(fc.positionsWorldToFile(cloud.positions), f.positions)
        assertArrayEquals(fc.rotationsWorldToFile(cloud.rotations), f.rotations)
        assertArrayEquals(fc.shRestWorldToFile(cloud.shRest, 3), f.shRest)
        assertArrayEquals(cloud.logScales, f.logScales)
        assertArrayEquals(cloud.opacityLogits, f.opacityLogits)
        assertArrayEquals(cloud.shDc, f.shDc)
        assertVec(fc.worldToFile(cloud.position(7)), f.position(7))
        assertQuat(fc.worldToFile(cloud.rotation(7)), f.rotation(7))

        val back = fc.fileToWorld(f)
        assertArrayEquals(snapshot(cloud), snapshot(back))
        // Inputs untouched, outputs are new arrays.
        assertArrayEquals(before, snapshot(cloud))
        for ((a, b) in arrays(cloud).zip(arrays(f))) assertTrue(a !== b)
    }

    @Test
    fun `worldToFile works at every SH degree and on an empty cloud`() {
        for (d in 0..3) {
            val c = seededCloud(Random(d), count = 4, shDegree = d)
            assertArrayEquals(snapshot(c), snapshot(fc.fileToWorld(fc.worldToFile(c))))
        }
        val empty = seededCloud(Random(0), count = 0, shDegree = 2)
        assertEquals(0, fc.worldToFile(empty).count)
    }

    @Test
    fun `array conversions do not mutate their input`() {
        val pos = floatArrayOf(1f, 2f, 3f)
        val rot = floatArrayOf(1f, 2f, 3f, 4f)
        val sh = FloatArray(9) { it + 1f }
        fc.positionsWorldToFile(pos); fc.rotationsWorldToFile(rot); fc.shRestWorldToFile(sh, 1)
        assertArrayEquals(floatArrayOf(1f, 2f, 3f), pos)
        assertArrayEquals(floatArrayOf(1f, 2f, 3f, 4f), rot)
        assertArrayEquals(FloatArray(9) { it + 1f }, sh)
    }

    @Test
    fun `shRest of the wrong length is rejected`() {
        assertThrows<IllegalArgumentException> { fc.shRestWorldToFile(FloatArray(10), 1) }
        assertThrows<IllegalArgumentException> { fc.shRestWorldToFile(FloatArray(3), 0) }
    }

    @ParameterizedTest(name = "sh{0} sign {1}")
    @MethodSource("shSigns")
    fun `each SH sign of ADR 0003's table`(index: Int, sign: Float) {
        // One splat at degree 3, only coefficient sh<index> set, in every channel.
        val k = 15
        val rest = FloatArray(3 * k)
        for (c in 0 until 3) rest[c * k + index - 1] = 0.75f
        val out = fc.shRestWorldToFile(rest, 3)
        for (c in 0 until 3) for (j in 0 until k) {
            val expected = if (j == index - 1) sign * 0.75f else 0f
            // Delta 0 so 0 and -0 compare equal; any nonzero difference still fails.
            assertEquals(expected, out[c * k + j], 0f, "channel $c coefficient sh${j + 1}")
        }
        assertEquals(sign, fc.shRestSigns(3)[index - 1])
    }

    // ---- helpers ----

    private fun randomPose(rnd: Random): CameraPose = CameraPose(
        Quat(rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f)
            .normalized(),
        Vec3(rnd.nextFloat() * 4 - 2, rnd.nextFloat() * 4 - 2, rnd.nextFloat() * 4 - 2),
    )

    private fun arrays(c: SplatCloud) =
        listOf(c.positions, c.logScales, c.rotations, c.opacityLogits, c.shDc, c.shRest)

    private fun snapshot(c: SplatCloud): FloatArray = arrays(c).fold(FloatArray(0)) { a, b -> a + b }

    companion object {
        @JvmStatic
        fun shSigns(): Stream<Arguments> = listOf(
            // degree 1: y, z, x
            -1f, -1f, 1f,
            // degree 2: xy, yz, 3z^2-1, xz, x^2-y^2
            -1f, 1f, 1f, -1f, 1f,
            // degree 3
            -1f, 1f, -1f, -1f, 1f, -1f, 1f,
        ).mapIndexed { i, s -> Arguments.of(i + 1, s) }.stream()

        fun seededCloud(rnd: Random, count: Int, shDegree: Int): SplatCloud {
            val k = SplatCloud.restCoefficients(shDegree)
            fun arr(n: Int) = FloatArray(n) { rnd.nextFloat() * 2f - 1f }
            return SplatCloud(
                count, shDegree, arr(3 * count), arr(3 * count),
                FloatArray(4 * count) { rnd.nextFloat() * 2f - 1f + if (it % 4 == 0) 3f else 0f },
                arr(count), arr(3 * count), arr(count * 3 * k),
            )
        }
    }
}
