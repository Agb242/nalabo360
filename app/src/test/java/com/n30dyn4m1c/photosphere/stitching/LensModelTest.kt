package com.n30dyn4m1c.photosphere.stitching

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The radial lens model that keeps frame edges honest.
 */
class LensModelTest {

    // Realistic phone-lens barrel distortion, in the *normalized* space the
    // camera2 API uses: the axes are scaled so the farthest edge of the
    // calibration array sits at ±1. A k1 of −0.02 pulls the frame corner
    // inward by roughly 3% (r² = 2 at the corner of a 4:3 array).
    private val unitlessCoefficients = doubleArrayOf(-0.02, 1.0e-3, -5.0e-5)

    // The same lens re-expressed as pixel-space coefficients for a ~1024px
    // working image, which is what the pipeline feeds to the renderer.
    private val smallCoefficients =
        RadialDistortion(unitlessCoefficients, 4000).effectiveFor(1024)!!

    private val centreX = 512.0
    private val centreY = 384.0

    @Test
    fun `the optical centre is unmoved by distortion`() {
        val distorted = distortPixel(centreX, centreY, centreX, centreY, smallCoefficients)
        assertEquals(centreX, distorted[0], 1e-9)
        assertEquals(centreY, distorted[1], 1e-9)
    }

    @Test
    fun `barrel distortion pulls the frame edge towards the centre`() {
        // An ideal pixel near the corner should land closer to the centre once
        // the negative k1 pushes it in, in proportion to its distance from it.
        val distorted = distortPixel(900.0, 700.0, centreX, centreY, smallCoefficients)
        val xOffset = 900.0 - centreX
        val yOffset = 700.0 - centreY
        val xShrink = xOffset - (distorted[0] - centreX)
        val yShrink = yOffset - (distorted[1] - centreY)
        assertTrue("x should shrink", xShrink > 0.0)
        assertTrue("y should shrink", yShrink > 0.0)
        // The pull is proportional to the offset from the optical centre.
        assertEquals(xShrink / yShrink, xOffset / yOffset, 1e-9)
    }

    @Test
    fun `undistort is the inverse of distort`() {
        for ((column, row) in listOf(
            512.0 to 384.0,
            900.0 to 700.0,
            100.0 to 50.0,
            811.0 to 24.0,
            64.0 to 740.0,
        )) {
            val distorted = distortPixel(column, row, centreX, centreY, smallCoefficients)
            val recovered = undistortPixel(distorted[0], distorted[1], centreX, centreY, smallCoefficients)
            assertEquals("column $column", column, recovered[0], 1e-3)
            assertEquals("row $row", row, recovered[1], 1e-3)
        }
    }

    @Test
    fun `no coefficients means no movement`() {
        val pinhole = distortPixel(900.0, 700.0, centreX, centreY, doubleArrayOf(0.0, 0.0, 0.0))
        assertEquals(900.0, pinhole[0], 1e-12)
        assertEquals(700.0, pinhole[1], 1e-12)
    }

    @Test
    fun `effectiveFor converts the normalized polynomial into pixel space`() {
        // A pixel offset p is the normalized offset p/(edge/2), so the term of
        // order 2n divides by (edge/2)^(2n): k1 by h^2, k2 by h^4, k3 by h^6.
        val effective = RadialDistortion(unitlessCoefficients, 4000).effectiveFor(1000)
        assertNotNull(effective)
        val h = 500.0
        assertEquals(unitlessCoefficients[0] / (h * h), effective!![0], 1e-15)
        assertEquals(unitlessCoefficients[1] / (h * h * h * h), effective[1], 1e-20)
        assertEquals(unitlessCoefficients[2] / (h * h * h * h * h * h), effective[2], 1e-25)
    }

    @Test
    fun `a missing calibration reports nothing to correct`() {
        assertEquals(null, RadialDistortion(doubleArrayOf(), 4000).effectiveFor(1000))
        assertNull(RadialDistortion(doubleArrayOf(-1.0, 0.0, 0.0), 0).effectiveFor(1000))
        assertNull(RadialDistortion(unitlessCoefficients, 4000).effectiveFor(0))
    }

    @Test
    fun `the two resolutions describe the same lens`() {
        // Distorting in a 4000px frame then dividing the coordinates by four is
        // the same physical result as distorting in a 1000px frame: both sample
        // the same lens at the same field of view.
        val atFull = RadialDistortion(unitlessCoefficients, 4000).effectiveFor(4000)!!
        val atScaled = RadialDistortion(unitlessCoefficients, 4000).effectiveFor(1000)!!

        val full = distortPixel(4000.0, 0.0, 2000.0, 0.0, atFull)[0]
        val scaled = distortPixel(1000.0, 0.0, 500.0, 0.0, atScaled)[0]
        assertEquals(full / 4.0, scaled, 1e-6)
    }

    @Test
    fun `a pixel-space coefficient from a realistic lens stays small`() {
        // The bug this guards against: a unitless k1 of -0.02 must come back as
        // ~2e-8 at 2000px, not -0.08. Treating it as the latter pushes every
        // source pixel out of the frame and the stitch covers nothing.
        val effective = RadialDistortion(unitlessCoefficients, 4000).effectiveFor(2000)!!
        assertEquals(-2.0e-8, effective[0], 1e-12)
    }
}
