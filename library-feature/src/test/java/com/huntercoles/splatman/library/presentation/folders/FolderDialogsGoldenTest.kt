package com.huntercoles.splatman.library.presentation.folders

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextRange
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.huntercoles.splatman.core.design.SplatTheme
import com.huntercoles.splatman.library.presentation.ScreenGolden
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale
import java.util.TimeZone

/**
 * The folder dialogs and the Move sheet (#314, docs/design/screens/library.md "Folders: create, rename, move,
 * delete"): their behaviour, and goldens of each in the dark and light theme under
 * `src/test/screenshots/FolderDialogs/`. A dialog or sheet is its own window, so the goldens capture the whole screen
 * (every window) rather than ScreenGolden's single compose root; the rule, the pinned zone and locale and the settle
 * time are ScreenGolden's. The name dialog is drawn as its card over a scrim: Robolectric never goes idle with a
 * text field inside a dialog window.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [ScreenGolden.SDK], qualifiers = ScreenGolden.QUALIFIERS)
class FolderDialogsGoldenTest {

    @get:Rule
    val golden = ScreenGolden()

    private val compose get() = golden.compose

    // Behaviour: the name dialog.

    @Test
    fun `the new folder dialog is focused, empty, and Create waits for a name that is not blank`() {
        host(NameDialog(NameDialogMode.New, ""))
        compose.onNodeWithText("New folder").assertIsDisplayed()
        compose.onNodeWithText("Name").assertIsDisplayed()
        compose.onNodeWithTag(FOLDER_NAME_FIELD_TAG).assertIsFocused()
        compose.onNodeWithText("Create").assertIsNotEnabled()
        compose.onNodeWithTag(FOLDER_NAME_FIELD_TAG).performTextInput("   ")
        compose.onNodeWithText("Create").assertIsNotEnabled()
        compose.onNodeWithTag(FOLDER_NAME_FIELD_TAG).performTextReplacement("Garden")
        compose.onNodeWithText("Create").assertIsEnabled()
    }

    @Test
    fun `rename is titled Rename folder, prefilled with the whole name selected, and confirms with Rename`() {
        host(NameDialog(NameDialogMode.Rename("f1"), "Desk stuff"))
        compose.onNodeWithText("Rename folder").assertIsDisplayed()
        compose.onNodeWithText("Rename").assertIsEnabled()
        compose.onNodeWithTag(FOLDER_NAME_FIELD_TAG)
            .assertIsFocused()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(0, 10)))
    }

    @Test
    fun `the counter shows from 50 characters`() {
        host(NameDialog(NameDialogMode.New, "a".repeat(49)))
        compose.onNodeWithText("49/60").assertDoesNotExist()
        compose.onNodeWithTag(FOLDER_NAME_FIELD_TAG).performTextInput("b")
        compose.onNodeWithText("50/60").assertIsDisplayed()
    }

    @Test
    fun `typing stops at 60 characters`() {
        val typed = host(NameDialog(NameDialogMode.New, "a".repeat(58)))
        compose.onNodeWithTag(FOLDER_NAME_FIELD_TAG).performTextInput("bcdef")
        assertEquals("a".repeat(58) + "bc", typed.last())
        compose.onNodeWithText("60/60").assertIsDisplayed()
    }

    @Test
    fun `a cut name never splits an emoji`() {
        val name = "🌱".repeat(61)
        val cut = capName(name)
        assertEquals(60, cut.codePointCount(0, cut.length))
        assertEquals("🌱".repeat(60), cut)
        assertEquals("short 🌱", capName("short 🌱"))
    }

    @Test
    fun `a taken name is the field's error`() {
        host(NameDialog(NameDialogMode.New, "Garden", error = NameError.Taken))
        compose.onNodeWithText("You already have a folder called", substring = true).assertIsDisplayed()
        compose.onNodeWithTag(FOLDER_NAME_FIELD_TAG).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
    }

    // Behaviour: the delete confirm.

    @Test
    fun `delete says where the scans go, or that the folder is empty`() {
        var confirmed = false
        var showing by mutableStateOf(DeleteConfirm("f1", "Desk stuff", 3))
        compose.setContent {
            SplatTheme { DeleteFolderDialog(showing, onConfirm = { confirmed = true }, onDismiss = {}) }
        }
        compose.onNodeWithText("Delete the folder", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Its 3 scans move back to Library. Nothing is deleted from your phone.").assertIsDisplayed()
        showing = DeleteConfirm("f1", "Desk stuff", 1)
        compose.onNodeWithText("Its scan moves back to Library. Nothing is deleted from your phone.").assertIsDisplayed()
        showing = DeleteConfirm("f1", "Desk stuff", 0)
        compose.onNodeWithText("It's empty, so nothing else changes.").assertIsDisplayed()
        compose.onNodeWithText("Delete folder").performClick()
        assertEquals(true, confirmed)
    }

    // Behaviour: the Move sheet.

    @Test
    fun `move rows go to their place, and the place every scan is in now is disabled and says so`() {
        val moved = mutableListOf<String?>()
        var newFolder = 0
        compose.setContent {
            SplatTheme {
                MoveToSheet(moveSheet(), onNewFolder = { newFolder++ }, onMoveTo = { moved += it }, onDismiss = {})
            }
        }
        compose.onNodeWithText("Move 3 scans to").assertIsDisplayed()
        compose.onNodeWithTag(moveRowTag("desk"))
            .assertIsNotEnabled()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Desk stuff, Here now")))
        compose.onNodeWithTag(moveRowTag("desk")).performClick()
        compose.onNodeWithText("5 scans").assertIsDisplayed()
        compose.onNodeWithText("Empty").assertIsDisplayed()
        compose.onNodeWithTag(moveRowTag("garden")).performClick()
        compose.onNodeWithTag(moveRowTag(null)).performClick()
        compose.onNodeWithTag(MOVE_ROW_NEW_TAG).performClick()
        assertEquals(listOf("garden", null), moved)
        assertEquals(1, newFolder)
        assertNull(moved.firstOrNull { it == "desk" })
    }

    // Goldens.

    @Test fun `name dialog empty dark`() = shot("name-empty", dark = true) { nameDialog(NameDialog(NameDialogMode.New, "")) }

    @Test fun `name dialog empty light`() = shot("name-empty", dark = false) { nameDialog(NameDialog(NameDialogMode.New, "")) }

    @Test fun `name dialog taken dark`() = shot("name-taken", dark = true) { nameDialog(TAKEN) }

    @Test fun `name dialog taken light`() = shot("name-taken", dark = false) { nameDialog(TAKEN) }

    @Test fun `name dialog 60 characters dark`() = shot("name-60", dark = true) { nameDialog(LONG) }

    @Test fun `name dialog 60 characters light`() = shot("name-60", dark = false) { nameDialog(LONG) }

    @Test fun `name dialog emoji dark`() = shot("name-emoji", dark = true) { nameDialog(EMOJI) }

    @Test fun `name dialog emoji light`() = shot("name-emoji", dark = false) { nameDialog(EMOJI) }

    @Test fun `delete dialog with scans dark`() = shot("delete-scans", dark = true) { deleteDialog(3) }

    @Test fun `delete dialog with scans light`() = shot("delete-scans", dark = false) { deleteDialog(3) }

    @Test fun `delete dialog empty dark`() = shot("delete-empty", dark = true) { deleteDialog(0) }

    @Test fun `delete dialog empty light`() = shot("delete-empty", dark = false) { deleteDialog(0) }

    @Test fun `move sheet dark`() = shot("move-sheet", dark = true) { moveToSheet() }

    @Test fun `move sheet light`() = shot("move-sheet", dark = false) { moveToSheet() }

    @Test fun `move sheet at font scale 200 percent dark`() = shot("move-sheet-font200", dark = true, fontScale = 2f) {
        moveToSheet()
    }

    @Test fun `move sheet at font scale 200 percent light`() = shot("move-sheet-font200", dark = false, fontScale = 2f) {
        moveToSheet()
    }

    @Test
    @Config(qualifiers = TABLET)
    fun `move dialog at 600 dp dark`() = shot("move-dialog", dark = true, size = "tablet-600x960") { moveToSheet() }

    @Test
    @Config(qualifiers = TABLET)
    fun `move dialog at 600 dp light`() = shot("move-dialog", dark = false, size = "tablet-600x960") { moveToSheet() }

    // Helpers.

    /** Shows the name dialog with its text held here, as the ViewModel would; returns every text it was sent. */
    private fun host(start: NameDialog): List<String> {
        val typed = mutableListOf<String>()
        compose.setContent {
            SplatTheme {
                var dialog by remember { mutableStateOf(start) }
                FolderNameCard(
                    dialog = dialog,
                    onTextChange = {
                        typed += it
                        dialog = dialog.copy(text = it, error = NameError.None)
                    },
                    onConfirm = {},
                    onDismiss = {},
                )
            }
        }
        compose.waitForIdle()
        return typed
    }

    @Composable
    private fun nameDialog(dialog: NameDialog) {
        // The card over the scrim a dialog window draws (FolderNameCard says why not the window itself).
        Box(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = SCRIM_ALPHA)),
            contentAlignment = Alignment.Center,
        ) {
            FolderNameCard(dialog, onTextChange = {}, onConfirm = {}, onDismiss = {})
        }
    }

    @Composable
    private fun deleteDialog(count: Int) =
        DeleteFolderDialog(DeleteConfirm("f1", "Desk stuff", count), onConfirm = {}, onDismiss = {})

    @Composable
    private fun moveToSheet() = MoveToSheet(moveSheet(), onNewFolder = {}, onMoveTo = {}, onDismiss = {})

    private fun moveSheet() = MoveSheet(
        scanIds = listOf("s1", "s2", "s3"),
        targets = listOf(
            MoveTarget(folderId = null, name = null, scanCount = 4, hereNow = false),
            MoveTarget(folderId = "desk", name = "Desk stuff", scanCount = 3, hereNow = true),
            MoveTarget(folderId = "empty", name = "Empty for now", scanCount = 0, hereNow = false),
            MoveTarget(folderId = "garden", name = "Garden", scanCount = 5, hereNow = false),
            MoveTarget(folderId = "rtl", name = RTL_NAME, scanCount = 1, hereNow = false),
        ),
    )

    /**
     * Renders [content] over an empty screen in the app theme and records or verifies the whole screen, every window,
     * as `FolderDialogs/FolderDialogs_<name>_<size>_<dark|light>.png`.
     */
    @OptIn(ExperimentalRoborazziApi::class)
    private fun shot(
        name: String,
        dark: Boolean,
        size: String = "phone-360x780",
        fontScale: Float = 1f,
        content: @Composable () -> Unit,
    ) {
        TimeZone.setDefault(TimeZone.getTimeZone(ScreenGolden.ZONE))
        Locale.setDefault(Locale.US)
        if (fontScale != 1f) {
            val resources = compose.activity.resources
            val configuration = resources.configuration.apply { this.fontScale = fontScale }
            @Suppress("DEPRECATION")
            resources.updateConfiguration(configuration, resources.displayMetrics)
        }
        // The test activity's own title bar is not part of any screen; the whole-screen capture would show it.
        compose.runOnUiThread { compose.activity.actionBar?.hide() }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            SplatTheme(darkTheme = dark) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {}
                content()
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(ScreenGolden.SETTLE_MS)
        compose.waitForIdle()
        val root = System.getProperty("roborazzi.output.dir") ?: "src/test/screenshots"
        val theme = if (dark) "dark" else "light"
        captureScreenRoboImage("$root/$SCREEN/${SCREEN}_${name}_${size}_$theme.png", OPTIONS)
    }

    private companion object {
        const val SCREEN = "FolderDialogs"
        const val SCRIM_ALPHA = 0.6f
        const val TABLET ="en-rUS-w600dp-h960dp-port-xhdpi"
        const val RTL_NAME = "حديقة البيت الخلفية مع الأشجار والزهور الكثيرة"
        val TAKEN = NameDialog(NameDialogMode.New, "Garden", error = NameError.Taken)
        val LONG = NameDialog(NameDialogMode.Rename("f1"), "Scans of the old wooden desk in the back room upstairs, 2026")
        val EMOJI = NameDialog(NameDialogMode.New, "🌱 Garden 🪴")
        val OPTIONS = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f))
    }
}
