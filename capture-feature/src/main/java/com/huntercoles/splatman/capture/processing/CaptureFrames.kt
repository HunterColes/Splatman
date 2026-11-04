package com.huntercoles.splatman.capture.processing

import timber.log.Timber
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/** Settings > Keep capture frames (`SettingsPreferences.keepFrames`), read when a build finishes. */
fun interface KeepFrames {
    fun keep(): Boolean
}

/**
 * Removes a built scan's capture frames (`images/`, ADR 0007). Never throws: a failure is logged and whatever could
 * not be removed stays. Symbolic links are removed as links, never followed, so nothing outside `images/` is touched.
 */
object CaptureFrames {
    const val DIR = "images"

    /** Returns whether [scan] has no `images/` left afterwards. */
    fun delete(scan: File): Boolean {
        val images = File(scan, DIR).toPath()
        return try {
            when {
                Files.notExists(images, LinkOption.NOFOLLOW_LINKS) -> Unit
                Files.isDirectory(images, LinkOption.NOFOLLOW_LINKS) -> Files.walkFileTree(images, Remover)
                else -> Files.delete(images)
            }
            true
        } catch (e: IOException) {
            Timber.w(e, "could not delete the frames of %s", scan)
            false
        } catch (e: SecurityException) {
            Timber.w(e, "could not delete the frames of %s", scan)
            false
        }
    }

    /** walkFileTree without FOLLOW_LINKS reports a link as a file, so the link itself is deleted. */
    private object Remover : SimpleFileVisitor<Path>() {
        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            Files.delete(file)
            return FileVisitResult.CONTINUE
        }

        override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
            if (exc != null) throw exc
            Files.delete(dir)
            return FileVisitResult.CONTINUE
        }
    }
}
