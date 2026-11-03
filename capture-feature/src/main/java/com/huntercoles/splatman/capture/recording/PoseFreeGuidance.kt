package com.huntercoles.splatman.capture.recording

import com.huntercoles.splatman.capture.presentation.capture.GuidanceLimits
import com.huntercoles.splatman.capture.presentation.capture.GuidanceWarning
import com.huntercoles.splatman.splat.capture.PoseFreeKeyframePolicy

/**
 * Live warnings without poses (#214): FAST when the gyro rate is over the policy's maxRateRadS or the frame is under
 * the sharpness floor two frames running, DARK when the mean luma is under [GuidanceLimits.MIN_MEAN_LUMA] (as
 * liveWarnings does). Never LOST, CLOSE or FAR: those need poses. [PoseFreeGuidanceTracker] keeps the blur run.
 */
object PoseFreeGuidance {
    private val maxRateRadS = PoseFreeKeyframePolicy().maxRateRadS

    /** True when [sharpness] is under [sharpnessFloor]; NaN either side is not blurred. */
    fun blurred(sharpness: Float, sharpnessFloor: Float): Boolean = sharpness < sharpnessFloor

    /**
     * The warnings for one frame. [previousBlurred] says whether the frame before was also under the floor; with the
     * stateless default (false) blur alone never warns, so callers that want the blur rule use the tracker.
     */
    fun warnings(
        rateRadS: Float,
        sharpness: Float,
        sharpnessFloor: Float,
        meanLuma: Float?,
        previousBlurred: Boolean = false,
    ): Set<GuidanceWarning> {
        val out = mutableSetOf<GuidanceWarning>()
        if (rateRadS > maxRateRadS || (previousBlurred && blurred(sharpness, sharpnessFloor))) {
            out += GuidanceWarning.FAST
        }
        if (meanLuma != null && meanLuma < GuidanceLimits.MIN_MEAN_LUMA) out += GuidanceWarning.DARK
        return out
    }
}

/** [PoseFreeGuidance] frame by frame, remembering whether the last frame was blurred. Not thread-safe. */
class PoseFreeGuidanceTracker {
    private var lastBlurred = false

    /** Forgets the blur run. */
    fun reset() {
        lastBlurred = false
    }

    /** The warnings for this frame, given the one before. */
    fun update(rateRadS: Float, sharpness: Float, sharpnessFloor: Float, meanLuma: Float?): Set<GuidanceWarning> {
        val w = PoseFreeGuidance.warnings(rateRadS, sharpness, sharpnessFloor, meanLuma, lastBlurred)
        lastBlurred = PoseFreeGuidance.blurred(sharpness, sharpnessFloor)
        return w
    }
}
