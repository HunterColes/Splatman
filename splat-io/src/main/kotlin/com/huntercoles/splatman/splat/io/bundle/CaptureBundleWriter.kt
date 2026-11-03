package com.huntercoles.splatman.splat.io.bundle

import com.huntercoles.splatman.splat.frames.FrameConversions
import com.huntercoles.splatman.splat.math.Vec3
import com.huntercoles.splatman.splat.model.SeedPoints
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Writes a capture bundle (ADR 0007) into a directory: `images/frame_NNNNN.jpg`, optional `depth/frame_NNNNN.bin`,
 * `seed.ply`, and last `transforms.json`.
 *
 * Every file is written to a `.tmp` sibling, synced and renamed into place, and an old `transforms.json` is removed
 * before anything else is written, so a process killed mid-write leaves no `transforms.json`: the directory is then
 * not a bundle (the reader says so) rather than a half one.
 */
object CaptureBundleWriter {

    /**
     * Writes [frames], [seed] and the optional capture [subject] (frame W) into [directory] (created if needed);
     * frame `i` is named `frame_{i+1}`, 5 digits.
     */
    @Throws(IOException::class)
    fun write(directory: File, frames: List<BundleFrameInput>, seed: SeedPoints, subject: Vec3? = null) {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("cannot create ${directory.path}")
        Files.deleteIfExists(File(directory, TRANSFORMS_FILE).toPath())
        val json = frames.mapIndexed { i, f -> writeFrame(directory, i, f) }
        writeAtomic(File(directory, SEED_FILE), seedPly(seed))
        val doc = TransformsJson(
            cameraModel = CAMERA_MODEL,
            splatmanVersion = SPLATMAN_BUNDLE_VERSION,
            plyFilePath = SEED_FILE,
            subject = subject?.let { listOf(it.x, it.y, it.z) },
            frames = json,
        )
        writeAtomic(File(directory, TRANSFORMS_FILE), encode(doc).toByteArray(Charsets.UTF_8))
    }

    internal fun encode(doc: TransformsJson): String =
        bundleJson.encodeToString(TransformsJson.serializer(), doc) + "\n"

    private fun writeFrame(directory: File, index: Int, frame: BundleFrameInput): FrameJson {
        val name = "frame_%05d".format(index + 1)
        val imagePath = "images/$name.jpg"
        writeAtomic(File(directory, imagePath), frame.jpeg)
        val depth = frame.depth
        var depthPath: String? = null
        if (depth != null) {
            depthPath = "depth/$name.bin"
            val buf = ByteBuffer.allocate(depth.depthM.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            buf.asFloatBuffer().put(depth.depthM)
            writeAtomic(File(directory, depthPath), buf.array())
        }
        val k = frame.intrinsics
        val d = k.distortion
        val di = depth?.intrinsics
        return FrameJson(
            filePath = imagePath,
            transformMatrix = FrameConversions.nerfstudioMatrix(frame.pose).map { it.toList() },
            flX = k.fx,
            flY = k.fy,
            cx = k.cx,
            cy = k.cy,
            w = k.width,
            h = k.height,
            k1 = d.k1,
            k2 = d.k2,
            k3 = d.k3,
            p1 = d.p1,
            p2 = d.p2,
            timestampNs = frame.timestampNs,
            depthFilePath = depthPath,
            depthFlX = di?.fx,
            depthFlY = di?.fy,
            depthCx = di?.cx,
            depthCy = di?.cy,
            depthW = di?.width,
            depthH = di?.height,
        )
    }

    /** Binary little-endian PLY: `float x y z`, `uchar red green blue`, frame W (the bundle's world, ADR 0003 NS). */
    internal fun seedPly(seed: SeedPoints): ByteArray {
        val header = "ply\nformat binary_little_endian 1.0\nelement vertex ${seed.count}\n" +
            "property float x\nproperty float y\nproperty float z\n" +
            "property uchar red\nproperty uchar green\nproperty uchar blue\nend_header\n"
        val h = header.toByteArray(Charsets.US_ASCII)
        val buf = ByteBuffer.allocate(h.size + seed.count * SEED_STRIDE).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(h)
        val p = seed.positions
        val c = seed.colours
        for (i in 0 until seed.count) {
            buf.putFloat(p[3 * i]).putFloat(p[3 * i + 1]).putFloat(p[3 * i + 2])
            buf.put(c[3 * i]).put(c[3 * i + 1]).put(c[3 * i + 2])
        }
        return buf.array()
    }

    /** Writes [bytes] to `<target>.tmp`, syncs, then renames it onto [target]; the temp file is removed on failure. */
    internal fun writeAtomic(target: File, bytes: ByteArray) {
        val parent = target.absoluteFile.parentFile
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("cannot create ${parent.path}")
        val tmp = File(parent, target.name + ".tmp")
        try {
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.fd.sync()
            }
            try {
                Files.move(
                    tmp.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: IOException) {
            tmp.delete()
            throw IOException("cannot write ${target.path}: ${e.message}", e)
        }
    }

    /** Bytes per seed vertex: 3 x f32 + 3 x u8. */
    internal const val SEED_STRIDE = 15
}
