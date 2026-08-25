package com.n30dyn4m1c.photosphere.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val TOLERANCE = 0.5f

/**
 * The arithmetic that turns what a camera says about itself into the field of
 * view everything else is scaled by.
 *
 * This is the number that decides how far apart the capture plan spaces its
 * targets, so getting it wrong does not show up as a wrong-looking overlay —
 * it shows up as frames that do not overlap and a run that will not stitch.
 * None of it touches the framework, so all of it runs here.
 */
class CameraOpticsTest {

    // -- Picking a lens out of a logical camera's list ------------------------

    /** Galaxy S23-shaped: ultrawide, main, 3x telephoto behind one logical id. */
    private val threeLenses = floatArrayOf(2.2f, 6.3f, 17.0f)

    /** Diagonal of a 1/1.56" main sensor, near enough. */
    private val sensorDiagonalMm = 9.5f

    @Test
    fun `a single-lens camera is taken at its word`() {
        assertEquals(
            6.3f,
            selectFocalLengthMm(floatArrayOf(6.3f), sensorDiagonalMm, preferWidest = false)!!,
            1e-4f,
        )
    }

    @Test
    fun `a camera that lists nothing has no focal length to offer`() {
        assertNull(selectFocalLengthMm(null, sensorDiagonalMm, preferWidest = false))
        assertNull(selectFocalLengthMm(floatArrayOf(), sensorDiagonalMm, preferWidest = false))
        assertNull(selectFocalLengthMm(floatArrayOf(0f, -1f), sensorDiagonalMm, preferWidest = false))
    }

    @Test
    fun `the main lens is picked out of a multi-camera rather than the ultrawide`() {
        // The bug this exists to stop: taking the first entry hands back the
        // ultrawide's 2.2 mm while the session streams the main lens, which
        // nearly doubles the field of view and spaces the plan clean past any
        // overlap at all.
        val focal = selectFocalLengthMm(threeLenses, sensorDiagonalMm, preferWidest = false)

        assertEquals(6.3f, focal!!, 1e-4f)
    }

    @Test
    fun `a profile that asked for the widest lens gets it`() {
        val focal = selectFocalLengthMm(threeLenses, sensorDiagonalMm, preferWidest = true)

        assertEquals(2.2f, focal!!, 1e-4f)
    }

    @Test
    fun `without a sensor size the safe guess is the narrowest lens`() {
        // Guessing narrow costs frames; guessing wide costs the run.
        val focal = selectFocalLengthMm(threeLenses, sensorDiagonalMm = 0f, preferWidest = false)

        assertEquals(17.0f, focal!!, 1e-4f)
    }

    // -- Reconciling the two estimates ---------------------------------------

    private val geometry = FieldOfView(horizontalDegrees = 66f, verticalDegrees = 52f)

    @Test
    fun `a calibration the geometry agrees with is the one that is used`() {
        val calibration = FieldOfView(horizontalDegrees = 68f, verticalDegrees = 53f)

        assertEquals(calibration, reconcileFieldOfView(calibration, geometry))
    }

    @Test
    fun `a calibration quoted against the wrong crop is thrown out`() {
        // The classic failure: a focal length in pixels of the full array
        // divided through by a binned array, which halves the answer.
        val calibration = FieldOfView(horizontalDegrees = 36f, verticalDegrees = 28f)

        assertEquals(geometry, reconcileFieldOfView(calibration, geometry))
    }

    @Test
    fun `either estimate alone is better than nothing`() {
        assertEquals(geometry, reconcileFieldOfView(null, geometry))
        assertEquals(geometry, reconcileFieldOfView(geometry, null))
        // And with neither, the fallback still has to be a legal field of view.
        val fallback = reconcileFieldOfView(null, null)
        assertTrue(fallback.horizontalDegrees in 1f..179f)
        assertTrue(fallback.verticalDegrees in 1f..179f)
    }

    // -- Cropping the array's angles down to the stream ----------------------

    /** A 4:3 sensor seeing 66° across and 52° down. */
    private val sensor = FieldOfView(horizontalDegrees = 66f, verticalDegrees = 52f)

    @Test
    fun `a stream the same shape as the sensor sees all of it`() {
        val stream = croppedToStreamAspect(sensor, 4f / 3f, 4f / 3f)

        assertEquals(sensor.horizontalDegrees, stream.horizontalDegrees, TOLERANCE)
        assertEquals(sensor.verticalDegrees, stream.verticalDegrees, TOLERANCE)
    }

    @Test
    fun `an unknown stream shape leaves the sensor angles alone`() {
        assertEquals(sensor, croppedToStreamAspect(sensor, 4f / 3f, 0f))
        assertEquals(sensor, croppedToStreamAspect(sensor, 0f, 16f / 9f))
    }

    @Test
    fun `a widescreen stream off a four by three sensor loses height not width`() {
        val stream = croppedToStreamAspect(sensor, 4f / 3f, 16f / 9f)

        // Camera2 crops rather than squeezes, so the full width survives...
        assertEquals(sensor.horizontalDegrees, stream.horizontalDegrees, TOLERANCE)
        // ...and the height is cut by the quarter the 16:9 window throws away.
        assertTrue(
            "expected the vertical angle to shrink, was ${stream.verticalDegrees}",
            stream.verticalDegrees < sensor.verticalDegrees - 5f,
        )
    }

    @Test
    fun `a taller stream loses width instead`() {
        val stream = croppedToStreamAspect(sensor, 4f / 3f, 1f)

        assertEquals(sensor.verticalDegrees, stream.verticalDegrees, TOLERANCE)
        assertTrue(
            "expected the horizontal angle to shrink, was ${stream.horizontalDegrees}",
            stream.horizontalDegrees < sensor.horizontalDegrees - 5f,
        )
    }

    @Test
    fun `cropping is consistent with the shape it crops to`() {
        // The point of the crop: what comes back really is the requested shape,
        // measured the way a pinhole measures shape — in tangents, not degrees.
        listOf(16f / 9f, 3f / 2f, 1f, 3f / 4f).forEach { requested ->
            val stream = croppedToStreamAspect(sensor, 4f / 3f, requested)
            val ratio = tanHalfOf(stream.horizontalDegrees) / tanHalfOf(stream.verticalDegrees)
            assertEquals("aspect $requested came back as $ratio", requested, ratio, 0.02f)
        }
    }

    private fun tanHalfOf(degrees: Float): Float =
        kotlin.math.tan(Math.toRadians(degrees / 2.0)).toFloat()
}
