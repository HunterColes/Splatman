package com.huntercoles.splatman.viewer.presentation

/** What the Library knows about a scan made on this phone, for the viewer's title and details sheet. */
data class ScanRecord(
    val name: String,
    val createdAtMs: Long,
    val keyframeCount: Int,
    /** The size of the scan's `splat.ply`, or null when it cannot be read. */
    val sizeBytes: Long?,
)

/** The Library's scans as the viewer needs them; the app binds it to library-feature's ScanRepository. */
interface ViewerScanStore {
    suspend fun get(scanId: String): ScanRecord?

    suspend fun rename(scanId: String, name: String)

    suspend fun delete(scanId: String)
}

/** The one-time gesture hint's flag. */
interface ViewerHintStore {
    val hintSeen: Boolean

    fun markHintSeen()
}

/** The rename dialog's rules (docs/design/screens/viewer.md: max 80 characters, a counter past 60). */
object RenameRules {
    const val MAX_LENGTH = 80
    const val COUNTER_FROM = 60

    /** What the field keeps of [text]: at most [MAX_LENGTH] characters, no line breaks. */
    fun clip(text: String): String = text.replace('\n', ' ').replace('\r', ' ').take(MAX_LENGTH)

    /** Save is enabled for a name that is not blank once trimmed. */
    fun canSave(text: String): Boolean {
        val trimmed = text.trim()
        return trimmed.isNotEmpty() && trimmed.length <= MAX_LENGTH
    }

    fun showCounter(text: String): Boolean = text.length > COUNTER_FROM
}
