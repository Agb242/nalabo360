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
 * with r^2 = x_i^2 + y_i^2. The coefficients are **unitless** and the
 * coordinates they act on are measured in a normalized space: the origin is the
 * optical centre, and the axes are scaled so the *farthest* edge of the
 * calibration array sits at ±1 (so |r| never exceeds √2). This is what makes
 * the polynomial resolution-independent — a phone's ~66° lens reports k1 on the
 * order of −0.02 regardless of whether the sensor is 12 MP or 50 MP.
 *
 * [coefficients] holds `[k1, k2, k3]` in that order, and
 * [calibrationLongestEdgePx] records the edge the normalized space was defined
 * against (kept so the calibration can be sanity-checked). The tangential terms
 * of the full model are dropped: they are tiny on phone lenses, and the radial
 * terms carry almost all of the seam error at frame borders.
 *
 * Every pipeline stage applies the same model in the same pixel space as the
 * frame it is looking at — the renderer samples through it, the footprint walks
 * the distorted border, and pose refinement undistorts keypoints — so a
 * direction, its ideal pixel and its distorted pixel always agree. [effectiveFor]
 * is what converts the unitless polynomial into that pixel space.
 */
class RadialDistortion(
    /** Unitless `[k1, k2, k3]`, applied to normalized coordinates (edge = ±1). */
    val coefficients: DoubleArray,
    /** Longest sensor edge the normalized coordinate space was defined against, in pixels. */
    val calibrationLongestEdgePx: Int,
) {
    /**
     * The same distortion re-expressed for a frame whose longest edge is
     * [decodedLongestEdgePx].
     *
     * A pixel offset `p` maps to the normalized offset `p / (edge/2)`, so the
     * polynomial in normalized r² becomes one in pixel r² with each term
     * divided by `(edge/2)^(2·order)`: k1 by h², k2 by h⁴, k3 by h⁶, where
     * `h = decodedLongestEdgePx / 2`. The result is what [distortPixel] and
     * [undistortPixel] expect: coefficients that take r² in pixels.
     */
    fun effectiveFor(decodedLongestEdgePx: Int): DoubleArray? {
        if (coefficients.isEmpty() || calibrationLongestEdgePx <= 0 || decodedLongestEdgePx <= 0) {
            return null
        }
        val halfExtent = decodedLongestEdgePx / 2.0
        val h2 = halfExtent * halfExtent
        val h4 = h2 * h2
        val h6 = h2 * h2 * h2
        return doubleArrayOf(
            coefficients[0] / h2,
            coefficients[1] / h4,
            coefficients[2] / h6,
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
