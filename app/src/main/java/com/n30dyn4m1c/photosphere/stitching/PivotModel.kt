package com.n30dyn4m1c.photosphere.stitching

/**
 * How far the lens sits from the axis the user actually turns about.
 *
 * A panorama assumes every frame was shot from one point — the lens's entrance
 * pupil. Nobody shoots one that way. Holding a phone up and swivelling *your
 * body* puts the camera on the end of a lever roughly a third of a metre long,
 * so between the first frame and the one 90° later the lens has moved half a
 * metre sideways. That is translation, not rotation, and it is what shows up as
 * "the frames don't line up" on near objects while the far ones sit fine.
 *
 * The geometry of it is simple enough to correct for. The camera is not
 * anywhere: it is at `leverArm × forward`, always ahead of the pivot along its
 * own optical axis, because that is what holding a phone out in front of you
 * and turning does. Everything the pipeline projects is a direction *from the
 * pivot*, so a scene point taken to sit [sceneDistanceMeters] away is
 * `sceneDistance × direction`, and the ray the camera saw it along is that minus
 * the lever arm.
 *
 * Only the ratio of the two lengths survives that subtraction — see [ratio] —
 * which is why neither number has to be measured precisely. It also collapses to
 * something almost free: because the lever arm points straight down the optical
 * axis, it cancels out of the two lateral components entirely and leaves the
 * depth as the only term to correct.
 *
 * The default is deliberately cautious. A ratio that is too large bends frames
 * apart just as surely as parallax bends them together, so the nominal scene
 * distance is set well beyond a typical room: the correction then takes most of
 * the error out of a close scene and adds barely a degree to a distant one,
 * which is comfortably inside what [PoseRefiner] absorbs.
 */
data class PivotModel(
    /** Distance from the turning axis to the lens, in metres. */
    val leverArmMeters: Float,
    /** Distance the scene is assumed to sit at, in metres. */
    val sceneDistanceMeters: Float,
) {
    init {
        require(leverArmMeters >= 0f) { "lever arm must not be negative, was $leverArmMeters" }
        require(sceneDistanceMeters > 0f) {
            "scene distance must be positive, was $sceneDistanceMeters"
        }
    }

    /**
     * `leverArm / sceneDistance` — the only quantity the projection needs.
     *
     * Clamped to [MAX_RATIO]: past that the correction is larger than the pose
     * error it is meant to remove, and a scene closer than a few lever arms
     * cannot be stitched into a sphere at all, however it is projected.
     */
    val ratio: Double =
        (leverArmMeters.toDouble() / sceneDistanceMeters.toDouble()).coerceIn(0.0, MAX_RATIO)

    companion object {
        /** Beyond this the model does more harm than the parallax it corrects. */
        const val MAX_RATIO: Double = 0.25

        /** The lens is the pivot: a tripod, or a phone turned about its own camera. */
        val None: PivotModel = PivotModel(leverArmMeters = 0f, sceneDistanceMeters = 1f)

        /**
         * A phone held out in front of a standing person who turns on the spot.
         *
         * 0.35 m is chest-to-lens with the elbows in; 10 m is a nominal scene
         * distance chosen long rather than typical, for the reason in the class
         * docs.
         */
        val HandheldBodySwivel: PivotModel =
            PivotModel(leverArmMeters = 0.35f, sceneDistanceMeters = 10f)
    }
}
