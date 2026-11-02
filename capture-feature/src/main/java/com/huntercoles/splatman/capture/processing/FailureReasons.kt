package com.huntercoles.splatman.capture.processing

import java.io.File
import java.io.IOException

/** Free bytes where a scan's splat is written; a seam so tests can run out of space. */
fun interface FreeSpace {
    fun bytes(folder: File): Long
}

/** [FreeSpace] from the file system. */
class FileSystemFreeSpace : FreeSpace {
    override fun bytes(folder: File): Long = folder.usableSpace
}

/** Space a run needs before it starts: a 100k-splat PLY is about 25 MB, plus the temp copy and headroom. */
const val MIN_FREE_BYTES: Long = 64L shl 20

/** Thrown before training when [FreeSpace] reports less than [MIN_FREE_BYTES]; [needBytes] is the shortfall. */
class LowStorageException(val needBytes: Long) : IOException("not enough free space: $needBytes bytes short")

/** Thrown when the bundle holds no usable keyframes. */
class NoFramesException(message: String) : IOException(message)

/** Maps a run's failure to the reason the user sees. Unknown errors keep the generic line (frames are kept). */
object FailureReasons {
    private val noSpace = listOf("ENOSPC", "No space left")
    private val noFrames = listOf("no keyframes", "no frames")
    private val tracking = listOf("tracking lost", "lost tracking")

    fun of(error: Throwable): FailureReason {
        val chain = generateSequence(error) { it.cause }.take(MAX_CAUSES).toList()
        return when {
            chain.any { it is OutOfMemoryError } -> FailureReason.MEMORY
            chain.any { it is LowStorageException || it.mentions(noSpace) } -> FailureReason.STORAGE
            chain.any { it is NoFramesException || it.mentions(noFrames) } -> FailureReason.FRAMES
            chain.any { it.mentions(tracking) } -> FailureReason.TRACKING
            else -> FailureReason.UNKNOWN
        }
    }

    /** The [ProcessingState.Failed] for [error]. */
    fun failed(error: Throwable): ProcessingState.Failed = ProcessingState.Failed(
        message = error.message ?: error.javaClass.simpleName,
        reason = of(error),
        needBytes = (error as? LowStorageException)?.needBytes ?: 0,
    )

    private fun Throwable.mentions(words: List<String>): Boolean {
        val text = message ?: return false
        return words.any { text.contains(it, ignoreCase = true) }
    }

    private const val MAX_CAUSES = 8
}
