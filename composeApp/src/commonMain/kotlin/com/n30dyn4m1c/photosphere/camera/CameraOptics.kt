package com.n30dyn4m1c.photosphere.camera

import com.n30dyn4m1c.photosphere.stitching.RadialDistortion
import com.n30dyn4m1c.photosphere.toDegreesSafe
import com.n30dyn4m1c.photosphere.toRadiansSafe
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.tan

/**
 * Field of view of a mid-range phone's main camera, in the sensor's own
 * (landscape) frame. Used when the camera refuses to describe itself.
 */
internal val DEFAULT_SENSOR_FIELD_OF_VIEW = FieldOfView(
    horizontalDegrees = 66f,
    verticalDegrees = 52f,
)

/**
 * Diagonal field of view a phone's *main* rear camera has, near enough.
 *
 * Main cameras cluster hard around a 24 mm-equivalent lens; ultrawides are past
 * 100° on the diagonal and telephotos well under 50°. That separation is wide
 * enough to pick the main lens out of a list of focal lengths by nothing more
 * than which one lands closest to here.
 */
private const val MAIN_LENS_DIAGONAL_FOV_DEGREES = 78f

/**
 * How far the two independent field-of-view estimates may disagree before the
 * calibration is treated as untrustworthy. A quarter is far more than lens
 * variation and far less than the factor-of-two a crop mismatch produces.
 */
private const val MAX_ESTIMATE_DISAGREEMENT = 1.25f

/**
 * The optics of the rear camera, as this device reports them.
 *
 * Carries both halves the pipeline needs: the on-screen [FieldOfView] for
 * laying out targets and placing markers, and the lens's radial
 * [RadialDistortion] so the stitcher can correct frame edges. `null` distortion
 * means the camera gave no calibration — the stitcher then assumes a pinhole.
 */
data class SphereOptics(
    val fieldOfView: FieldOfView,
    val radialDistortion: RadialDistortion?,
    /**
     * Clockwise rotation, in degrees, that turns a raw (sensor-native) frame
     * into the display-upright portrait frame the [fieldOfView] describes —
     * the same rotation the camera records in each still's EXIF `ORIENTATION`
     * tag. The stitcher uses it as the fallback when a frame's EXIF rotation
     * is missing or was lost in a metadata rewrite, so the frame still lands
     * upright against its pose.
     */
    val portraitRotationDegrees: Int,
)

/**
 * The focal length of the lens that will actually be streaming.
 *
 * A physical camera lists one. A logical multi-camera lists one per lens it
 * fronts, in no useful order, and the pipeline has to pick the one matching the
 * lens the session bound:
 *
 * - A profile that asked for the widest lens gets the shortest focal length.
 * - Otherwise the main lens is wanted, and it is identified by its diagonal
 *   field of view landing nearest [MAIN_LENS_DIAGONAL_FOV_DEGREES].
 * - Without a sensor size ([sensorDiagonalMm] at zero) there is no field of view
 *   to compare, so the *longest* focal length wins by default. It is the
 *   asymmetry that decides it: guessing too narrow costs a few extra frames,
 *   guessing too wide spaces the plan past the overlap the stitcher needs and
 *   loses the whole run.
 */
internal fun selectFocalLengthMm(
    focalLengthsMm: FloatArray?,
    sensorDiagonalMm: Float,
    preferWidest: Boolean,
): Float? {
    val candidates = focalLengthsMm?.filter { it > 0f }?.distinct().orEmpty()
    if (candidates.isEmpty()) return null
    if (candidates.size == 1) return candidates.first()
    if (preferWidest) return candidates.min()
    if (sensorDiagonalMm <= 0f) return candidates.max()

    return candidates.minByOrNull { focal ->
        abs(fieldOfViewDegrees(sensorDiagonalMm, focal) - MAIN_LENS_DIAGONAL_FOV_DEGREES)
    }
}

/**
 * Settles the two field-of-view estimates against each other.
 *
 * The calibration is preferred when the geometry agrees with it, because it is
 * the more precise of the two. Past [MAX_ESTIMATE_DISAGREEMENT] the pair cannot
 * both be describing this lens, and the geometry is what survives: a focal
 * length in millimetres over a sensor size in millimetres has no crop to be
 * quoted against and therefore no way to be off by a factor.
 *
 * Deliberately free of any framework call, logging included, so the decision
 * can be exercised in a local unit test. The caller says what was discarded.
 */
internal fun reconcileFieldOfView(
    fromIntrinsics: FieldOfView?,
    fromGeometry: FieldOfView?,
): FieldOfView {
    if (fromIntrinsics == null) return fromGeometry ?: DEFAULT_SENSOR_FIELD_OF_VIEW
    if (fromGeometry == null) return fromIntrinsics

    val ratio = fromIntrinsics.horizontalDegrees / fromGeometry.horizontalDegrees
    val agrees = ratio <= MAX_ESTIMATE_DISAGREEMENT && ratio >= 1f / MAX_ESTIMATE_DISAGREEMENT
    return if (agrees) fromIntrinsics else fromGeometry
}

/**
 * Narrows a sensor-array field of view to the stream being read off it.
 *
 * A camera stack derives a stream of a different shape by cropping the array,
 * not by squeezing it — the focal length is unchanged and the axis that does
 * not fit simply loses its ends. A 16:9 preview off a 4:3 sensor therefore sees
 * a good 25% less across one axis than the array does, and an overlay drawn
 * from the array's angles would place every marker too close to the centre of a
 * frame that will not actually reach it.
 *
 * Both ratios are `width / height` in the sensor's own frame. A stream ratio of
 * zero (unknown) leaves the field of view alone.
 */
internal fun croppedToStreamAspect(
    sensor: FieldOfView,
    sensorAspectRatio: Float,
    streamAspectRatio: Float,
): FieldOfView {
    if (sensorAspectRatio <= 0f || streamAspectRatio <= 0f) return sensor
    val tanHorizontal = tanHalf(sensor.horizontalDegrees)
    val tanVertical = tanHalf(sensor.verticalDegrees)
    return if (streamAspectRatio >= sensorAspectRatio) {
        // Wider than the array: the full width is kept and the height is cut.
        FieldOfView(
            horizontalDegrees = sensor.horizontalDegrees,
            verticalDegrees = degreesFromTanHalf(tanHorizontal / streamAspectRatio),
        )
    } else {
        FieldOfView(
            horizontalDegrees = degreesFromTanHalf(tanVertical * streamAspectRatio),
            verticalDegrees = sensor.verticalDegrees,
        )
    }
}

/**
 * Angle subtended by an extent of [extent] (pixels or millimetres) behind a lens
 * of [focal] in the same units.
 */
internal fun fieldOfViewDegrees(extent: Float, focal: Float): Float =
    degreesFromTanHalf(extent / (2f * focal))

internal fun tanHalf(degrees: Float): Float = tan(toRadiansSafe(degrees / 2.0)).toFloat()

private fun degreesFromTanHalf(tangent: Float): Float =
    toDegreesSafe(2.0 * atan(tangent.toDouble())).toFloat().coerceIn(1f, 179f)
