package com.huntercoles.splatman.viewer.export.web

import com.huntercoles.splatman.splat.io.splat.SplatCodec
import com.huntercoles.splatman.splat.math.Vec3
import com.huntercoles.splatman.splat.model.SplatCloud
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.Base64
import kotlin.coroutines.cancellation.CancellationException

class HtmlViewerExportTest {

    private val template = WebViewerFixture.template()
    private val export = HtmlViewerExport(template)
    private val view = HtmlViewerExport.StartView(Vec3(1f, 2f, -3f), 4.5f)

    @Test
    fun `one splat page is exactly the expected size and carries the codec bytes`() = roundTrip(cloud(1), "One")

    @Test
    fun `1000 splat page is exactly the expected size and carries the codec bytes`() = roundTrip(cloud(1000), "Desk plant")

    @Test
    fun `long non-ASCII title is counted in UTF-8 bytes`() =
        roundTrip(cloud(7), "Kaktus im Wohnzimmer, Grüße 植物 🌵 & <café> ".repeat(40))

    @Test
    fun `written page references nothing outside itself and fills every marker`() {
        val page = String(write(cloud(50), "Cactus"), Charsets.UTF_8)
        assertEquals(emptyList<String>(), ViewerTemplateTest.networkReferences(page))
        for (m in listOf(HtmlViewerExport.TITLE, HtmlViewerExport.VIEW, HtmlViewerExport.DATA)) {
            assertFalse(page.contains(m), m)
        }
    }

    @Test
    fun `a title that closes the title tag stays text`() {
        val title = "\"</title><script>alert(1)</script>"
        val page = String(write(cloud(3), title), Charsets.UTF_8)
        assertTrue(page.contains("<title>&quot;&lt;/title&gt;&lt;script&gt;alert(1)&lt;/script&gt;</title>"))
        assertFalse(page.contains("<script>alert"))
        assertEquals(1, Regex("</title>").findAll(page).count())
        assertEquals("&amp;&lt;&gt;&quot;&#39;", HtmlViewerExport.escape("&<>\"'"))
    }

    @Test
    fun `a title holding a marker is written once as text and the markers are not replaced again`() {
        val title = "${HtmlViewerExport.DATA} ${HtmlViewerExport.VIEW}"
        val c = cloud(10)
        val bytes = write(c, title)
        val page = String(bytes, Charsets.UTF_8)
        assertEquals(title, between(page, "<title>", "</title>"))
        assertEquals(1, Regex(HtmlViewerExport.DATA).findAll(page).count())
        assertEquals(1, Regex(HtmlViewerExport.VIEW).findAll(page).count())
        assertArrayEquals(SplatCodec.write(c), data(page))
        assertEquals(export.expectedBytes(c.count, title), bytes.size.toLong())
    }

    @Test
    fun `the view is fixed-width JSON in the payload frame`() {
        val page = String(write(cloud(2), "v"), Charsets.UTF_8)
        val json = between(page, "<script type=\"application/json\" id=\"splat-view\">", "</script>")
        assertEquals(HtmlViewerExport.VIEW_JSON_BYTES, json.length)
        val number = Regex("[ -]\\d\\.\\d{8}e[+-]\\d{2}")
        assertEquals(
            "{\"target\":[0,0,0],\"distance\":0,\"yaw\":0,\"pitch\":0,\"fovY\":0}",
            number.replace(json, "0"),
        )
        // W -> F: y and z negated (ADR 0003); then distance, yaw, pitch, fovY
        assertEquals(
            listOf(1.0, -2.0, 3.0, 4.5, 35.0, 20.0, 60.0),
            number.findAll(json).map { it.value.trim().toDouble() }.toList(),
        )
        val odd = HtmlViewerExport.StartView(Vec3(-1e-30f, Float.NaN, 3e38f), Float.POSITIVE_INFINITY, -123.456f).json()
        assertEquals(HtmlViewerExport.VIEW_JSON_BYTES, odd.length)
    }

