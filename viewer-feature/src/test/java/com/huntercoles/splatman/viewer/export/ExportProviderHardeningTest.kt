package com.huntercoles.splatman.viewer.export

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Hardening for #45: the share FileProvider exposes only the export cache folder, and no INTERNET permission. */
class ExportProviderHardeningTest {

    private val app = File(System.getProperty("user.dir")).resolve("../app/src/main")
    private val android = "http://schemas.android.com/apk/res/android"
    private val tools = "http://schemas.android.com/tools"

    private fun parse(file: File) = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(file).documentElement

    private fun Element.children(): List<Element> =
        (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>()

    @Test
    fun `the provider paths name only cache exports`() {
        val paths = parse(app.resolve("res/xml/export_paths.xml"))
        val entries = paths.children()
        assertEquals(1, entries.size, "one path entry")
        val only = entries.single()
        assertEquals("cache-path", only.tagName)
        assertEquals(AndroidExportTarget.DIR + "/", only.getAttribute("path"))
    }

    @Test
    fun `the provider is private, per-grant and uses the export paths`() {
        val manifest = parse(app.resolve("AndroidManifest.xml"))
        val application = manifest.children().single { it.tagName == "application" }
        val providers = application.children().filter { it.tagName == "provider" }
        assertEquals(1, providers.size)
        val p = providers.single()
        assertEquals("androidx.core.content.FileProvider", p.getAttributeNS(android, "name"))
        assertEquals("\${applicationId}" + AndroidExportTarget.AUTHORITY_SUFFIX, p.getAttributeNS(android, "authorities"))
        assertEquals("false", p.getAttributeNS(android, "exported"))
        assertEquals("true", p.getAttributeNS(android, "grantUriPermissions"))
        val meta = p.children().single { it.tagName == "meta-data" }
        assertEquals("@xml/export_paths", meta.getAttributeNS(android, "resource"))
    }

    @Test
    fun `no INTERNET permission is requested`() {
        val manifest = parse(app.resolve("AndroidManifest.xml"))
        val internet = manifest.children()
            .filter { it.tagName == "uses-permission" }
            .filter { it.getAttributeNS(android, "name") == "android.permission.INTERNET" }
        assertTrue(internet.all { it.getAttributeNS(tools, "node") == "remove" })
        assertFalse(internet.isEmpty(), "the explicit removal stays")
    }
}
