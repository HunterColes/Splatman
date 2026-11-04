package com.huntercoles.splatman.viewer.export.web

import android.content.res.AssetManager
import com.huntercoles.splatman.splat.frames.FrameConversions
import com.huntercoles.splatman.splat.io.splat.SplatCodec
import com.huntercoles.splatman.splat.math.Vec3
import com.huntercoles.splatman.splat.model.SplatCloud
import com.huntercoles.splatman.viewer.rendering.GaussianCameraController
import com.huntercoles.splatman.viewer.rendering.ViewerConstants
import com.huntercoles.splatman.viewer.rendering.gl.SceneFraming
import timber.log.Timber
import java.io.IOException
import java.io.OutputStream
import java.util.Base64
import java.util.Locale
import kotlin.math.tan

/**
 * Writes a scan as one offline `.html` page (docs/design/screens/export.md, "Web page (.html)"): the template asset
 * (#419, `assets/web/viewer.html`) with its three markers filled by position, each once and never a second pass, so
 * a title holding a marker's text stays text. The title goes in HTML-escaped, the start view as fixed-width JSON in
 * the payload's frame F, and the scan as Base64 of [SplatCodec]'s records, encoded chunk by chunk.
 *
 * Over [CAP] splats the [CAP] most opaque are kept (the `.splat` alpha byte, descending, ties by the lower index)
 * and written in their original order; the scan itself is never changed.
 */
class HtmlViewerExport internal constructor(template: String, private val cap: Int) {

    constructor(template: String) : this(template, CAP)

    /** The template's text between the markers, in file order, and which marker follows each piece. */
    private val pieces: List<ByteArray>
    private val order: List<String>

    init {
        require(cap > 0) { "cap must be > 0, got $cap" }
        val at = MARKERS.associateWith { marker ->
            val first = template.indexOf(marker)
            require(first >= 0) { "template is missing $marker" }
            require(template.indexOf(marker, first + 1) < 0) { "template holds $marker more than once" }
            first
        }
        order = MARKERS.sortedBy { at.getValue(it) }
        val cuts = ArrayList<ByteArray>(order.size + 1)
        var from = 0
        for (marker in order) {
            val start = at.getValue(marker)
            cuts += template.substring(from, start).toByteArray(Charsets.UTF_8)
            from = start + marker.length
        }
        cuts += template.substring(from).toByteArray(Charsets.UTF_8)
        pieces = cuts
    }

    /** The template's UTF-8 bytes with every marker empty (`T` in export.md). */
    private val templateBytes: Long = pieces.sumOf { it.size.toLong() }

    /** Splats a page of a [count]-splat scan holds: all of them up to the cap, else the cap. */
    fun kept(count: Int): Int = minOf(count, cap)

    /** True when a [count]-splat scan is trimmed to the cap. */
    fun trimmed(count: Int): Boolean = count > cap

    /** The exact size in bytes of the page [write] makes for a [count]-splat scan named [title]. */
    fun expectedBytes(count: Int, title: String): Long {
        require(count >= 0) { "count must be >= 0, got $count" }
        val data = SplatCodec.BYTES_PER_SPLAT.toLong() * kept(count)
        return templateBytes + escape(title).toByteArray(Charsets.UTF_8).size + VIEW_JSON_BYTES + 4 * ((data + 2) / 3)
    }

    /**
     * Writes the page for [cloud] (frame W) named [title], opening at [view], to [out]. Blocking. [out] is the
     * caller's: it is neither flushed nor closed here, and when this throws (a write failure, or the caller's
     * stream throwing a CancellationException to stop it) the bytes already written are a partial page the caller
     * must delete (ExportUseCase.cleanUp), never hand on.
     */
    fun write(cloud: SplatCloud, title: String, view: StartView, out: OutputStream) {
        val records = SplatCodec.write(cloud)
        val keep = keepMask(records, cap)
        for ((i, marker) in order.withIndex()) {
            out.write(pieces[i])
            when (marker) {
                TITLE -> out.write(escape(title).toByteArray(Charsets.UTF_8))
                VIEW -> out.write(view.json().toByteArray(Charsets.UTF_8))
                DATA -> writeBase64(records, keep, out)
            }
        }
        out.write(pieces.last())
    }

