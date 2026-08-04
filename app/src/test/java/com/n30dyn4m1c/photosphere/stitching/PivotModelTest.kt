package com.n30dyn4m1c.photosphere.stitching

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

private const val TOLERANCE = 1e-6

/**
 * The correction for turning the phone around yourself instead of around the
 * lens.
 *
 * A hand-held sphere is shot by swivelling on the spot, which puts the camera
 * on the end of a lever a third of a metre long rather than on the axis. That
 * is translation between frames, and translation is what a panorama is not
 * allowed to have — so the pipeline models the lever explicitly and refers
 * everything back to the axis the user actually turned about.
 */
class PivotModelTest {

    /** Level, facing north. */
    private val basis = CameraBasis.of(
        CameraPose(yawDegrees = 0f, pitchDegrees = 0f, rollDegrees = 0f)
    )

    private val intrinsics = FrameIntrinsics.fromFieldOfView(
        widthPx = 1000,
        heightPx = 1000,
        horizontalFovDegrees = 60f,
        verticalFovDegrees = 60f,
    )

    @Test
    fun `only the ratio of the two lengths survives`() {
        // Half a metre at twenty is the same geometry as a metre at forty.
        assertEquals(
            PivotModel(leverArmMeters = 0.5f, sceneDistanceMeters = 20f).ratio,
            PivotModel(leverArmMeters = 1f, sceneDistanceMeters = 40f).ratio,
            TOLERANCE,
        )
        assertEquals(0.035, PivotModel.HandheldBodySwivel.ratio, 1e-4)
    }

    @Test
    fun `a lens on the axis is no correction at all`() {
        assertEquals(0.0, PivotModel.None.ratio, TOLERANCE)
    }

    @Test
    fun `a scene closer than the correction can describe is clamped`() {
        // Arm's length from the wall: no projection saves this, and pretending
        // otherwise would bend the frames further apart than parallax bent them
        // together.
        val absurd = PivotModel(leverArmMeters = 0.35f, sceneDistanceMeters = 0.35f)

        assertEquals(PivotModel.MAX_RATIO, absurd.ratio, TOLERANCE)
    }

    @Test
    fun `without a lever arm a ray points where it always did`() {
        val direction = unit(0.3, 0.9, 0.2)
        val referred = pivotDirection(basis, 0.0, direction[0], direction[1], direction[2])

        assertEquals(direction[0], referred[0], TOLERANCE)
        assertEquals(direction[1], referred[1], TOLERANCE)
        assertEquals(direction[2], referred[2], TOLERANCE)
    }

    @Test
    fun `a referred ray is still a direction`() {
        listOf(0.02, 0.1, PivotModel.MAX_RATIO).forEach { ratio ->
            listOf(unit(0.0, 1.0, 0.0), unit(0.5, 0.85, 0.15), unit(-0.7, 0.7, -0.1))
                .forEach { direction ->
                    val referred =
                        pivotDirection(basis, ratio, direction[0], direction[1], direction[2])
                    val length = sqrt(
                        referred[0] * referred[0] +
                            referred[1] * referred[1] +
                            referred[2] * referred[2]
                    )
                    assertEquals("ratio $ratio left a non-unit direction", 1.0, length, 1e-9)
                }
        }
    }

    @Test
    fun `the optical axis is unmoved by a lever arm along it`() {
        // The lever points down the axis, so the one direction it cannot swing
        // is the axis itself.
        val referred = pivotDirection(basis, 0.1, 0.0, 1.0, 0.0)

        assertEquals(0.0, referred[0], TOLERANCE)
        assertEquals(1.0, referred[1], TOLERANCE)
        assertEquals(0.0, referred[2], TOLERANCE)
    }

    @Test
    fun `a camera held out in front sees a narrower slice of the sphere`() {
        // 30° off the axis at the lens is under 28° off it from the pivot: the
        // camera has moved towards what it is looking at, so the same frame
        // spans less of the sphere the canvas is measured in.
        val border = unit(0.5, sqrt(3.0) / 2.0, 0.0)
        val referred = pivotDirection(basis, 0.1, border[0], border[1], border[2])
        val angle = Math.toDegrees(Math.atan2(referred[0], referred[1]))

        assertTrue("expected less than 30°, was $angle", angle < 29.0)
        assertTrue("expected the slice to narrow, not collapse, was $angle", angle > 25.0)
    }

    @Test
    fun `the projection and the referral are inverses of each other`() {
        // What the renderer relies on: a pivot direction projected through the
        // shortened depth lands on the same pixel the original camera ray came
        // out of. If these two disagreed, every frame would be sampled from the
        // wrong place by exactly the amount the correction was worth.
        val ratio = 0.08
        val cameraRay = unit(0.4, 1.0, 0.25)
        val expected = projectDirection(
            basis, intrinsics, cameraRay[0], cameraRay[1], cameraRay[2],
        )
        assertNotNull("the test ray has to be inside the frame to prove anything", expected)

        val fromPivot = pivotDirection(basis, ratio, cameraRay[0], cameraRay[1], cameraRay[2])
        val actual = projectDirection(
            basis, intrinsics, fromPivot[0], fromPivot[1], fromPivot[2], ratio,
        )

        assertNotNull(actual)
        assertEquals(expected!![0], actual!![0], 1e-6)
        assertEquals(expected[1], actual[1], 1e-6)
    }

    @Test
    fun `a frame's footprint shrinks once the lens is off the axis`() {
        val onAxis = FrameFootprint.compute(
            basis = basis,
            intrinsics = intrinsics,
            canvasWidth = 4096,
            canvasHeight = 2048,
        )
        val offAxis = FrameFootprint.compute(
            basis = basis,
            intrinsics = intrinsics,
            canvasWidth = 4096,
            canvasHeight = 2048,
            pivotRatio = 0.1,
        )

        assertTrue(
            "footprint ${offAxis.columnSpan} did not narrow from ${onAxis.columnSpan}",
            offAxis.columnSpan < onAxis.columnSpan,
        )
        assertTrue(
            "footprint ${offAxis.rowSpan} did not narrow from ${onAxis.rowSpan}",
            offAxis.rowSpan < onAxis.rowSpan,
        )
        // Still recognisably the same frame, not a collapsed one.
        assertTrue(offAxis.columnSpan > onAxis.columnSpan * 0.8)
    }

    private fun unit(x: Double, y: Double, z: Double): DoubleArray {
        val length = sqrt(x * x + y * y + z * z)
        return doubleArrayOf(x / length, y / length, z / length)
    }
}
