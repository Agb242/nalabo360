package com.n30dyn4m1c.photosphere.stitching

/**
 * Brown-Conrady radial lens distortion, in the convention the camera reports it.
 *
 * The camera2 API describes a lens's distortion with the coefficients that map
 * an *undistorted* (ideal pinhole) image coordinate to the *distorted*
 * coordinate that must be sampled in the captured frame
 * (`CameraCharacteristics.LENS_DISTORTION`):
 *
 * ```
 * x_d = x_i * (1 + k1 r^2 + k2 r^4 + k3 r^6)
 * y_d = y_i * (1 + k1 r^2 + k2 r^4 + k3 r^6)
 * ```
 *
 * with r^2 = x_i^2 + y_i^2 and both coordinates measured in pixels from the
 * optical centre. [coefficients] holds `[k1, k2, k3]` in that order, calibrated
 * on the sensor whose longest edge is [calibrationLongestEdgePx]. The tangential
 * terms of the full model are dropped: they are tiny on phone lenses, and their
 * coordinate normalization is the part the API documents inconsistently, while
 * the radial terms carry almost all of the seam error at frame borders.
 *
 * Every pipeline stage applies the same model in the same pixel space as the
 * frame it is looking at — the renderer samples through it, the footprint walks
 * the distorted border, and pose refinement undistorts keypoints — so a
 * direction, its ideal pixel and its distorted pixel always agree.
 */
class RadialDistortion(
    /** `[k1, k2, k3]` at the calibration scale. */
    val coefficients: DoubleArray,
    /** Longest sensor edge the coefficients were calibrated at, in pixels. */
    val calibrationLongestEdgePx: Int,
) {
    /**
     * The same distortion re-expressed for a frame whose longest edge is
     * [decodedLongestEdgePx].
     *
     * A uniform downsample by `s` shrinks every pixel offset by `s`, so r^2
     * shrinks by s^2 and each coefficient of the polynomial in r^2 rescales by
     * s^(2·order): k1 by s^2, k2 by s^4, k3 by s^6.
     */
    fun effectiveFor(decodedLongestEdgePx: Int): DoubleArray? {
        if (coefficients.isEmpty() || calibrationLongestEdgePx <= 0) return null
        val scale = calibrationLongestEdgePx.toDouble() / decodedLongestEdgePx
        val s2 = scale * scale
        return doubleArrayOf(
            coefficients[0] * s2,
            coefficients[1] * s2 * s2,
            coefficients[2] * s2 * s2 * s2,
        )
    }
}

/**
 * The radial scale factor applied to an offset `r^2` from the optical axis.
 *
 * `1 + k1 r^2 + k2 r^4 + k3 r^6`, folded as `r^2 (k1 + r^2 (k2 + r^2 k3))`.
 * Kept in one place because the renderer inlines it over millions of pixels and
 * the geometry helpers call it for footprints and keypoints — both must use the
 * identical polynomial.
 */
internal fun radialFactor(coefficients: DoubleArray, r2: Double): Double =
    1.0 + r2 * (coefficients[0] + r2 * (coefficients[1] + r2 * coefficients[2]))

/**
 * The distorted source pixel for an ideal pixel at ([column], [row]).
 *
 * The forward (ideal → distorted) direction of the model above: what the lens
 * turns the pinhole projection of a ray into.
 */
internal fun distortPixel(
    column: Double,
    row: Double,
    centreX: Double,
    centreY: Double,
    coefficients: DoubleArray,
): DoubleArray {
    val x = column - centreX
    val y = row - centreY
    val factor = radialFactor(coefficients, x * x + y * y)
    return doubleArrayOf(centreX + x * factor, centreY + y * factor)
}

/**
 * The ideal (undistorted) pixel whose [column]/[row] the lens captured.
 *
 * The inverse of [distortPixel]. There is no closed form, so it is inverted
 * with fixed-point iteration: `x_{n+1} = x_d / factor(x_n^2 + y_n^2)` converges
 * quickly because the factor is close to 1 for the modest distortion a phone
 * lens exhibits.
 */
internal fun undistortPixel(
    column: Double,
    row: Double,
    centreX: Double,
    centreY: Double,
    coefficients: DoubleArray,
): DoubleArray {
    val targetX = column - centreX
    val targetY = row - centreY
    var x = targetX
    var y = targetY
    repeat(8) {
        val factor = radialFactor(coefficients, x * x + y * y)
        x = targetX / factor
        y = targetY / factor
    }
    return doubleArrayOf(centreX + x, centreY + y)
}
