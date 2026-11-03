package com.huntercoles.splatman.viewer.rendering

import com.huntercoles.splatman.splat.capture.ScanMode
import com.huntercoles.splatman.splat.io.bundle.CaptureBundleException
import com.huntercoles.splatman.splat.io.bundle.CaptureBundleReader
import com.huntercoles.splatman.splat.math.Vec3
import com.huntercoles.splatman.splat.model.CameraPose
import timber.log.Timber
import java.io.File

/**
 * Where a room scan opens (docs/design/screens/viewer.md "Room scans"): the [eye] at the mean of the capture's camera
 * centres, looking along [forward], the first keyframe's view direction (camera -Z, ADR 0003), in the bundle's world
 * frame W, which is the trained splat's frame. [GaussianCameraController.setRoomView] levels it.
 */
data class RoomStart(val eye: Vec3, val forward: Vec3) {
    companion object {
        /** The start pose of a capture with [poses] in keyframe order; null when there is none to start from. */
        fun of(poses: List<CameraPose>): RoomStart? {
            val finite = poses.filter { it.translationM.isFinite() }
            if (finite.isEmpty()) return null
            var sum = Vec3.ZERO
            finite.forEach { sum += it.translationM }
            val forward = finite.first().rotation.rotate(-Vec3.UNIT_Z)
            if (!forward.isFinite() || forward.lengthSquared() < 1e-12f) return null
            return RoomStart(sum / finite.size.toFloat(), forward.normalized())
        }

        /**
         * The start pose of the capture bundle in [scanDir] when its `transforms.json` says `splatman_mode: "room"`;
         * null for an object scan, a pose-free capture or a bundle that cannot be read (it then opens as today).
         */
        fun read(scanDir: File): RoomStart? {
            // Poses only: Settings "Clear frames" deletes images/, and the room must still open from inside.
            val bundle = try {
                CaptureBundleReader.readPoses(scanDir)
            } catch (e: CaptureBundleException) {
                Timber.w(e, "No room start for %s", scanDir.name)
                return null
            }
            if (bundle.mode != ScanMode.ROOM) return null
            return of(bundle.poses)
        }

        private fun Vec3.isFinite(): Boolean = x.isFinite() && y.isFinite() && z.isFinite()
    }
}
