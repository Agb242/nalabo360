package com.n30dyn4m1c.photosphere.camera

import com.n30dyn4m1c.photosphere.sensor.OrientationData
import com.n30dyn4m1c.photosphere.sensor.normalizeDegrees
import kotlin.math.cos
import kotlin.math.roundToInt

/**
 * One frame the sphere needs, expressed as a direction to aim the camera at.
 *
 * Angles follow [OrientationData] exactly, so a target is "reached" when the
 * tracker reports the same pair: [yawDegrees] is the compass bearing of the
 * camera and [pitchDegrees] is **negative above the horizon**. [elevationDegrees]
 * is the friendlier reading of the same number for anything user-facing.
 */
data class SphereTarget(
    /** Compass bearing to aim at, -180°..180° (0° = north). */
    val yawDegrees: Float,
    /** Vertical aim, -90°..90°, negative above the horizon. */
    val pitchDegrees: Float,
) {
    /** Height above the horizon, positive up — the sign most people expect. */
    val elevationDegrees: Float get() = -pitchDegrees

    companion object {
        /** Builds a target from an elevation (positive up) rather than a pitch. */
        fun atElevation(yawDegrees: Float, elevationDegrees: Float): SphereTarget =
            SphereTarget(normalizeDegrees(yawDegrees), -elevationDegrees)
    }
}

/**
 * The ordered set of frames that makes up one sphere.
 *
 * Targets sit on horizontal rings. Each ring holds evenly spaced yaws, and the
 * spacing widens with elevation by `1 / cos(elevation)` so neighbouring frames
 * stay a constant *angular* distance apart rather than bunching up as the rings
 * shrink toward the poles — bunched frames cost capture time and buy the
 * stitcher nothing.
 *
 * The order is a boustrophedon: every other ring is swept in the opposite
 * direction, so each ring ends roughly where the next one begins and the user
 * never has to spin back through 360° between frames.
 *
 * Rings stop at ±60°, matching the brief. The caps are left to the stitcher's
 * feathering rather than to the user's patience.
 */
data class SphereTargetPlan(val targets: List<SphereTarget>) {

    val size: Int get() = targets.size

    operator fun get(index: Int): SphereTarget = targets[index]

    fun getOrNull(index: Int): SphereTarget? = targets.getOrNull(index)

    companion object {
        /**
         * Elevations of each ring, in capture order: the horizon first (where
         * the user is already pointing), then up, then down.
         */
        val DEFAULT_RING_ELEVATIONS: List<Float> = listOf(0f, 30f, 60f, -30f, -60f)

        /**
         * Yaw gap between neighbouring frames on the equator.
         *
         * 30° leaves roughly 40% overlap on a typical ~50° portrait field of
         * view, which is about the minimum a feature-based stitcher wants.
         */
        const val DEFAULT_EQUATOR_SPACING_DEGREES: Float = 30f

        /**
         * How much of each frame its neighbours may re-shoot, as a fraction of
         * the horizontal field of view.
         *
         * This stitcher works from measured poses rather than feature matches,
         * so overlap only has to absorb capture error — a small overlap keeps
         * the sphere covered without doubling every object up in a soft blend.
         */
        const val DEFAULT_TARGET_OVERLAP_FRACTION: Float = 0.25f

        /**
         * Lays out a sphere whose first target sits at [startYawDegrees].
         *
         * Anchoring on the bearing the user is already facing means capture
         * starts with the reticle on the first marker instead of asking them to
         * find magnetic north.
         */
        fun create(
            startYawDegrees: Float = 0f,
            ringElevations: List<Float> = DEFAULT_RING_ELEVATIONS,
            equatorSpacingDegrees: Float = DEFAULT_EQUATOR_SPACING_DEGREES,
        ): SphereTargetPlan {
            require(equatorSpacingDegrees > 0f) { "spacing must be positive" }

            return createUnchecked(startYawDegrees, ringElevations, equatorSpacingDegrees)
        }

        /**
         * Lays out a sphere whose frame spacing matches the device's optics.
         *
         * The yaw gap is the horizontal field of view minus the target overlap,
         * so a wide-angle phone takes fewer, larger frames and a narrow one
         * takes more, smaller frames — every frame covers roughly the same
         * slice of the sphere instead of the fixed 30° spacing piling up far
         * more overlap on wide lenses.
         */
        fun createForFieldOfView(
            startYawDegrees: Float,
            fieldOfView: FieldOfView,
            ringElevations: List<Float> = DEFAULT_RING_ELEVATIONS,
            targetOverlapFraction: Float = DEFAULT_TARGET_OVERLAP_FRACTION,
        ): SphereTargetPlan {
            require(targetOverlapFraction in 0f..1f) {
                "overlap fraction must be between 0 and 1, was $targetOverlapFraction"
            }
            val spacing = fieldOfView.horizontalDegrees * (1f - targetOverlapFraction)
            return createUnchecked(startYawDegrees, ringElevations, spacing)
        }

        private fun createUnchecked(
            startYawDegrees: Float,
            ringElevations: List<Float>,
            equatorSpacingDegrees: Float,
        ): SphereTargetPlan {
            require(equatorSpacingDegrees > 0f) { "spacing must be positive" }

            val targets = buildList {
                ringElevations.forEachIndexed { ringIndex, elevation ->
                    val ring = ringYaws(startYawDegrees, elevation, equatorSpacingDegrees)
                    // Reverse every other ring so consecutive rings meet at the
                    // same bearing instead of a full turn apart.
                    val ordered = if (ringIndex % 2 == 0) ring else ring.reversed()
                    ordered.forEach { yaw -> add(SphereTarget.atElevation(yaw, elevation)) }
                }
            }
            return SphereTargetPlan(targets)
        }

        private fun ringYaws(
            startYawDegrees: Float,
            elevationDegrees: Float,
            equatorSpacingDegrees: Float,
        ): List<Float> {
            val shrink = cos(Math.toRadians(elevationDegrees.toDouble())).toFloat()
            // A ring at the pole degenerates to a single frame; guard the divide
            // rather than letting the spacing run away to infinity.
            val spacing = if (shrink <= 1e-3f) 360f else equatorSpacingDegrees / shrink
            val count = (360f / spacing).roundToInt().coerceAtLeast(1)
            val step = 360f / count
            return List(count) { normalizeDegrees(startYawDegrees + it * step) }
        }
    }
}
