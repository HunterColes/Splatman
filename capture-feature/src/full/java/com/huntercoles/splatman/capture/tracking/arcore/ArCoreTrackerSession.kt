package com.huntercoles.splatman.capture.tracking.arcore

import android.content.Context
import com.google.ar.core.Camera
import com.google.ar.core.Config
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.exceptions.NotYetAvailableException
import com.huntercoles.splatman.capture.preview.CameraBackgroundRenderer
import com.huntercoles.splatman.capture.preview.PreviewTarget
import com.huntercoles.splatman.capture.tracking.FeaturePoints
import com.huntercoles.splatman.capture.tracking.FrameParts
import com.huntercoles.splatman.capture.tracking.MediaCameraImage
import com.huntercoles.splatman.capture.tracking.TrackedFrame
import com.huntercoles.splatman.capture.tracking.TrackerSession
import com.huntercoles.splatman.capture.tracking.TrackingFailure
import com.huntercoles.splatman.capture.tracking.TrackingState
import com.huntercoles.splatman.splat.frames.FrameConversions
import com.huntercoles.splatman.splat.model.CameraIntrinsics
import com.huntercoles.splatman.splat.model.DepthImage
import com.google.ar.core.CameraIntrinsics as ArIntrinsics
import com.google.ar.core.TrackingFailureReason as ArFailure
import com.google.ar.core.TrackingState as ArTrackingState

/**
 * One ARCore session (ADR 0004) as a [TrackerSession]: depth when supported, focus auto, latest-camera-image update
 * mode, an offscreen GL context for `update()`; with a [PreviewTarget] it also draws the camera texture into the
 * preview window after each new frame. Build, use and close it on one thread (the driver's). Only build it
 * after [ArCoreAvailability.check] said supported and CAMERA is granted.
 */
internal class ArCoreTrackerSession(context: Context) : TrackerSession {
    private val gl = OffscreenGl()
    private val session: Session
    private val depthEnabled: Boolean
    private var lastTimestampNs = 0L
    private var preview: PreviewTarget? = null
    private var background: CameraBackgroundRenderer? = null
    private val quadTexCoords = FloatArray(CameraBackgroundRenderer.QUAD_NDC.size)
    private var texCoordsValid = false

    init {
        var opened: Session? = null
        try {
            opened = Session(context)
            depthEnabled = opened.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
            val config = Config(opened).apply {
                focusMode = Config.FocusMode.AUTO
                updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                depthMode = if (depthEnabled) Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED
            }
            opened.configure(config)
            opened.setCameraTextureName(gl.cameraTexture)
            session = opened
        } catch (e: Exception) {
            opened?.close()
            gl.release()
            throw e
        }
    }

    override fun resume() = session.resume()

    override fun pause() = session.pause()

    override fun update(): TrackedFrame? {
        val frame = session.update()
        val timestampNs = frame.timestamp
        if (timestampNs == 0L || timestampNs == lastTimestampNs) return null
        lastTimestampNs = timestampNs
        try {
            drawPreview(frame)
        } catch (e: Exception) {
            // A broken preview must not stop tracking: drop the window and keep the frames coming.
            setPreview(null)
        }
        val camera = frame.camera
        val image = try {
            MediaCameraImage(frame.acquireCameraImage())
        } catch (e: NotYetAvailableException) {
            null
        }
        return FrameParts.closeOnFailure(image) {
            val pose = camera.pose
            TrackedFrame(
                timestampNs = timestampNs,
                trackingState = trackingState(camera.trackingState),
                pose = FrameConversions.poseFromArCore(pose.translation, pose.rotationQuaternion),
                intrinsics = intrinsics(camera.imageIntrinsics),
                image = image,
                points = points(frame),
                depth = if (depthEnabled) depth(frame, camera) else null,
                trackingFailure = failure(camera.trackingFailureReason),
            )
        }
    }

    override fun setPreview(target: PreviewTarget?) {
        if (target == null) {
            preview = null
            gl.detachWindow()
            return
        }
        if (!gl.attachWindow(target.surface)) {
            preview = null
            return
        }
        preview = target
        session.setDisplayGeometry(target.rotation, target.width, target.height)
        texCoordsValid = false
    }

    override fun close() {
        try {
            background?.release()
            background = null
            session.close()
        } finally {
            gl.release()
        }
    }

    /** Draws the camera texture full screen, rotated for the display by `transformCoordinates2d`. */
    private fun drawPreview(frame: Frame) {
        val target = preview ?: return
        if (!texCoordsValid || frame.hasDisplayGeometryChanged()) {
            frame.transformCoordinates2d(
                Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
                CameraBackgroundRenderer.QUAD_NDC,
                Coordinates2d.TEXTURE_NORMALIZED,
                quadTexCoords,
            )
            texCoordsValid = true
        }
        val renderer = background ?: CameraBackgroundRenderer().also { background = it }
        renderer.draw(gl.cameraTexture, quadTexCoords, target.width, target.height)
        gl.swapWindow()
    }

    private fun points(frame: Frame): FeaturePoints =
        frame.acquirePointCloud().use { cloud -> FrameParts.featurePoints(cloud.points) }

    private fun depth(frame: Frame, camera: Camera): DepthImage? {
        val image = try {
            frame.acquireDepthImage16Bits()
        } catch (e: NotYetAvailableException) {
            return null
        }
        return image.use {
            val plane = it.planes[0]
            // The depth image covers the GPU texture's field of view at its own resolution.
            val texture = intrinsics(camera.textureIntrinsics).scaled(it.width, it.height)
            FrameParts.depthFromMillimetres(plane.buffer, plane.rowStride, plane.pixelStride, texture)
        }
    }

    private fun intrinsics(ar: ArIntrinsics): CameraIntrinsics {
        val focal = ar.focalLength
        val principal = ar.principalPoint
        val size = ar.imageDimensions
        return CameraIntrinsics(
            fx = focal[0],
            fy = focal[1],
            cx = principal[0],
            cy = principal[1],
            width = size[0],
            height = size[1],
        )
    }

    private fun failure(reason: ArFailure): TrackingFailure = when (reason) {
        ArFailure.NONE -> TrackingFailure.NONE
        ArFailure.BAD_STATE -> TrackingFailure.BAD_STATE
        ArFailure.INSUFFICIENT_LIGHT -> TrackingFailure.INSUFFICIENT_LIGHT
        ArFailure.EXCESSIVE_MOTION -> TrackingFailure.EXCESSIVE_MOTION
        ArFailure.INSUFFICIENT_FEATURES -> TrackingFailure.INSUFFICIENT_FEATURES
        ArFailure.CAMERA_UNAVAILABLE -> TrackingFailure.CAMERA_UNAVAILABLE
    }

    private fun trackingState(state: ArTrackingState): TrackingState = when (state) {
        ArTrackingState.TRACKING -> TrackingState.TRACKING
        ArTrackingState.PAUSED -> TrackingState.PAUSED
        ArTrackingState.STOPPED -> TrackingState.STOPPED
    }
}
