package com.huntercoles.splatman.capture.recording

import com.huntercoles.splatman.splat.io.bundle.ImageSize
import com.huntercoles.splatman.splat.io.bundle.IntrinsicsSource
import com.huntercoles.splatman.splat.io.bundle.TimestampSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** [Camera2Calibration] fed plain values through [CharacteristicsView]. */
class Camera2CalibrationTest {
    private data class Fake(
        override val sensorOrientation: Int? = 90,
        override val timestampSource: Int? = 1,
        override val pixelArraySize: IntArray? = intArrayOf(4032, 3024),
        override val activeArray: IntArray? = intArrayOf(0, 0, 4032, 3024),
        override val preCorrectionActiveArray: IntArray? = intArrayOf(0, 0, 4000, 3000),
        override val physicalSizeMm: FloatArray? = floatArrayOf(5.645f, 4.234f),
        override val focalLengthsMm: FloatArray? = floatArrayOf(4.38f),
        override val lensIntrinsicCalibration: FloatArray? = floatArrayOf(3000f, 2990f, 2010f, 1500f, 0f),
        override val lensDistortion: FloatArray? = null,
        override val lensPoseRotation: FloatArray? = null,
        override val lensPoseTranslation: FloatArray? = null,
        override val lensPoseReference: Int? = null,
    ) : CharacteristicsView

    private val eps = 0.01f

    @Test
    fun rescalesAKnownCalibrationTo1920x1080() {
        val k = Camera2Calibration.read(Fake(), "0", ImageSize(1920, 1080)).intrinsics
        // Cover scale max(1920/4000, 1080/3000) = 0.48; 3000 - 1080/0.48 = 750 rows cropped, 375 off the top.
        assertEquals(IntrinsicsSource.LENS_INTRINSIC_CALIBRATION, k.source)
        assertEquals(1440f, k.flX, eps)
        assertEquals(1435.2f, k.flY, eps)
        assertEquals(964.8f, k.cx, eps)
        assertEquals(540f, k.cy, eps)
    }

    @Test
    fun rescalesAKnownCalibrationTo1280x720() {
        val k = Camera2Calibration.read(Fake(), "0", ImageSize(1280, 720)).intrinsics
        assertEquals(IntrinsicsSource.LENS_INTRINSIC_CALIBRATION, k.source)
        assertEquals(960f, k.flX, eps)
        assertEquals(956.8f, k.flY, eps)
        assertEquals(643.2f, k.cx, eps)
        assertEquals(360f, k.cy, eps)
    }

    @Test
    fun anAllZeroCalibrationFallsBackToFocalLength() {
        val c = Camera2Calibration.read(Fake(lensIntrinsicCalibration = FloatArray(5)), "0", ImageSize(1920, 1080))
        assertFocalLength(c.intrinsics)
        assertEquals(List(5) { 0f }, c.lensIntrinsicCalibration)
    }

    @Test
    fun aNullCalibrationFallsBackToFocalLength() {
        val c = Camera2Calibration.read(Fake(lensIntrinsicCalibration = null), "0", ImageSize(1920, 1080))
        assertFocalLength(c.intrinsics)
        assertNull(c.lensIntrinsicCalibration)
    }

    /** ADR 0007's example: 4.38 mm over 5.645 mm on 4032 px, scaled by 1920/4032. */
    private fun assertFocalLength(k: com.huntercoles.splatman.splat.io.bundle.CalibrationIntrinsics) {
        assertEquals(IntrinsicsSource.FOCAL_LENGTH, k.source)
        assertEquals(1489.8f, k.flX, 0.5f)
        assertEquals(1489.6f, k.flY, 0.5f)
        assertEquals(960f, k.cx, eps)
        assertEquals(540f, k.cy, eps)
    }

    @Test
    fun timestampSourceIsRealtimeOnlyWhenCamera2SaysSo() {
        val image = ImageSize(1920, 1080)
        assertEquals(TimestampSource.REALTIME, Camera2Calibration.read(Fake(timestampSource = 1), "0", image).timestampSource)
        assertEquals(TimestampSource.UNKNOWN, Camera2Calibration.read(Fake(timestampSource = 0), "0", image).timestampSource)
        assertEquals(TimestampSource.UNKNOWN, Camera2Calibration.read(Fake(timestampSource = null), "0", image).timestampSource)
    }

    @Test
    fun aNullLensPoseIsKeptAsNull() {
        val c = Camera2Calibration.read(Fake(), "0", ImageSize(1920, 1080))
        assertNull(c.lensPoseRotation)
        assertNull(c.lensPoseTranslation)
        assertNull(c.lensPoseReference)
    }

    @Test
    fun aReportedLensPoseIsCopied() {
        val fake = Fake(
            lensPoseRotation = floatArrayOf(0f, 0f, 0f, 1f),
            lensPoseTranslation = floatArrayOf(0.01f, 0f, 0f),
            lensPoseReference = 1,
        )
        val c = Camera2Calibration.read(fake, "2", ImageSize(1920, 1080))
        assertEquals(listOf(0f, 0f, 0f, 1f), c.lensPoseRotation)
        assertEquals(listOf(0.01f, 0f, 0f), c.lensPoseTranslation)
        assertEquals(1, c.lensPoseReference)
        assertEquals("2", c.cameraId)
        assertEquals(90, c.sensorOrientationDeg)
    }

    @Test
    fun theLogLineHoldsEveryFieldWithNullsAsNull() {
        val line = Camera2Calibration.log(Camera2Calibration.read(Fake(), "0", ImageSize(1920, 1080)))
        val keys = listOf(
            "camera_id=0", "sensor_orientation_deg=90", "timestamp_source=REALTIME", "pixel_array_size=[4032, 3024]",
            "active_array=[0, 0, 4032, 3024]", "pre_correction_active_array=[0, 0, 4000, 3000]",
            "physical_size_mm=[5.645, 4.234]", "focal_lengths_mm=[4.38]",
            "lens_intrinsic_calibration=[3000.0, 2990.0, 2010.0, 1500.0, 0.0]", "lens_distortion=null",
            "lens_pose_rotation=null", "lens_pose_translation=null", "lens_pose_reference=null", "image=1920x1080",
            "fl_x=", "fl_y=", "cx=", "cy=", "source=LENS_INTRINSIC_CALIBRATION",
        )
        for (k in keys) assertTrue(line.contains(k), "missing $k in $line")
    }
}
