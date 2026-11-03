package com.huntercoles.splatman.splat.sfm

import com.huntercoles.splatman.splat.model.CameraIntrinsics
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Ground truth and a start for the bundle adjustment tests (#227), OpenCV axes throughout (ADR 0003): x right, y down,
 * z forward, world-to-camera `x_c = R x_w + t`, `u = fx x/z + cx`, `v = fy y/z + cy`, quaternions w, x, y, z
 * (Hamilton). Double maths; the model stores Floats as the native side will.
 *
 * Cameras sit on a wavy ring of radius about 4 around the origin, each looking near it; even frames use [BA_K_EVEN],
 * odd frames [BA_K_ODD], so a solver that ignores the frames' own intrinsics cannot fit. Points are uniform in the
 * cube [-1, 1]^3. A point's id is carried in its colour ([baColourId]) so it survives the solver's re-indexing.
 */
class BaScene(
    val frames: List<SfmFrame>,
    /** R_Ccv_W row-major (9), null where the frame is unregistered. */
    val trueR: Array<DoubleArray?>,
    val trueT: Array<DoubleArray?>,
    val truePoints: Array<DoubleArray>,
    /** The start: perturbed cameras and points, noisy (and maybe gross) observations; unregistered slots are zero. */
    val model: SparseModel,
    /** Per observation of [model]: true where the observation is a planted gross outlier. */
    val gross: BooleanArray,
    /** Ids at or above this are the two-observation points whose second observation is gross. */
    val pairPointsFrom: Int,
) {
    val firstRegistered: Int get() = model.registered.indexOfFirst { it }
    val secondRegistered: Int get() = (firstRegistered + 1 until model.frameCount).first { model.registered[it] }
    fun trueCentre(f: Int): DoubleArray = baCentre(trueR[f]!!, trueT[f]!!)
}

val BA_K_EVEN = CameraIntrinsics(500f, 500f, 320f, 240f, 640, 480)
val BA_K_ODD = CameraIntrinsics(620f, 615f, 330f, 236f, 640, 480)

/**
 * [cameras] frames, [points] points seen by every registered camera they project into ([window] 0), or by the
 * [window] consecutive cameras starting at `id * cameras / points`. Observations get Gaussian [noisePx] per axis; a
 * [grossFrac] share of them is replaced by a uniform pixel at least 20 px from the truth. [pairPoints] extra points
 * are seen by exactly two registered cameras, the second observation shifted 40 px in v (across the ring's epipolar
 * lines, so moving the point cannot hide it). The start rotates every registered camera but the first by [rotDeg]
 * about a random axis, shifts its t by [transFrac] |t| and every point by [pointFrac] (the cube's half size is 1).
 */
fun baScene(
    cameras: Int = 20,
    points: Int = 500,
    noisePx: Double = 0.5,
    rotDeg: Double = 2.0,
    transFrac: Double = 0.05,
    pointFrac: Double = 0.05,
    grossFrac: Double = 0.0,
    window: Int = 0,
    unregistered: Set<Int> = emptySet(),
    pairPoints: Int = 0,
    seed: Long = 227L,
): BaScene {
    val rng = Random(seed)
    val frames = List(cameras) { i ->
        SfmFrame(i, i * 33_000_000L, "f$i.jpg", if (i % 2 == 0) BA_K_EVEN else BA_K_ODD)
    }
    val registered = BooleanArray(cameras) { it !in unregistered }
    val regList = (0 until cameras).filter { registered[it] }
    require(regList.size >= 2) { "need two registered cameras" }
    val trueR = arrayOfNulls<DoubleArray>(cameras)
    val trueT = arrayOfNulls<DoubleArray>(cameras)
    for (i in 0 until cameras) {
        val th = 2 * PI * i / cameras
        val r = 4.0 + 0.3 * sin(3 * th)
        val c = doubleArrayOf(r * cos(th), -0.5 + 0.3 * cos(2 * th), r * sin(th))
        val target = doubleArrayOf(uniform(rng, 0.2), uniform(rng, 0.2), uniform(rng, 0.2))
        val rot = lookAt(c, target)
        if (registered[i]) {
            trueR[i] = rot
            trueT[i] = mulMat(rot, c).also { for (k in 0..2) it[k] = -it[k] }
        }
    }

    val total = points + pairPoints
    val truePoints = Array(total) { doubleArrayOf(uniform(rng, 1.0), uniform(rng, 1.0), uniform(rng, 1.0)) }
    val obsF = ArrayList<Int>()
    val obsP = ArrayList<Int>()
    val uv = ArrayList<Double>()
    val trueUv = ArrayList<Double>()
    val gross = ArrayList<Boolean>()
    fun add(f: Int, p: Int, tu: Double, tv: Double, u: Double, v: Double, isGross: Boolean) {
        obsF += f; obsP += p; trueUv += tu; trueUv += tv; uv += u; uv += v; gross += isGross
    }
    for (j in 0 until points) {
        val cams = if (window == 0) regList else (0 until window).map { (j * cameras / points + it) % cameras }
        for (f in cams) {
            if (!registered[f]) continue
            val px = baProject(trueR[f]!!, trueT[f]!!, frames[f].intrinsics, truePoints[j]) ?: continue
            add(f, j, px[0], px[1], px[0] + noisePx * rng.nextGaussian(), px[1] + noisePx * rng.nextGaussian(), false)
        }
    }
    for (j in points until total) {
        val a = regList[(j * 7) % regList.size]
        val b = regList[((j * 7) % regList.size + 1) % regList.size]
        while (true) {
            val pa = baProject(trueR[a]!!, trueT[a]!!, frames[a].intrinsics, truePoints[j])
            val pb = baProject(trueR[b]!!, trueT[b]!!, frames[b].intrinsics, truePoints[j])
            if (pa != null && pb != null) {
                add(a, j, pa[0], pa[1], pa[0] + noisePx * rng.nextGaussian(), pa[1] + noisePx * rng.nextGaussian(), false)
                val shift = if (pb[1] + 40 < frames[b].intrinsics.height) 40.0 else -40.0
                add(b, j, pb[0], pb[1], pb[0] + noisePx * rng.nextGaussian(), pb[1] + shift, true)
                break
            }
            truePoints[j] = doubleArrayOf(uniform(rng, 1.0), uniform(rng, 1.0), uniform(rng, 1.0))
        }
    }
    val regular = (0 until obsF.size).filter { obsP[it] < points }.toMutableList()
    java.util.Collections.shuffle(regular, rng)
    for (o in regular.take((grossFrac * regular.size).roundToInt())) {
        val k = frames[obsF[o]].intrinsics
        var u: Double
        var v: Double
        do {
            u = rng.nextDouble() * k.width
            v = rng.nextDouble() * k.height
        } while (hypot(u - trueUv[2 * o], v - trueUv[2 * o + 1]) < 20.0)
        uv[2 * o] = u
        uv[2 * o + 1] = v
        gross[o] = true
    }

    val rot = FloatArray(4 * cameras)
    val trans = FloatArray(3 * cameras)
    val first = regList[0]
    for (f in regList) {
        var r = trueR[f]!!
        val t = trueT[f]!!.copyOf()
        if (f != first) {
            r = mulMatMat(rodrigues(randomUnit(rng), rotDeg * PI / 180), r)
            val n = norm(t)
            val d = randomUnit(rng)
            for (k in 0..2) t[k] += d[k] * transFrac * n
        }
        val q = matToQuat(r)
        for (k in 0..3) rot[4 * f + k] = q[k].toFloat()
        for (k in 0..2) trans[3 * f + k] = t[k].toFloat()
    }
    val pts = FloatArray(3 * total)
    val colours = ByteArray(3 * total)
    for (j in 0 until total) {
        val d = randomUnit(rng)
        for (k in 0..2) pts[3 * j + k] = (truePoints[j][k] + d[k] * pointFrac).toFloat()
        colours[3 * j] = (j shr 16).toByte()
        colours[3 * j + 1] = (j shr 8).toByte()
        colours[3 * j + 2] = j.toByte()
    }
    val model = SparseModel(
        registered, rot, trans, pts, colours, obsF.toIntArray(), obsP.toIntArray(),
        FloatArray(uv.size) { uv[it].toFloat() },
    )
    return BaScene(frames, trueR, trueT, truePoints, model, gross.toBooleanArray(), points)
}

/** The point id carried in the colour of point [p] of [model]. */
fun baColourId(model: SparseModel, p: Int): Int =
    ((model.colours[3 * p].toInt() and 0xFF) shl 16) or
        ((model.colours[3 * p + 1].toInt() and 0xFF) shl 8) or
        (model.colours[3 * p + 2].toInt() and 0xFF)

/** Pixel of [p] in a camera, or null when behind it (z < 0.1) or outside the image. */
fun baProject(r: DoubleArray, t: DoubleArray, k: CameraIntrinsics, p: DoubleArray): DoubleArray? {
    val c = mulMat(r, p)
    for (i in 0..2) c[i] += t[i]
    if (c[2] < 0.1) return null
    val u = k.fx * c[0] / c[2] + k.cx
    val v = k.fy * c[1] / c[2] + k.cy
    if (u < 0 || v < 0 || u >= k.width || v >= k.height) return null
    return doubleArrayOf(u, v)
}

/** R of the model's frame [f] (quaternion normalised in Double), row-major. */
fun baRotation(model: SparseModel, f: Int): DoubleArray {
    val q = DoubleArray(4) { model.rotationsCcvW[4 * f + it].toDouble() }
    val n = sqrt(q.sumOf { it * it })
    return quatToMat(DoubleArray(4) { q[it] / n })
}

fun baTranslation(model: SparseModel, f: Int): DoubleArray = DoubleArray(3) { model.translationsCcvW[3 * f + it].toDouble() }

fun baPoint(model: SparseModel, p: Int): DoubleArray = DoubleArray(3) { model.points[3 * p + it].toDouble() }

/** Camera centre `-R^T t`. */
fun baCentre(r: DoubleArray, t: DoubleArray): DoubleArray =
    DoubleArray(3) { i -> -(r[i] * t[0] + r[3 + i] * t[1] + r[6 + i] * t[2]) }

fun baModelCentre(model: SparseModel, f: Int): DoubleArray = baCentre(baRotation(model, f), baTranslation(model, f))

/** Reprojection error in pixels of every observation of [model]; +inf for a point behind its camera. */
fun baErrors(model: SparseModel, frames: List<SfmFrame>): DoubleArray {
    val rs = arrayOfNulls<DoubleArray>(model.frameCount)
    return DoubleArray(model.observationCount) { o ->
        val f = model.obsFrame[o]
        val r = rs[f] ?: baRotation(model, f).also { rs[f] = it }
        val t = baTranslation(model, f)
        val c = mulMat(r, baPoint(model, model.obsPoint[o]))
        for (i in 0..2) c[i] += t[i]
        if (c[2] <= 0) {
            Double.POSITIVE_INFINITY
        } else {
            val k = frames[f].intrinsics
            hypot(k.fx * c[0] / c[2] + k.cx - model.obsUv[2 * o], k.fy * c[1] / c[2] + k.cy - model.obsUv[2 * o + 1])
        }
    }
}

fun baMean(x: DoubleArray): Double = x.sum() / x.size

fun baMedian(x: DoubleArray): Double {
    val s = x.sortedArray()
    val n = s.size
    return if (n % 2 == 1) s[n / 2] else 0.5 * (s[n / 2 - 1] + s[n / 2])
}

/**
 * Largest distance between the model's registered camera centres, after the best similarity onto the truth (Horn's
 * quaternion method), and the truth, as a fraction of the RMS distance of the true centres from their centroid.
 */
fun baCentreErrorAfterSimilarity(model: SparseModel, scene: BaScene): Double {
    val reg = (0 until model.frameCount).filter { model.registered[it] }
    val a = reg.map { baModelCentre(model, it) }
    val b = reg.map { scene.trueCentre(it) }
    val ca = DoubleArray(3) { k -> a.sumOf { it[k] } / a.size }
    val cb = DoubleArray(3) { k -> b.sumOf { it[k] } / b.size }
    val ac = a.map { p -> DoubleArray(3) { p[it] - ca[it] } }
    val bc = b.map { p -> DoubleArray(3) { p[it] - cb[it] } }
    val s = Array(3) { r -> DoubleArray(3) { c -> ac.indices.sumOf { ac[it][r] * bc[it][c] } } }
    val n = arrayOf(
        doubleArrayOf(s[0][0] + s[1][1] + s[2][2], s[1][2] - s[2][1], s[2][0] - s[0][2], s[0][1] - s[1][0]),
        doubleArrayOf(s[1][2] - s[2][1], s[0][0] - s[1][1] - s[2][2], s[0][1] + s[1][0], s[2][0] + s[0][2]),
        doubleArrayOf(s[2][0] - s[0][2], s[0][1] + s[1][0], -s[0][0] + s[1][1] - s[2][2], s[1][2] + s[2][1]),
        doubleArrayOf(s[0][1] - s[1][0], s[2][0] + s[0][2], s[1][2] + s[2][1], -s[0][0] - s[1][1] + s[2][2]),
    )
    val shift = n.maxOf { row -> row.sumOf { abs(it) } }
    var q = doubleArrayOf(1.0, 0.1, 0.2, 0.3)
    repeat(3000) {
        val next = DoubleArray(4) { i -> (0..3).sumOf { j -> n[i][j] * q[j] } + shift * q[i] }
        val len = sqrt(next.sumOf { it * it })
        q = DoubleArray(4) { next[it] / len }
    }
    val r = quatToMat(q)
    val ra = ac.map { mulMat(r, it) }
    val scale = ac.indices.sumOf { i -> (0..2).sumOf { ra[i][it] * bc[i][it] } } / ac.sumOf { p -> p.sumOf { it * it } }
    val spread = sqrt(bc.sumOf { p -> p.sumOf { it * it } } / bc.size)
    return ac.indices.maxOf { i -> sqrt((0..2).sumOf { k -> (scale * ra[i][k] - bc[i][k]).let { it * it } }) } / spread
}

/** Angle in radians between two rotation matrices. */
fun baRotationAngle(a: DoubleArray, b: DoubleArray): Double {
    var tr = 0.0
    for (i in 0..2) for (k in 0..2) tr += a[3 * i + k] * b[3 * i + k]
    return kotlin.math.acos(((tr - 1) / 2).coerceIn(-1.0, 1.0))
}

private fun uniform(rng: Random, half: Double) = (rng.nextDouble() * 2 - 1) * half

private fun randomUnit(rng: Random): DoubleArray {
    val v = DoubleArray(3) { rng.nextGaussian() }
    val n = norm(v)
    return DoubleArray(3) { v[it] / n }
}

private fun norm(v: DoubleArray) = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])

