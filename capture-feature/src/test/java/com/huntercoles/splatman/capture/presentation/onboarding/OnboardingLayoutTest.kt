package com.huntercoles.splatman.capture.presentation.onboarding

import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The sideways rule is on the measured space (#167), so split screen and foldables follow what they get. */
class OnboardingLayoutTest {

    @Test
    fun aPhoneHeldSidewaysIsLandscape() {
        assertTrue(isLandscapeLayout(width = 640.dp, height = 360.dp))
        assertTrue(isLandscapeLayout(width = 915.dp, height = 412.dp))
    }

    @Test
    fun aPhoneHeldUprightIsNot() {
        assertFalse(isLandscapeLayout(width = 360.dp, height = 640.dp))
    }

    @Test
    fun aShortButNarrowSplitScreenHalfIsNot() {
        // A split-screen half that is short but not wider than tall keeps the stacked layout.
        assertFalse(isLandscapeLayout(width = 300.dp, height = 320.dp))
        assertFalse(isLandscapeLayout(width = 360.dp, height = 360.dp))
    }

    @Test
    fun aTabletOrUnfoldedFoldableSidewaysIsNot() {
        // Tall enough for the stacked layout, even though it is wider than tall.
        assertFalse(isLandscapeLayout(width = 1280.dp, height = 800.dp))
        assertFalse(isLandscapeLayout(width = 840.dp, height = 480.dp))
    }

    @Test
    fun justUnderTheHeightLimitIsLandscape() {
        assertTrue(isLandscapeLayout(width = 840.dp, height = 479.dp))
    }
}
