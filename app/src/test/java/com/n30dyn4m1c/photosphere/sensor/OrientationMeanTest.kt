package com.n30dyn4m1c.photosphere.sensor

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.abs

/**
 * The dwell-window pose averaging that quiets the sensor for the frame it
 * stamps onto the sphere.
 */
class OrientationMeanTest {

    @Test
    fun `a plain set of angles averages as expected`() {
        assertEquals(5f, meanAngleDegrees(listOf(0f, 10f)), 1e-3f)
        assertEquals(-5f, meanAngleDegrees(listOf(-10f, 0f)), 1e-3f)
    }

    @Test
    fun `a crossing of plus or minus one eighty unwraps instead of smearing`() {
        // 179° and -179° are 2° apart, and the short arc between them passes
        // through the date line, so their mean is ±180° — not the 0° a naive
        // average would claim.
        assertEquals(180f, abs(meanAngleDegrees(listOf(179f, -179f))), 1e-3f)
        assertEquals(180f, abs(meanAngleDegrees(listOf(-179f, 179f))), 1e-3f)
    }

    @Test
    fun `the mean orientation of a window is the mean of its angles`() {
        val mean = meanOrientation(
            listOf(
                OrientationData(yawDegrees = 10f, pitchDegrees = 1f, rollDegrees = -2f, timestampNanos = 1L),
                OrientationData(yawDegrees = 12f, pitchDegrees = 3f, rollDegrees = 0f, timestampNanos = 2L),
            )
        )

        assertEquals(11f, mean.yawDegrees, 1e-3f)
        assertEquals(2f, mean.pitchDegrees, 1e-3f)
        assertEquals(-1f, mean.rollDegrees, 1e-3f)
    }

    @Test
    fun `the mean carries the freshest accuracy and timestamp`() {
        val mean = meanOrientation(
            listOf(
                OrientationData(yawDegrees = 0f, accuracy = OrientationAccuracy.Low, timestampNanos = 1L),
                OrientationData(yawDegrees = 1f, accuracy = OrientationAccuracy.Medium, timestampNanos = 2L),
            )
        )

        assertEquals(OrientationAccuracy.Medium, mean.accuracy)
        assertEquals(2L, mean.timestampNanos)
    }

    @Test
    fun `samples without a fix are ignored`() {
        val mean = meanOrientation(
            listOf(
                OrientationData(),
                OrientationData(yawDegrees = 20f, timestampNanos = 5L),
            )
        )
        assertEquals(20f, mean.yawDegrees, 1e-3f)
        assertEquals(5L, mean.timestampNanos)
    }
}