private fun cross(a: DoubleArray, b: DoubleArray) =
    doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])

private fun unit(v: DoubleArray): DoubleArray = norm(v).let { n -> DoubleArray(3) { v[it] / n } }

/** R_Ccv_W of a camera at [c] looking at [target], image down along world +y. Rows: camera x, y, z in W. */
private fun lookAt(c: DoubleArray, target: DoubleArray): DoubleArray {
    val z = unit(DoubleArray(3) { target[it] - c[it] })
    val x = unit(cross(doubleArrayOf(0.0, 1.0, 0.0), z))
    val y = cross(z, x)
    return doubleArrayOf(x[0], x[1], x[2], y[0], y[1], y[2], z[0], z[1], z[2])
}

private fun mulMat(r: DoubleArray, v: DoubleArray) = DoubleArray(3) { i -> r[3 * i] * v[0] + r[3 * i + 1] * v[1] + r[3 * i + 2] * v[2] }

private fun mulMatMat(a: DoubleArray, b: DoubleArray) =
    DoubleArray(9) { idx -> val i = idx / 3; val j = idx % 3; (0..2).sumOf { a[3 * i + it] * b[3 * it + j] } }

private fun rodrigues(axis: DoubleArray, angle: Double): DoubleArray {
    val (x, y, z) = Triple(axis[0], axis[1], axis[2])
    val c = cos(angle)
    val s = sin(angle)
    val v = 1 - c
    return doubleArrayOf(
        c + x * x * v, x * y * v - z * s, x * z * v + y * s,
        y * x * v + z * s, c + y * y * v, y * z * v - x * s,
        z * x * v - y * s, z * y * v + x * s, c + z * z * v,
    )
}

