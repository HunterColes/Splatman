package com.huntercoles.splatman.capture.tracking

import android.app.Activity

/**
 * Whether this build and phone can track, and the install offer (ADR 0004). Each flavour's `FlavourTracking` gives
 * one: ARCore in `full`, a tracker that is never there in `foss` (no proprietary library, F-Droid).
 */
interface TrackerSupport {
    /** Never throws; a failed check reads as [TrackingAvailability.UNSUPPORTED]. */
    suspend fun check(): TrackingAvailability

    /** Asks for the tracker's install (from the activity's onResume); never throws. */
    fun requestInstall(activity: Activity, userRequested: Boolean): TrackingAvailability
}
