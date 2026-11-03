package com.huntercoles.splatman.viewer.crop.store

import com.huntercoles.splatman.splat.io.ply.PlyWriter
import com.huntercoles.splatman.splat.model.SplatCloud
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** The disk steps a crop takes; a seam so tests can fail each one. Blocking. */
interface CropFileOps {
    /** Writes [cloud] as PLY to [file] and fsyncs it. */
    fun write(file: File, cloud: SplatCloud)

    /** Copies [from] to [to] and fsyncs [to]. */
    fun copy(from: File, to: File)

    /** Atomically renames [from] over [to] (same folder). */
    fun rename(from: File, to: File)
}

/** The real disk: fsync through the file descriptor, atomic same-folder renames. */
object DiskCropFileOps : CropFileOps {
    override fun write(file: File, cloud: SplatCloud) {
        FileOutputStream(file).use { out ->
            val buffered = out.buffered()
            PlyWriter.write(cloud, buffered)
            buffered.flush()
            out.fd.sync()
        }
    }

    override fun copy(from: File, to: File) {
        FileInputStream(from).use { input ->
            FileOutputStream(to).use { out ->
                input.copyTo(out)
                out.flush()
                out.fd.sync()
            }
        }
    }

    override fun rename(from: File, to: File) {
        Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}
