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

    // A realistic phone-lens barrel distortion calibrated on a 4000px sensor:
    // about 2% inward at the corner, with the higher-order terms small.
    private val fullCoefficients = doubleArrayOf(-5.0e-9, -1.0e-16, 1.0e-22)

    // The same distortion rescaled to a ~1024px working image, which is how the
    // pipeline feeds it to the renderer.
    private val smallCoefficients =
        RadialDistortion(fullCoefficients, 4000).effectiveFor(1024)!!

    private val centreX = 512.0
    private val centreY = 384.0

    @Test
    fun `the optical centre is unmoved by distortion`() {
        val distorted = distortPixel(centreX, centreY, centreX, centreY, fullCoefficients)
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
    fun `effectiveFor rescales each radial term by the right power of the scale`() {
        val effective = RadialDistortion(fullCoefficients, 4000).effectiveFor(1000)
        assertNotNull(effective)
        // A 4x downsample: k1 * 16, k2 * 256, k3 * 4096.
        assertEquals(fullCoefficients[0] * 16.0, effective!![0], 1e-12)
        assertEquals(fullCoefficients[1] * 256.0, effective[1], 1e-15)
        assertEquals(fullCoefficients[2] * 4096.0, effective[2], 1e-22)
    }

    @Test
    fun `a missing calibration reports nothing to correct`() {
        assertEquals(null, RadialDistortion(doubleArrayOf(), 4000).effectiveFor(1000))
        assertNull(RadialDistortion(doubleArrayOf(-1.0, 0.0, 0.0), 0).effectiveFor(1000))
    }

    @Test
    fun `the two scales of distortion agree at the shared resolution`() {
        // Distorting at full resolution then downsampling the *coordinates* is
        // the same as downsampling the coefficients first.
        val scaled = RadialDistortion(fullCoefficients, 4000).effectiveFor(1000)!!

        val atFull = distortPixel(4000.0, 0.0, 2000.0, 0.0, fullCoefficients)[0]
        val atScaled = distortPixel(1000.0, 0.0, 500.0, 0.0, scaled)[0]
        assertEquals(atFull / 4.0, atScaled, 1e-6)
    }
}
