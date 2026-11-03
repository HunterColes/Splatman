package com.huntercoles.splatman.splat.sfm.eval

/** One TUM trajectory line: timestamp in seconds, position in metres, unit quaternion (x, y, z, w). */
data class TumPose(
    val t: Double,
    val tx: Double,
    val ty: Double,
    val tz: Double,
    val qx: Double = 0.0,
    val qy: Double = 0.0,
    val qz: Double = 0.0,
    val qw: Double = 1.0,
)

/** TUM RGB-D trajectory format: `timestamp tx ty tz qx qy qz qw`, space separated, `#` starts a comment. */
object TumTrajectory {
    private val SEP = Regex("\\s+")

    fun read(text: String): List<TumPose> = text.lineSequence().mapIndexedNotNull { i, raw ->
        val line = raw.substringBefore('#').trim()
        if (line.isEmpty()) return@mapIndexedNotNull null
        val f = line.split(SEP).map {
            requireNotNull(it.toDoubleOrNull()) { "line ${i + 1}: not a number: '$it'" }
        }
        require(f.size == 8) { "line ${i + 1}: expected 8 fields, got ${f.size}" }
        TumPose(f[0], f[1], f[2], f[3], f[4], f[5], f[6], f[7])
    }.toList()

    fun write(poses: List<TumPose>, header: String? = "timestamp tx ty tz qx qy qz qw"): String = buildString {
        if (header != null) append("# ").append(header).append('\n')
        for (p in poses) {
            append(listOf(p.t, p.tx, p.ty, p.tz, p.qx, p.qy, p.qz, p.qw).joinToString(" "))
            append('\n')
        }
    }
}
