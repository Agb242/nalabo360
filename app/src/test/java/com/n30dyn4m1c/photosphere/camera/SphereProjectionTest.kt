package com.n30dyn4m1c.photosphere.camera

import com.n30dyn4m1c.photosphere.sensor.OrientationData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt
import kotlin.math.tan

private const val TOLERANCE = 1e-3f

/**
 * The projection is pure arithmetic over [OrientationData], so all of it runs in
 * a local unit test — no sensor, no Android framework, no device.
 */
class SphereProjectionTest {

    private fun orientation(yaw: Float, pitch: Float, roll: Float = 0f) =
        OrientationData(yawDegrees = yaw, pitchDegrees = pitch, rollDegrees = roll)

    @Test
    fun `a target the camera is already aimed at sits dead centre`() {
        val view = SphereProjection.project(
            orientation(yaw = 30f, pitch = -12f, roll = 7f),
            SphereTarget(yawDegrees = 30f, pitchDegrees = -12f),
        )

        assertEquals(0f, view.x, TOLERANCE)
        assertEquals(0f, view.y, TOLERANCE)
        assertEquals(1f, view.z, TOLERANCE)
        assertEquals(0f, view.angularDistanceDegrees, TOLERANCE)
        assertTrue(view.isInFront)
    }

    @Test
    fun `a target further clockwise falls to the right of frame`() {
        val view = SphereProjection.project(
            orientation(yaw = 0f, pitch = 0f),
            SphereTarget(yawDegrees = 10f, pitchDegrees = 0f),
        )

        assertTrue("expected a right-of-centre offset, was ${view.x}", view.x > 0f)
        assertEquals(0f, view.y, TOLERANCE)
        assertEquals(10f, view.angularDistanceDegrees, TOLERANCE)
    }

    @Test
    fun `a target above the horizon falls above centre`() {
        // Negative pitch is up, per OrientationData.
        val view = SphereProjection.project(
            orientation(yaw = 0f, pitch = 0f),
            SphereTarget(yawDegrees = 0f, pitchDegrees = -10f),
        )

        assertEquals(0f, view.x, TOLERANCE)
        assertTrue("expected an above-centre offset, was ${view.y}", view.y > 0f)
        assertEquals(10f, view.angularDistanceDegrees, TOLERANCE)
    }

    @Test
    fun `rolling the device turns the scene the other way`() {
        // Top edge rolled to the right by a quarter turn: what was overhead is
        // now off the left of the frame.
        val view = SphereProjection.project(
            orientation(yaw = 0f, pitch = 0f, roll = 90f),
            SphereTarget(yawDegrees = 0f, pitchDegrees = -89.99f),
        )

        assertTrue("expected a left-of-centre offset, was ${view.x}", view.x < 0f)
        assertEquals(0f, view.y, TOLERANCE)
    }

    @Test
    fun `a target behind the camera is not projectable`() {
        val view = SphereProjection.project(
            orientation(yaw = 0f, pitch = 0f),
            SphereTarget(yawDegrees = 180f, pitchDegrees = 0f),
        )

        assertFalse(view.isInFront)
        assertEquals(180f, view.angularDistanceDegrees, TOLERANCE)
    }

    @Test
    fun `distance is measured the short way around north`() {
        val distance = SphereProjection.angularDistanceDegrees(
            orientation(yaw = 179f, pitch = 0f),
            SphereTarget(yawDegrees = -179f, pitchDegrees = 0f),
        )

        assertEquals(2f, distance, TOLERANCE)
    }

    @Test
    fun `distance ignores roll`() {
        val target = SphereTarget(yawDegrees = 55f, pitchDegrees = 10f)
        val level = SphereProjection.angularDistanceDegrees(orientation(20f, -30f), target)
        val rolled = SphereProjection.angularDistanceDegrees(orientation(20f, -30f, 137f), target)

        // Turning the phone in its own plane does not change where it is aimed.
        assertEquals(level, rolled, TOLERANCE)
    }

    @Test
    fun `camera axes stay orthonormal at an awkward attitude`() {
        // Three perpendicular world directions must stay perpendicular and unit
        // length once expressed in the camera frame. This is what guarantees the
        // rolled basis is a rotation rather than a skew.
        val at = orientation(yaw = 37f, pitch = -22f, roll = 61f)
        val east = SphereProjection.project(at, SphereTarget(90f, 0f)).vector()
        val north = SphereProjection.project(at, SphereTarget(0f, 0f)).vector()
        val up = SphereProjection.project(at, SphereTarget(0f, -90f)).vector()

        listOf(east, north, up).forEach { assertEquals(1f, it.length(), TOLERANCE) }
        assertEquals(0f, east dot north, TOLERANCE)
        assertEquals(0f, east dot up, TOLERANCE)
        assertEquals(0f, north dot up, TOLERANCE)
    }

    @Test
    fun `focal length is the one that keeps both axes in view`() {
        // A 90-degree field of view puts the edge exactly one half-extent from
        // the centre, so the focal length is that half-extent.
        val square = SphereProjection.focalLengthPx(
            widthPx = 1000f,
            heightPx = 1000f,
            fieldOfView = FieldOfView(horizontalDegrees = 90f, verticalDegrees = 90f),
        )
        assertEquals(500f, square, 0.1f)

        // Portrait phone: the vertical axis is the wide one, and the taller
        // viewport wins, cropping the horizontal.
        val portrait = SphereProjection.focalLengthPx(
            widthPx = 1080f,
            heightPx = 2400f,
            fieldOfView = FieldOfView(horizontalDegrees = 52f, verticalDegrees = 66f),
        )
        val horizontalCandidate = 540f / tan(Math.toRadians(26.0)).toFloat()
        val verticalCandidate = 1200f / tan(Math.toRadians(33.0)).toFloat()
        assertEquals(maxOf(horizontalCandidate, verticalCandidate), portrait, 0.1f)
        assertTrue(portrait >= horizontalCandidate)
    }

    @Test
    fun `a marker one field of view across lands at the frame edge`() {
        // Half the horizontal field of view to the right of a level camera puts
        // the target on the right edge of a viewport that is not cropped.
        val fov = FieldOfView(horizontalDegrees = 90f, verticalDegrees = 90f)
        val focal = SphereProjection.focalLengthPx(1000f, 1000f, fov)
        val view = SphereProjection.project(
            orientation(yaw = 0f, pitch = 0f),
            SphereTarget(yawDegrees = 45f, pitchDegrees = 0f),
        )

        val offsetPx = view.x / view.z * focal
        assertEquals(500f, offsetPx, 0.5f)
    }

    private fun TargetView.vector() = floatArrayOf(x, y, z)

    private fun FloatArray.length() = sqrt(this[0] * this[0] + this[1] * this[1] + this[2] * this[2])

    private infix fun FloatArray.dot(other: FloatArray) =
        this[0] * other[0] + this[1] * other[1] + this[2] * other[2]
}