    @Test
    fun `initial view frames the scene's sphere as the viewer does`() {
        val framing = com.huntercoles.splatman.viewer.rendering.gl.SceneFraming(Vec3(1f, 1f, 1f), 0.7f, 0f)
        val v = HtmlViewerExport.StartView.initial(framing)
        assertEquals(framing.target, v.targetW)
        assertEquals(0.7 / (0.7 * Math.tan(Math.toRadians(30.0))), v.distance.toDouble(), 1e-5)
    }

    @Test
    fun `a cancelled write stops at the stream and leaves it to the caller`() {
        var closed = false
        val out = object : OutputStream() {
            var written = 0
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
            override fun write(b: ByteArray, off: Int, len: Int) {
                if (written + len > template.length) throw CancellationException("stopped")
                written += len
            }
            override fun close() {
                closed = true
            }
        }
        assertThrows(CancellationException::class.java) { export.write(cloud(5000), "x", view, out) }
        assertFalse(closed)
        assertTrue(out.written < export.expectedBytes(5000, "x"))
    }

    @Test
    fun `at the cap the whole scan is written`() {
        val c = cloud(HtmlViewerExport.CAP)
        assertFalse(export.trimmed(c.count))
        assertEquals(HtmlViewerExport.CAP, export.kept(c.count))
        roundTrip(c, "cap")
    }

    @Test
    fun `one over the cap drops the faintest splat and keeps the order`() {
        val n = HtmlViewerExport.CAP + 1
        val faint = 12_345
        val c = cloud(n) { i -> if (i == faint) -6f else 2f }
        assertTrue(export.trimmed(n))
        assertEquals(HtmlViewerExport.CAP, export.kept(n))
        assertEquals(export.expectedBytes(HtmlViewerExport.CAP, "t"), export.expectedBytes(n, "t"))
        val bytes = write(c, "t")
        assertEquals(export.expectedBytes(n, "t"), bytes.size.toLong())
        val all = SplatCodec.write(c)
        val b = SplatCodec.BYTES_PER_SPLAT
        val expected = all.copyOfRange(0, faint * b) + all.copyOfRange((faint + 1) * b, all.size)
        assertArrayEquals(expected, data(String(bytes, Charsets.UTF_8)))
    }

    @Test
    fun `over the cap ties go to the lower index`() {
        val small = HtmlViewerExport(template, 3)
        val alphas = floatArrayOf(0f, 3f, 0f, 0f, 3f, 0f)
        val c = cloud(alphas.size) { alphas[it] }
        val out = ByteArrayOutputStream()
        small.write(c, "ties", view, out)
        val all = SplatCodec.write(c)
        val b = SplatCodec.BYTES_PER_SPLAT
        val expected = listOf(0, 1, 4).map { all.copyOfRange(it * b, (it + 1) * b) }.reduce(ByteArray::plus)
        assertArrayEquals(expected, data(out.toString(Charsets.UTF_8.name())))
        assertEquals(small.expectedBytes(c.count, "ties"), out.size().toLong())
    }

    private fun roundTrip(c: SplatCloud, title: String) {
        val bytes = write(c, title)
        assertEquals(export.expectedBytes(c.count, title), bytes.size.toLong())
        assertArrayEquals(SplatCodec.write(c), data(String(bytes, Charsets.UTF_8)))
    }

    private fun write(c: SplatCloud, title: String): ByteArray =
        ByteArrayOutputStream().also { export.write(c, title, view, it) }.toByteArray()

    private fun data(page: String): ByteArray =
        Base64.getDecoder().decode(between(page, "<script type=\"application/octet-stream\" id=\"splat-data\">", "</script>"))

    private fun between(page: String, start: String, end: String): String {
        val from = page.indexOf(start)
        assertTrue(from >= 0, start)
        val s = from + start.length
        return page.substring(s, page.indexOf(end, s))
    }

    private fun cloud(n: Int, opacity: (Int) -> Float = { (it % 9) - 4f }): SplatCloud = SplatCloud(
        n, 0,
        FloatArray(3 * n) { (it % 97) * 0.01f - 0.4f },
        FloatArray(3 * n) { -3f - (it % 5) * 0.1f },
        FloatArray(4 * n) { if (it % 4 == 0) 1f else (it % 7) * 0.05f },
        FloatArray(n) { opacity(it) },
        FloatArray(3 * n) { (it % 11) * 0.1f - 0.5f },
        FloatArray(0),
    )
}
