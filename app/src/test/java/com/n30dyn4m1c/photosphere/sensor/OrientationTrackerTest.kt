package com.n30dyn4m1c.photosphere.sensor

import android.hardware.SensorManager
import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the parts of the tracker that are pure arithmetic.
 *
 * The sensor path itself needs a device (`SensorManager`'s matrix helpers are
 * stubbed out in local unit tests), and is verified by hand through
 * `OrientationDebugScreen`. The `Surface.ROTATION_*` and `SensorManager.AXIS_*`
 * values used here are compile-time constants, so they survive the stub jar.
 */
class OrientationTrackerTest {

    @Test
    fun `normalizeDegrees leaves in-range angles alone`() {
        assertEquals(0f, normalizeDegrees(0f), TOLERANCE)
        assertEquals(90f, normalizeDegrees(90f), TOLERANCE)
        assertEquals(-90f, normalizeDegrees(-90f), TOLERANCE)
        assertEquals(-179.5f, normalizeDegrees(-179.5f), TOLERANCE)
    }

    @Test
    fun `normalizeDegrees wraps onto a half-open range`() {
        // 180 and -180 are the same bearing; the range is [-180, 180).
        assertEquals(-180f, normalizeDegrees(180f), TOLERANCE)
        assertEquals(-180f, normalizeDegrees(-180f), TOLERANCE)
        assertEquals(-90f, normalizeDegrees(270f), TOLERANCE)
        assertEquals(90f, normalizeDegrees(-270f), TOLERANCE)
        assertEquals(0f, normalizeDegrees(360f), TOLERANCE)
        assertEquals(1f, normalizeDegrees(721f), TOLERANCE)
    }

    @Test
    fun `each display rotation maps to its own axis pair`() {
        val pairs = listOf(
            Surface.ROTATION_0,
            Surface.ROTATION_90,
            Surface.ROTATION_180,
            Surface.ROTATION_270,
        ).map { DisplayAxes.forDisplayRotation(it) }

        assertEquals(pairs.size, pairs.distinct().size)
    }

    @Test
    fun `portrait is the identity remap`() {
        val axes = DisplayAxes.forDisplayRotation(Surface.ROTATION_0)
        assertEquals(SensorManager.AXIS_X, axes.axisX)
        assertEquals(SensorManager.AXIS_Y, axes.axisY)
    }

    @Test
    fun `landscape swaps the chassis axes`() {
        val axes = DisplayAxes.forDisplayRotation(Surface.ROTATION_90)
        assertEquals(SensorManager.AXIS_Y, axes.axisX)
        assertEquals(SensorManager.AXIS_MINUS_X, axes.axisY)
    }

    @Test
    fun `an unknown rotation falls back to portrait`() {
        assertEquals(
            DisplayAxes.forDisplayRotation(Surface.ROTATION_0),
            DisplayAxes.forDisplayRotation(-1),
        )
    }

    @Test
    fun `sensor accuracy maps onto the readable enum`() {
        assertEquals(
            OrientationAccuracy.High,
            OrientationAccuracy.fromSensorAccuracy(SensorManager.SENSOR_STATUS_ACCURACY_HIGH),
        )
        assertEquals(
            OrientationAccuracy.Medium,
            OrientationAccuracy.fromSensorAccuracy(SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM),
        )
        assertEquals(
            OrientationAccuracy.Low,
            OrientationAccuracy.fromSensorAccuracy(SensorManager.SENSOR_STATUS_ACCURACY_LOW),
        )
        assertEquals(
            OrientationAccuracy.Unreliable,
            OrientationAccuracy.fromSensorAccuracy(SensorManager.SENSOR_STATUS_UNRELIABLE),
        )
        // SENSOR_STATUS_NO_CONTACT and anything else a vendor invents.
        assertEquals(OrientationAccuracy.Unknown, OrientationAccuracy.fromSensorAccuracy(-1))
    }

    @Test
    fun `only medium and high accuracy count as usable`() {
        assertTrue(OrientationAccuracy.High.isUsable)
        assertTrue(OrientationAccuracy.Medium.isUsable)
        assertFalse(OrientationAccuracy.Low.isUsable)
        assertFalse(OrientationAccuracy.Unreliable.isUsable)
        assertFalse(OrientationAccuracy.Unknown.isUsable)
    }

    @Test
    fun `the default sample has no fix`() {
        assertFalse(OrientationData().hasFix)
        assertTrue(OrientationData(timestampNanos = 1L).hasFix)
    }

    private companion object {
        const val TOLERANCE = 1e-4f
    }
}
