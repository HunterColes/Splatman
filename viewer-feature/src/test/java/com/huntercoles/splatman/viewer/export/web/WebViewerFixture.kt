package com.huntercoles.splatman.viewer.export.web

import com.huntercoles.splatman.splat.io.splat.SplatCodec
import com.huntercoles.splatman.splat.model.SplatCloud
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Test-only page builder for assets/web/viewer.html (#419): fills the three markers the way the Kotlin writer will
 * (next card), so a real page can be opened in a browser. Not the writer: no HTML escaping beyond the title's
 * `<`, `&` and `>`, no size limits.
 */
object WebViewerFixture {
    const val TITLE = "__SPLATMAN_TITLE__"
    const val VIEW = "__SPLATMAN_VIEW__"
    const val DATA = "__SPLATMAN_DATA__"

    /** The repository root, found by walking up from the test's working directory. */
    fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile
        return checkNotNull(dir) { "settings.gradle.kts not found above ${System.getProperty("user.dir")}" }
    }

    fun template(): String = File(repoRoot(), "viewer-feature/src/main/assets/web/viewer.html").readText()

    /** A page for [cloud] (frame W; SplatCodec writes it in F) framed from its own bounds, yaw 35, pitch 20. */
    fun page(cloud: SplatCloud, title: String): String = pageFromRecords(SplatCodec.write(cloud), title)

    /** A page for SplatCodec's raw 32-byte [records] (frame F). */
    fun pageFromRecords(records: ByteArray, title: String): String = template()
        .replace(TITLE, title.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"))
        .replace(VIEW, viewJson(records))
        .replace(DATA, Base64.getEncoder().encodeToString(records))

    /** The start view in the payload's frame F: centroid target, the sphere filling 0.7 of the short edge
     * (the radius as the page's own fallback: 1.5 x RMS spread, at least 3 x the largest scale). */
    fun viewJson(records: ByteArray, fovY: Double = 60.0): String {
        val n = records.size / SplatCodec.BYTES_PER_SPLAT
        val buf = ByteBuffer.wrap(records).order(ByteOrder.LITTLE_ENDIAN)
        val p = DoubleArray(3 * n)
        val c = DoubleArray(3)
        var maxScale = 0.0
        for (i in 0 until n) for (a in 0 until 3) {
            p[3 * i + a] = buf.getFloat(i * SplatCodec.BYTES_PER_SPLAT + 4 * a).toDouble()
            c[a] += p[3 * i + a] / n
            maxScale = maxOf(maxScale, buf.getFloat(i * SplatCodec.BYTES_PER_SPLAT + 12 + 4 * a).toDouble())
        }
        var sq = 0.0
        for (i in 0 until n) for (a in 0 until 3) sq += (p[3 * i + a] - c[a]).let { it * it }
        val radius = maxOf(1.5 * sqrt(sq / maxOf(n, 1)), 3 * maxScale, 0.05)
        val distance = radius / (0.7 * tan(Math.toRadians(fovY / 2)))
        return """{"target":[${c[0]},${c[1]},${c[2]}],"distance":$distance,"yaw":35,"pitch":20,"fovY":$fovY}"""
    }
}