private fun quatToMat(q: DoubleArray): DoubleArray {
    val (w, x, y, z) = q
    return doubleArrayOf(
        1 - 2 * (y * y + z * z), 2 * (x * y - w * z), 2 * (x * z + w * y),
        2 * (x * y + w * z), 1 - 2 * (x * x + z * z), 2 * (y * z - w * x),
        2 * (x * z - w * y), 2 * (y * z + w * x), 1 - 2 * (x * x + y * y),
    )
}

/** w, x, y, z of a rotation matrix (Shepperd). */
private fun matToQuat(m: DoubleArray): DoubleArray {
    val tr = m[0] + m[4] + m[8]
    val q = when {
        tr > 0 -> {
            val s = sqrt(tr + 1) * 2
            doubleArrayOf(s / 4, (m[7] - m[5]) / s, (m[2] - m[6]) / s, (m[3] - m[1]) / s)
        }
        m[0] > m[4] && m[0] > m[8] -> {
            val s = sqrt(1 + m[0] - m[4] - m[8]) * 2
            doubleArrayOf((m[7] - m[5]) / s, s / 4, (m[1] + m[3]) / s, (m[2] + m[6]) / s)
        }
        m[4] > m[8] -> {
            val s = sqrt(1 + m[4] - m[0] - m[8]) * 2
            doubleArrayOf((m[2] - m[6]) / s, (m[1] + m[3]) / s, s / 4, (m[5] + m[7]) / s)
        }
        else -> {
            val s = sqrt(1 + m[8] - m[0] - m[4]) * 2
            doubleArrayOf((m[3] - m[1]) / s, (m[2] + m[6]) / s, (m[5] + m[7]) / s, s / 4)
        }
    }
    val n = max(sqrt(q.sumOf { it * it }), 1e-300)
    return DoubleArray(4) { q[it] / n }
}