    /**
     * The view the page opens at, as the Viewer's initial orbit view: [targetW] in the world frame W (converted to
     * F on write, ADR 0003), [distance] metres, [yawDeg] about up, [pitchDeg] looking down, [fovYDeg] vertical.
     */
    data class StartView(
        val targetW: Vec3,
        val distance: Float,
        val yawDeg: Float = GaussianCameraController.INITIAL_YAW_DEG,
        val pitchDeg: Float = GaussianCameraController.INITIAL_PITCH_DEG,
        val fovYDeg: Float = ViewerConstants.FOV_DEGREES,
    ) {
        /** Always [VIEW_JSON_BYTES] long: every number is printed in a fixed width, a non-finite one as 0. */
        fun json(): String {
            val t = FrameConversions.worldToFile(targetW)
            return "{\"target\":[${num(t.x)},${num(t.y)},${num(t.z)}],\"distance\":${num(distance)}," +
                "\"yaw\":${num(yawDeg)},\"pitch\":${num(pitchDeg)},\"fovY\":${num(fovYDeg)}}"
        }

        companion object {
            /** The Viewer's initial view of a scene framed as [framing]: the sphere filling [GaussianCameraController.FILL]
             * of the vertical field of view, as the page's own fallback frames it. */
            fun initial(framing: SceneFraming, fovYDeg: Float = ViewerConstants.FOV_DEGREES): StartView {
                val r = if (framing.radius.isFinite() && framing.radius > 0f) framing.radius else 1f
                val half = Math.toRadians(fovYDeg / 2.0)
                return StartView(framing.target, (r / (GaussianCameraController.FILL * tan(half))).toFloat(), fovYDeg = fovYDeg)
            }
        }
    }

    companion object {
        const val ASSET = "web/viewer.html"
        const val TITLE = "__SPLATMAN_TITLE__"
        const val VIEW = "__SPLATMAN_VIEW__"
        const val DATA = "__SPLATMAN_DATA__"
        private val MARKERS = listOf(TITLE, VIEW, DATA)

        /** Splats a page holds at most (export.md, "Cap"). */
        const val CAP = 600_000

        /** Width of one number in the view JSON: sign or space, d.dddddddd, e, sign, two digits. */
        private const val NUMBER_WIDTH = 15

        /** The view JSON's fixed length: its keys and punctuation plus seven numbers. */
        val VIEW_JSON_BYTES: Int = StartView(Vec3.ZERO, 0f, 0f, 0f, 0f).json().length

        /** Base64 chunk: a whole number of records and of 3-byte groups, so only the last chunk pads. */
        private const val CHUNK = 3 * SplatCodec.BYTES_PER_SPLAT * 512

        /** The `.splat` record's alpha byte. */
        private const val ALPHA = 27

        /** The template asset, or null when the build has none (the Web page row is then hidden). */
        fun fromAssets(assets: AssetManager): HtmlViewerExport? = try {
            HtmlViewerExport(assets.open(ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() })
        } catch (e: IOException) {
            Timber.w(e, "Web viewer template %s missing", ASSET)
            null
        }

        /** [text] safe as HTML text or attribute: `&`, `<`, `>`, `"` and `'` as character references. */
        fun escape(text: String): String = buildString(text.length) {
            for (c in text) when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(c)
            }
        }

        private fun num(v: Float): String {
            val s = String.format(Locale.ROOT, "% .8e", if (v.isFinite()) v.toDouble() else 0.0)
            check(s.length == NUMBER_WIDTH) { "view number $s is not $NUMBER_WIDTH wide" }
            return s
        }

        /** Which records to keep under [cap]: null keeps all; else the [cap] highest alpha bytes, ties by index. */
        internal fun keepMask(records: ByteArray, cap: Int): BooleanArray? {
            val n = records.size / SplatCodec.BYTES_PER_SPLAT
            if (n <= cap) return null
            val histogram = IntArray(256)
            for (i in 0 until n) histogram[alpha(records, i)]++
            var threshold = 255
            var above = 0
            while (above + histogram[threshold] < cap) above += histogram[threshold--]
            var atThreshold = cap - above
            return BooleanArray(n) { i ->
                val a = alpha(records, i)
                a > threshold || (a == threshold && atThreshold-- > 0)
            }
        }

        private fun alpha(records: ByteArray, i: Int): Int =
            records[i * SplatCodec.BYTES_PER_SPLAT + ALPHA].toInt() and 0xFF

        private fun writeBase64(records: ByteArray, keep: BooleanArray?, out: OutputStream) {
            val encoder = Base64.getEncoder()
            if (keep == null) {
                var from = 0
                while (from < records.size) {
                    val to = minOf(records.size, from + CHUNK)
                    out.write(encoder.encode(records.copyOfRange(from, to)))
                    from = to
                }
                return
            }
            val chunk = ByteArray(CHUNK)
            var filled = 0
            for (i in keep.indices) {
                if (!keep[i]) continue
                System.arraycopy(records, i * SplatCodec.BYTES_PER_SPLAT, chunk, filled, SplatCodec.BYTES_PER_SPLAT)
                filled += SplatCodec.BYTES_PER_SPLAT
                if (filled == CHUNK) {
                    out.write(encoder.encode(chunk))
                    filled = 0
                }
            }
            if (filled > 0) out.write(encoder.encode(chunk.copyOf(filled)))
        }
    }
}
