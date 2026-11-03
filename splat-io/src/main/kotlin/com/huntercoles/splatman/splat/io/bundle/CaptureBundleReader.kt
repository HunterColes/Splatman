package com.huntercoles.splatman.splat.io.bundle

import com.huntercoles.splatman.splat.capture.ScanMode
import com.huntercoles.splatman.splat.math.Vec3
import com.huntercoles.splatman.splat.model.CameraIntrinsics
import com.huntercoles.splatman.splat.model.DepthImage
import com.huntercoles.splatman.splat.model.Distortion
import com.huntercoles.splatman.splat.model.SeedPoints
import kotlinx.serialization.SerializationException
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads a capture bundle (ADR 0007) from a directory. Unknown keys are ignored; a `splatman_version` higher than
 * [SPLATMAN_BUNDLE_VERSION] is rejected; every image, depth and seed file named must exist, else the error names it.
 * Image bytes are not loaded: frames carry their files.
 */
object CaptureBundleReader {

    @Throws(CaptureBundleException::class)
    fun read(directory: File): CaptureBundle {
        val doc = readTransforms(directory)
        val frames = doc.frames.mapIndexed { i, f -> readFrame(directory, i, f) }
        val seed = doc.plyFilePath?.let { readSeedPly(existing(directory, it, "ply_file_path")) }
        val subject = doc.subject?.let {
            if (it.size != 3 || it.any { c -> !c.isFinite() }) {
                throw CaptureBundleException("$TRANSFORMS_FILE: splatman_subject must be 3 finite numbers")
            }
            Vec3(it[0], it[1], it[2])
        }
        return CaptureBundle(directory, doc.splatmanVersion, frames, seed, subject, ScanMode.fromKey(doc.mode))
    }

    /**
     * The scan mode and the frame poses (in frame order) from `transforms.json` alone: image, depth and seed files
     * need not exist, so it still works after Settings "Clear frames" has deleted `images/` (#293).
     */
    @Throws(CaptureBundleException::class)
    fun readPoses(directory: File): BundlePoses {
        val doc = readTransforms(directory)
        val poses = doc.frames.mapIndexed { i, f ->
            poseFromNerfstudioMatrix(f.transformMatrix, "frames[$i] (${f.filePath})")
        }
        return BundlePoses(ScanMode.fromKey(doc.mode), poses)
    }

    private fun readTransforms(directory: File): TransformsJson {
        val transforms = File(directory, TRANSFORMS_FILE)
        if (!transforms.isFile) {
            throw CaptureBundleException("not a capture bundle: no $TRANSFORMS_FILE in ${directory.path}")
        }
        val doc = try {
            bundleJson.decodeFromString(TransformsJson.serializer(), transforms.readText(Charsets.UTF_8))
        } catch (e: SerializationException) {
            throw CaptureBundleException("$TRANSFORMS_FILE: ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            throw CaptureBundleException("$TRANSFORMS_FILE: ${e.message}", e)
        }
        if (doc.splatmanVersion > SPLATMAN_BUNDLE_VERSION) {
            throw CaptureBundleException(
                "$TRANSFORMS_FILE: splatman_version ${doc.splatmanVersion} is newer than $SPLATMAN_BUNDLE_VERSION",
            )
        }
        return doc
    }

    /** Loads a frame's depth file (little-endian f32 metres); null when it has no depth or no depth size. */
    @Throws(CaptureBundleException::class)
    fun readDepth(frame: BundleFrame): DepthImage? {
        val file = frame.depthFile ?: return null
        val k = frame.depthIntrinsics ?: return null
        val bytes = file.readBytes()
        val n = k.width * k.height
        if (bytes.size != n * 4) {
            throw CaptureBundleException(
                "${file.name}: expected ${n * 4} bytes for ${k.width}x${k.height}, got ${bytes.size}",
            )
        }
        val depth = FloatArray(n)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(depth)
        return DepthImage(k, depth, null)
    }

    private fun readFrame(directory: File, index: Int, f: FrameJson): BundleFrame {
        val where = "frames[$index] (${f.filePath})"
        val intrinsics = intrinsics(where) {
            CameraIntrinsics(f.flX, f.flY, f.cx, f.cy, f.w, f.h, Distortion(f.k1, f.k2, f.p1, f.p2, f.k3))
        }
        val pose = poseFromNerfstudioMatrix(f.transformMatrix, where)
        val imageFile = existing(directory, f.filePath, "$where file_path")
        val depthFile = f.depthFilePath?.let { existing(directory, it, "$where depth_file_path") }
        val dfx = f.depthFlX
        val dfy = f.depthFlY
        val dcx = f.depthCx
        val dcy = f.depthCy
        val dw = f.depthW
        val dh = f.depthH
        val depthIntrinsics =
            if (depthFile != null && dfx != null && dfy != null && dcx != null && dcy != null && dw != null && dh != null) {
                intrinsics(where) { CameraIntrinsics(dfx, dfy, dcx, dcy, dw, dh) }
            } else {
                null
            }
        return BundleFrame(f.timestampNs, intrinsics, pose, imageFile, depthFile, depthIntrinsics)
    }

    private inline fun intrinsics(where: String, make: () -> CameraIntrinsics): CameraIntrinsics =
        try {
            make()
        } catch (e: IllegalArgumentException) {
            throw CaptureBundleException("$where: ${e.message}", e)
        }

    private fun existing(directory: File, path: String, key: String): File {
        val file = File(directory, path)
        if (!file.isFile) throw CaptureBundleException("$key: missing file $path")
        return file
    }

    /** Reads the seed PLY the writer produces: binary little-endian, `float x y z`, `uchar red green blue`. */
    internal fun readSeedPly(file: File): SeedPoints {
        val bytes = file.readBytes()
        val end = "end_header\n".toByteArray(Charsets.US_ASCII)
        val headerEnd = indexOf(bytes, end)
        if (headerEnd < 0) throw CaptureBundleException("${file.name}: no end_header")
        val header = String(bytes, 0, headerEnd, Charsets.US_ASCII).lines().map { it.trim() }.filter { it.isNotEmpty() }
        val vertex = "element vertex "
        val count = header.firstOrNull { it.startsWith(vertex) }?.removePrefix(vertex)?.toIntOrNull()
        if (count == null || count < 0 || header.filterNot { it.startsWith(vertex) } != SEED_HEADER) {
            throw CaptureBundleException("${file.name}: not a seed PLY (float x y z, uchar red green blue)")
        }
        val stride = CaptureBundleWriter.SEED_STRIDE
        val body = headerEnd + end.size
        if (bytes.size - body != count * stride) {
            throw CaptureBundleException("${file.name}: expected ${count * stride} data bytes, got ${bytes.size - body}")
        }
        val buf = ByteBuffer.wrap(bytes, body, count * stride).order(ByteOrder.LITTLE_ENDIAN)
        val positions = FloatArray(3 * count)
        val colours = ByteArray(3 * count)
        for (i in 0 until count) {
            positions[3 * i] = buf.float
            positions[3 * i + 1] = buf.float
            positions[3 * i + 2] = buf.float
            buf.get(colours, 3 * i, 3)
        }
        return SeedPoints(count, positions, colours)
    }

    private val SEED_HEADER = listOf(
        "ply",
        "format binary_little_endian 1.0",
        "property float x",
        "property float y",
        "property float z",
        "property uchar red",
        "property uchar green",
        "property uchar blue",
    )

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
