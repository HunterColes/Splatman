package com.huntercoles.splatman.library.presentation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import com.huntercoles.splatman.library.domain.model.Scan
import com.huntercoles.splatman.library.domain.model.ScanStatus
import com.huntercoles.splatman.library.domain.repository.SampleRepository
import com.huntercoles.splatman.library.domain.repository.ScanRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.OutputStream
import java.util.Locale
import java.util.TimeZone

/** The Library's golden (#67): four scans, one in each card state, at one phone size, light and dark. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [ScreenGolden.SDK], qualifiers = ScreenGolden.QUALIFIERS)
class LibraryScreenGoldenTest {

    @get:Rule
    val golden = ScreenGolden()

    private val actions = LibraryActions(onOpen = {}, onScan = {}, onOpenFile = {}, onSettings = {}, onAbout = {})

    private fun viewModel() = LibraryViewModel(
        scanRepository = FixedScans(SCANS),
        sampleRepository = SampleRepository { emptyList() },
        buildProgress = BuildProgress { id -> flowOf(if (id == "b") BUILD_PERCENT else null) },
        scanSupport = ScanSupport { true },
        sizeText = SizeText { bytes -> String.format(Locale.US, "%.0f MB", bytes / 1e6) },
        scanSharer = ScanSharer { null },
    )

    private fun capture(dark: Boolean) =
        golden.capture("LibraryScreen", dark) {
            LibraryScreen(actions = actions, arUnsupportedTitle = "Can't scan on this phone", viewModel = viewModel())
        }

    @Test
    fun light() = capture(dark = false)

    @Test
    fun dark() = capture(dark = true)

    /**
     * Hardening: the cards' dates follow the JVM's time zone, so the golden pins it. The newest scan is 23:30 UTC on
     * 14 March, already 15 March on Kiritimati (UTC+14): without the pin this would draw "15 Mar" and fail.
     */
    @Test
    fun `the golden does not follow the machine's time zone`() {
        TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati"))
        capture(dark = false)
        golden.compose.onNodeWithText("14 Mar", substring = true).assertIsDisplayed()
    }

    /** Emits [scans] once; everything that writes is unused by the screen. */
    private class FixedScans(private val scans: List<Scan>) : ScanRepository {
        override fun scans(): Flow<List<Scan>> = flowOf(scans)

        override suspend fun get(id: String): Scan? = scans.firstOrNull { it.id == id }

        override suspend fun create(name: String, createdAtMs: Long): Scan = error("not used")

        override suspend fun import(
            name: String,
            createdAtMs: Long,
            splatCount: Int,
            writeSplat: (OutputStream) -> Unit,
        ): Scan = error("not used")

        override suspend fun updateStatus(id: String, status: ScanStatus, splatCount: Int?, keyframeCount: Int?) =
            error("not used")

        override suspend fun rename(id: String, name: String) = Unit

        override suspend fun delete(id: String) = Unit

        override suspend fun sync() = Unit

        override suspend fun refresh(id: String) = Unit
    }

    private companion object {
        /** 2026-03-14T23:30:00Z. */
        const val NEWEST_MS = 1_773_531_000_000L

        /** 2026-02-02T09:00:00Z. */
        const val OLDER_MS = 1_770_022_800_000L

        const val BUILD_PERCENT = 40

        val SCANS = listOf(
            Scan("a", "Kitchen table", NEWEST_MS, ScanStatus.READY, 182_340, 64, "a", sizeBytes = 38_000_000),
            Scan("b", "Garden gnome", NEWEST_MS - 3_600_000, ScanStatus.PROCESSING, 0, 48, "b"),
            Scan("c", "Bike", OLDER_MS, ScanStatus.CAPTURED, 0, 32, "c"),
            Scan(
                "d", "Measuring tape", OLDER_MS - 86_400_000, ScanStatus.READY, 54_120, 0, "d",
                imported = true, sizeBytes = 3_400_000,
            ),
            Scan("e", "Hallway", OLDER_MS - 2 * 86_400_000, ScanStatus.FAILED, 0, 12, "e"),
        )
    }
}
