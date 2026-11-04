package com.huntercoles.splatman.viewer.export.web

import com.huntercoles.splatman.splat.io.ply.PlyReader
import com.huntercoles.splatman.splat.io.splat.SplatCodec
import com.huntercoles.splatman.splat.model.SplatCloud
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The offline web viewer template (#419): no network reference of any kind, and the three markers the writer
 * replaces each exactly once. Also writes fixture pages to viewer-feature/build/web-viewer/ for a browser check.
 */
class ViewerTemplateTest {

    private val template = WebViewerFixture.template()

    @Test
    fun `template references nothing outside itself`() {
        assertEquals(emptyList<String>(), networkReferences(template))
    }

    @Test
    fun `the scanner catches every forbidden form`() {
        val bad = listOf(
            "<a href=\"http://x\">", "see https:", "<script src=\"//cdn/x.js\">", "<img src='//x'>",
            "<link href = //x>", "fetch(url)", "new XMLHttpRequest()", "import('x')", "@import 'x';",
            "@font-face { }",
        )
        for (snippet in bad) assertTrue(networkReferences(snippet).isNotEmpty(), snippet)
        assertEquals(emptyList<String>(), networkReferences("// a comment\nvar a = b; // c"))
    }

    @Test
    fun `each marker appears exactly once`() {
        for (marker in MARKERS) assertEquals(1, Regex(Regex.escape(marker)).findAll(template).count(), marker)
    }

    @Test
    fun `markers sit where the writer expects them`() {
        assertTrue(template.contains("<title>${WebViewerFixture.TITLE}</title>"))
        assertTrue(template.contains("<script type=\"application/json\" id=\"splat-view\">${WebViewerFixture.VIEW}</script>"))
        assertTrue(
            template.contains(
                "<script type=\"application/octet-stream\" id=\"splat-data\">${WebViewerFixture.DATA}</script>",
            ),
        )
    }

    @Test
    fun `the page asks for WebGL 2 and has its loading, caption and attribution lines`() {
        assertTrue(template.contains("getContext(\"webgl2\""))
        assertTrue(template.contains("needs WebGL 2"))
        assertTrue(template.contains(">Loading<"))
        assertTrue(template.contains("Made with Splatman"))
        assertTrue(template.contains("document.title"))
        assertTrue(template.contains("antimatter15/splat (MIT"))
    }

    @Test
    fun `the depth sort runs on the main thread and only when the view has changed`() {
        assertTrue(template.contains("if (needsSort(m)) sortFor(m);"))
        assertFalse(template.contains("Worker("))
    }

    @Test
    fun `cactus page fills every marker and stays offline`() {
        val page = WebViewerFixture.page(cactus(), "Cactus")
        assertNoMarkers(page)
        assertEquals(emptyList<String>(), networkReferences(page))
        write("cactus.html", page)
    }

    @Test
    fun `one splat with an empty title still builds a whole page`() {
        val one = SplatCloud(
            1, 0, floatArrayOf(0f, 0f, 0f), floatArrayOf(-3f, -3f, -3f), floatArrayOf(1f, 0f, 0f, 0f),
            floatArrayOf(4f), floatArrayOf(1f, -1f, 0f), FloatArray(0),
        )
        val page = WebViewerFixture.page(one, "")
        assertNoMarkers(page)
        assertTrue(page.contains("<title></title>"))
        write("one-splat.html", page)
    }

    @Test
    fun `a 300k splat page builds`() {
        val base = SplatCodec.write(cactus())
        val baseCount = base.size / SplatCodec.BYTES_PER_SPLAT
        val records = ByteArray(BIG * SplatCodec.BYTES_PER_SPLAT)
        val out = ByteBuffer.wrap(records).order(ByteOrder.LITTLE_ENDIAN)
        val src = ByteBuffer.wrap(base).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until BIG) {
            val tile = i / baseCount
            val from = (i % baseCount) * SplatCodec.BYTES_PER_SPLAT
            val to = i * SplatCodec.BYTES_PER_SPLAT
            System.arraycopy(base, from, records, to, SplatCodec.BYTES_PER_SPLAT)
            out.putFloat(to, src.getFloat(from) + 0.6f * (tile % 6))
            out.putFloat(to + 8, src.getFloat(from + 8) + 0.6f * (tile / 6))
        }
        val page = WebViewerFixture.pageFromRecords(records, "300k")
        assertNoMarkers(page)
        write("big.html", page)
    }

    private fun cactus(): SplatCloud =
        File(WebViewerFixture.repoRoot(), "app/src/main/assets/samples/cactus-sh1.ply")
            .inputStream().buffered().use(PlyReader::read)

    private fun assertNoMarkers(page: String) {
        for (marker in MARKERS) assertFalse(page.contains(marker), marker)
    }

    private fun write(name: String, page: String) {
        val dir = File(WebViewerFixture.repoRoot(), "viewer-feature/build/web-viewer").apply { mkdirs() }
        File(dir, name).writeText(page)
    }

    companion object {
        private const val BIG = 300_000
        private val MARKERS = listOf(WebViewerFixture.TITLE, WebViewerFixture.VIEW, WebViewerFixture.DATA)

        private val FORBIDDEN = listOf(
            Regex("(?i)https?:"),
            Regex("(?i)\\b(src|href)\\s*=\\s*[\"']?\\s*//"),
            Regex("fetch\\("),
            Regex("XMLHttpRequest"),
            Regex("import\\("),
            Regex("(?i)@import"),
            Regex("(?i)@font-face"),
        )

        /** Every forbidden match in [text], as `pattern: match`. */
        fun networkReferences(text: String): List<String> =
            FORBIDDEN.flatMap { re -> re.findAll(text).map { "${re.pattern}: ${it.value}" }.toList() }
    }
}
