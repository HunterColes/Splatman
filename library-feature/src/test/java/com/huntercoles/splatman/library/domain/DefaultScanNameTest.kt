package com.huntercoles.splatman.library.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

/**
 * #426: copy.md `scan_default_name`. The patterns are what `DateFormat.getBestDateTimePattern` gives for each
 * locale's `dMMM` and `Hm`/`hm` skeletons on Android 14.
 */
class DefaultScanNameTest {

    private val zone = ZoneId.of("Europe/London")

    private fun namer(date: String, time: String, locale: Locale) =
        DefaultScanName(TEMPLATE, date, time, locale, zone)

    @Test
    fun `en-GB 24 h reads Scan 4 Nov, 07 30`() {
        assertEquals("Scan 4 Nov, 07:30", namer("d MMM", "HH:mm", Locale.UK).name(ID, 0))
    }

    @Test
    fun `en-US 12 h reads Scan Nov 4, 7 30 AM`() {
        assertEquals("Scan Nov 4, 7:30 AM", namer("MMM d", "h:mm a", Locale.US).name(ID, 0))
    }

    @Test
    fun `an id that is not a scan stamp falls back to createdAtMs`() {
        val createdAtMs = LocalDateTime.of(2026, 1, 2, 21, 40).atZone(zone).toInstant().toEpochMilli()
        val gb = namer("d MMM", "HH:mm", Locale.UK)

        assertEquals("Scan 2 Jan, 21:40", gb.name("my folder", createdAtMs))
        assertEquals("Scan 2 Jan, 21:40", gb.name("scan_2026110_073012", createdAtMs))
        assertEquals("Scan 2 Jan, 21:40", gb.name("scan_20261104_073012_copy", createdAtMs))
        assertEquals("Scan 2 Jan, 21:40", gb.name("0b6f7c1e-3f1a-4b8e-9c55-2b1f0a7d9e10", createdAtMs))
    }

    @Test
    fun `an id with an impossible date or time falls back and never throws`() {
        assertNull(DefaultScanName.startOf("scan_20261304_073012"))
        assertNull(DefaultScanName.startOf("scan_20260230_073012"))
        assertNull(DefaultScanName.startOf("scan_20261104_250000"))
        assertEquals("Scan 1 Jan, 01:00", namer("d MMM", "HH:mm", Locale.UK).name("scan_20261304_073012", 0))
    }

    @Test
    fun `a pattern java time cannot read falls back to a plain stamp`() {
        assertEquals("Scan 2026-11-04 07:30", namer("d MMM {", "HH:mm", Locale.UK).name(ID, 0))
    }

    @Test
    fun `Arabic right to left formats without a crash`() {
        val ar = Locale.forLanguageTag("ar")
        val name = namer("d MMM", "h:mm a", ar).name(ID, 0)

        assertTrue(name.startsWith("Scan "), name)
        assertTrue(name.length > "Scan ".length + 4, name)
    }

    @Test
    fun `a long Finnish month formats without a crash`() {
        val fi = Locale.forLanguageTag("fi")
        val name = namer("d. MMMM", "H.mm", fi).name(ID, 0)

        assertEquals("Scan 4. marraskuuta, 7.30", name)
    }

    private companion object {
        const val TEMPLATE = "Scan %1\$s"
        const val ID = "scan_20261104_073012"
    }
}
