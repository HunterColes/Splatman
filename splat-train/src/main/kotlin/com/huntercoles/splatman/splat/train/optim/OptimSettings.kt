package com.huntercoles.splatman.splat.train.optim

/**
 * The stage-2 optimiser's knobs, defaults from ADR 0005 (the 3DGS values). Learning rates are per step; the
 * position ones are multiplied by the scene extent (metres) and decay log-linearly from [positionLr] to
 * [positionLrFinal] over the run.
 */
data class OptimSettings(
    /** Initial position learning rate, x scene extent. */
    val positionLr: Double = 1.6e-4,
    /** Final position learning rate, x scene extent. */
    val positionLrFinal: Double = 1.6e-6,
    /** Degree-0 SH learning rate. */
    val shDcLr: Double = 2.5e-3,
    /** Opacity-logit learning rate. */
    val opacityLr: Double = 0.05,
    /** Log-scale learning rate. */
    val logScaleLr: Double = 5e-3,
    /** Raw-quaternion learning rate. */
    val rotationLr: Double = 1e-3,
    /** First iteration that densifies. */
    val densifyFrom: Int = 300,
    /** Densify every this many iterations. */
    val densifyEvery: Int = 100,
    /** Last densifying iteration as a fraction of the run. */
    val densifyUntilFraction: Double = 0.6,
    /** Mean view-space (NDC) gradient at or above which a splat is cloned or split. */
    val densifyGradThreshold: Double = 2e-4,
    /** Splats whose largest scale is at most this x scene extent are cloned, larger ones split. */
    val percentDense: Double = 0.01,
    /** Splats under this opacity are pruned. */
    val pruneOpacity: Double = 0.005,
    /** Splats whose largest scale is over this x scene extent are pruned as oversize. */
    val pruneScaleFraction: Double = 0.1,
    /** A split splat's two children have their scales divided by this (3DGS: 0.8 x 2). */
    val splitScaleDivisor: Double = 1.6,
    /** Emit a [com.huntercoles.splatman.splat.packet.TrainingProgress] snapshot every this many iterations. */
    val snapshotEvery: Int = 250,
    /**
     * Learn the lens (ticket #10): one shared scale on every keyframe's fx and fy and one radial k1, see
     * [com.huntercoles.splatman.splat.train.raster.Lens]. False keeps the keyframes' pinholes, exactly as without it.
     */
    val refineCamera: Boolean = true,
    /** Iterations the lens stays frozen at the pinhole before it learns (the splats settle first, as in TrackGS). */
    val cameraFreezeSteps: Int = 500,
    /** Focal-scale learning rate, per step. */
    val focalScaleLr: Double = 5e-4,
    /** k1 learning rate, per step. */
    val k1Lr: Double = 1e-4,
    /** The focal scale stays within 1 +- this. */
    val focalScaleLimit: Double = 0.1,
    /** k1 stays within +- this. */
    val k1Limit: Double = 0.1,
) {
    init {
        require(densifyEvery > 0) { "densifyEvery must be > 0, got $densifyEvery" }
        require(snapshotEvery > 0) { "snapshotEvery must be > 0, got $snapshotEvery" }
        require(splitScaleDivisor > 0.0) { "splitScaleDivisor must be > 0, got $splitScaleDivisor" }
        require(cameraFreezeSteps >= 0) { "cameraFreezeSteps must be >= 0, got $cameraFreezeSteps" }
        require(focalScaleLimit >= 0.0 && focalScaleLimit < 1.0) { "focalScaleLimit must be in [0, 1), got $focalScaleLimit" }
        require(k1Limit >= 0.0) { "k1Limit must be >= 0, got $k1Limit" }
    }

    /** Whether iteration [t] (1-based) learns the lens: [refineCamera] and past [cameraFreezeSteps]. */
    fun refinesCameraAt(t: Int): Boolean = refineCamera && t > cameraFreezeSteps

    /** Whether iteration [t] (1-based) of a [total]-iteration run densifies: every [densifyEvery] from [densifyFrom]. */
    fun densifiesAt(t: Int, total: Int): Boolean =
        t >= densifyFrom && t <= (densifyUntilFraction * total).toInt() && (t - densifyFrom) % densifyEvery == 0
}
