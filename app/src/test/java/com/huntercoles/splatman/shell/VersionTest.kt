package com.huntercoles.splatman.shell

import com.huntercoles.splatman.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The version guard (#209): the app stays in 0.0.x until Hunter says otherwise (CLAUDE.md). */
class VersionTest {
    @Test
    fun versionNameStaysInZeroZeroX() {
        assertTrue(
            "versionName ${BuildConfig.VERSION_NAME} left 0.0.x; that is Hunter's call (CLAUDE.md)",
            Regex("""0\.0\.\d+""").matches(BuildConfig.VERSION_NAME),
        )
    }

    @Test
    fun versionCodeMatchesThePatch() {
        assertEquals(BuildConfig.VERSION_NAME.substringAfterLast('.').toInt(), BuildConfig.VERSION_CODE)
    }

    @Test
    fun gitHashIsAShortHashOrSource() {
        assertTrue(
            "GIT_HASH ${BuildConfig.GIT_HASH}",
            BuildConfig.GIT_HASH == "source" || Regex("[0-9a-f]{7,40}").matches(BuildConfig.GIT_HASH),
        )
    }

    @Test
    fun aboutShowsTheVersionAndTheHash() {
        assertEquals("0.0.2 (abc1234)", aboutVersion("0.0.2", "abc1234"))
        assertEquals("0.0.2 (source)", aboutVersion("0.0.2", "source"))
    }
}
